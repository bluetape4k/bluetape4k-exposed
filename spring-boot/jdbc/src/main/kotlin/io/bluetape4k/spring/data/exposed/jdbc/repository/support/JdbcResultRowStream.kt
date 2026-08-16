package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.stream.Stream
import java.util.stream.StreamSupport

@OptIn(InternalApi::class)
internal object JdbcResultRowStream {

    fun <R: Any> open(
        transaction: JdbcTransaction,
        query: Query,
        mapper: (Int, ResultRow) -> R,
    ): Stream<R> = try {
        transaction.execQuery(query) { resultSet ->
            fromResultSet(transaction, query, resultSet, mapper)
        } ?: error("JDBC FluentQuery expected a ResultSet.")
    } catch (cause: SQLException) {
        throw DataAccessResourceFailureException(
            "JDBC FluentQuery could not execute the query.",
            sanitizedSqlException(cause, "JDBC query execution failed."),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun <R: Any> fromResultSet(
        transaction: JdbcTransaction,
        query: Query,
        resultSet: ResultSet,
        mapper: (Int, ResultRow) -> R,
    ): Stream<R> = try {
        Lease(transaction, query, resultSet, mapper).stream()
    } catch (cause: Exception) {
        closeJdbcResources(resultSet)?.let(cause::addSuppressed)
        throw cause
    }

    private class Lease<R: Any>(
        private val transaction: JdbcTransaction,
        query: Query,
        private val resultSet: ResultSet,
        private val mapper: (Int, ResultRow) -> R,
    ): AutoCloseable {

        private val ownerThreadId = Thread.currentThread().threadId()
        private val state = AtomicReference(LeaseState.OPEN)
        private val nestedStatementGuard = object: StatementInterceptor {
            override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                if (state.get() != LeaseState.CLOSED) {
                    throw InvalidDataAccessApiUsageException(
                        "Nested Exposed SQL is not allowed while a JDBC FluentQuery stream is open.",
                    )
                }
            }
        }
        private val fieldIndex: Map<Expression<*>, Int> = query.set.realFields
            .distinct()
            .mapIndexed { index, expression -> expression to index }
            .toMap()
        private val columnTypes: Array<IColumnType<*>?> = ResultRow.columnTypesOf(fieldIndex)
        private var rowIndex = 0

        init {
            transaction.registerInterceptor(nestedStatementGuard)
        }

        fun stream(): Stream<R> {
            val spliterator = object: Spliterators.AbstractSpliterator<R>(
                Long.MAX_VALUE,
                Spliterator.ORDERED or Spliterator.NONNULL,
            ) {
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                override fun tryAdvance(action: Consumer<in R>): Boolean {
                    validateActiveTransaction()
                    return try {
                        if (!advanceCursor()) {
                            close()
                            false
                        } else {
                            val row = ResultRow.create(JdbcResult(resultSet), fieldIndex, columnTypes)
                            action.accept(mapper(rowIndex++, row))
                            true
                        }
                    } catch (cause: SQLException) {
                        val failure = DataAccessResourceFailureException(
                            "JDBC FluentQuery row materialization failed.",
                            sanitizedSqlException(cause, "JDBC row materialization failed."),
                        )
                        closeAfterFailure(failure)
                        throw failure
                    } catch (cause: Throwable) {
                        closeAfterFailure(cause)
                        throw cause
                    }
                }
            }
            return StreamSupport.stream(spliterator, false).onClose(::close)
        }

        private fun validateActiveTransaction() {
            if (state.get() != LeaseState.OPEN) {
                throw InvalidDataAccessApiUsageException("JDBC FluentQuery stream is already closed.")
            }
            if (Thread.currentThread().threadId() != ownerThreadId ||
                TransactionManager.currentOrNull() !== transaction
            ) {
                throw InvalidDataAccessApiUsageException(
                    "JDBC FluentQuery stream must be consumed on its owner thread inside the originating transaction.",
                )
            }
        }

        private fun advanceCursor(): Boolean = try {
            resultSet.next()
        } catch (cause: SQLException) {
            throw DataAccessResourceFailureException(
                "JDBC FluentQuery cursor could not advance; avoid nested SQL while consuming the stream.",
                sanitizedSqlException(cause, "JDBC cursor advance failed."),
            )
        }

        @Suppress("TooGenericExceptionCaught")
        override fun close() {
            if (state.get() == LeaseState.CLOSED) return
            validateOwnerContext()
            if (!beginClose()) return

            val cleanupFailure = closeJdbcResources(resultSet)
            if (cleanupFailure != null) {
                state.set(LeaseState.CLOSE_FAILED)
                throw cleanupFailure
            }

            try {
                transaction.unregisterInterceptor(nestedStatementGuard)
                state.set(LeaseState.CLOSED)
            } catch (cause: RuntimeException) {
                state.set(LeaseState.CLOSE_FAILED)
                throw cause
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun closeAfterFailure(failure: Throwable) {
            try {
                close()
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
        }

        private fun validateOwnerContext() {
            if (Thread.currentThread().threadId() != ownerThreadId ||
                TransactionManager.currentOrNull() !== transaction
            ) {
                throw InvalidDataAccessApiUsageException(
                    "JDBC FluentQuery stream must be closed on its owner thread inside the originating transaction.",
                )
            }
        }

        private fun beginClose(): Boolean {
            while (true) {
                when (val current = state.get()) {
                    LeaseState.CLOSED -> return false
                    LeaseState.CLOSING -> throw InvalidDataAccessApiUsageException(
                        "JDBC FluentQuery stream close is already in progress.",
                    )
                    LeaseState.OPEN,
                    LeaseState.CLOSE_FAILED,
                    -> if (state.compareAndSet(current, LeaseState.CLOSING)) return true
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeJdbcResources(resultSet: ResultSet): DataAccessResourceFailureException? {
        var statement: java.sql.Statement? = null
        val failures = buildList {
            try {
                statement = resultSet.statement
            } catch (cause: Exception) {
                add(sanitizedJdbcCleanupException(cause, "JDBC Statement lookup failed."))
            }
            try {
                resultSet.close()
            } catch (cause: Exception) {
                add(sanitizedJdbcCleanupException(cause, "JDBC ResultSet close failed."))
            }
            try {
                statement?.close()
            } catch (cause: Exception) {
                add(sanitizedJdbcCleanupException(cause, "JDBC Statement close failed."))
            }
        }
        if (failures.isEmpty()) return null

        return DataAccessResourceFailureException(
            "JDBC FluentQuery cursor cleanup failed.",
            failures.first(),
        ).also { failure -> failures.drop(1).forEach(failure::addSuppressed) }
    }

    private enum class LeaseState {
        OPEN,
        CLOSING,
        CLOSE_FAILED,
        CLOSED,
    }
}
