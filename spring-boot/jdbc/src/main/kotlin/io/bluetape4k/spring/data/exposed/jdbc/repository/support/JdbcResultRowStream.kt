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
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.stream.Stream
import java.util.stream.StreamSupport

@OptIn(InternalApi::class)
internal object JdbcResultRowStream {

    fun <R: Any> open(
        transaction: JdbcTransaction,
        query: Query,
        mapper: (Int, ResultRow) -> R,
    ): Stream<R> = transaction.execQuery(query) { resultSet ->
        fromResultSet(transaction, query, resultSet, mapper)
    } ?: error("JDBC FluentQuery expected a ResultSet.")

    internal fun <R: Any> fromResultSet(
        transaction: JdbcTransaction,
        query: Query,
        resultSet: ResultSet,
        mapper: (Int, ResultRow) -> R,
    ): Stream<R> = Lease(transaction, query, resultSet, mapper).stream()

    private class Lease<R: Any>(
        private val transaction: JdbcTransaction,
        query: Query,
        private val resultSet: ResultSet,
        private val mapper: (Int, ResultRow) -> R,
    ): AutoCloseable {

        private val ownerThreadId = Thread.currentThread().threadId()
        private val closed = AtomicBoolean(false)
        private val nestedStatementGuard = object: StatementInterceptor {
            override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                if (!closed.get()) {
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
                override fun tryAdvance(action: Consumer<in R>): Boolean {
                    validateActiveTransaction()
                    var completed = false
                    return try {
                        if (!advanceCursor()) {
                            close()
                            completed = true
                            false
                        } else {
                            val row = ResultRow.create(JdbcResult(resultSet), fieldIndex, columnTypes)
                            action.accept(mapper(rowIndex++, row))
                            completed = true
                            true
                        }
                    } finally {
                        if (!completed) close()
                    }
                }
            }
            return StreamSupport.stream(spliterator, false).onClose(::close)
        }

        private fun validateActiveTransaction() {
            if (closed.get()) {
                throw InvalidDataAccessApiUsageException("JDBC FluentQuery stream is already closed.")
            }
            if (Thread.currentThread().threadId() != ownerThreadId ||
                TransactionManager.currentOrNull() !== transaction
            ) {
                close()
                throw InvalidDataAccessApiUsageException(
                    "JDBC FluentQuery stream must be consumed on its owner thread inside the originating transaction.",
                )
            }
        }

        private fun advanceCursor(): Boolean = try {
            resultSet.next()
        } catch (cause: java.sql.SQLException) {
            throw DataAccessResourceFailureException(
                "JDBC FluentQuery cursor could not advance; avoid nested SQL while consuming the stream.",
                cause,
            )
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            transaction.unregisterInterceptor(nestedStatementGuard)
            val statement = runCatching { resultSet.statement }.getOrNull()
            runCatching { resultSet.close() }
            runCatching { statement?.close() }
        }
    }
}
