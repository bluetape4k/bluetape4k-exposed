package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.spring.data.exposed.jdbc.AbstractExposedJdbcRepositoryTest
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import io.bluetape4k.spring.data.exposed.jdbc.domain.Users
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcResultRowStream
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.InvalidDataAccessApiUsageException
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JdbcResultRowStreamTest: AbstractExposedJdbcRepositoryTest() {

    @Test
    fun `exhaustion closes result set and statement exactly once`() {
        withCountingStream { stream, counters ->
            stream.toList() shouldBeEqualTo listOf("Alice", "Bob")
            counters.next.get() shouldBeEqualTo 3
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `explicit and repeated close are idempotent before first advance`() {
        withCountingStream { stream, counters ->
            val iterator = stream.iterator()
            counters.next.get() shouldBeEqualTo 0
            stream.close()
            stream.close()
            assertFailsWith<InvalidDataAccessApiUsageException> { iterator.next() }
            counters.next.get() shouldBeEqualTo 0
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `mapper failure closes both resources exactly once`() {
        withCountingStream(mapper = { _, _ -> error("mapping failed") }) { stream, counters ->
            assertFailsWith<IllegalStateException> { stream.findFirst() }
            counters.next.get() shouldBeEqualTo 1
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `mapper error closes both resources before propagating`() {
        withCountingStream(mapper = { _, _ -> throw AssertionError("fatal mapping failure") }) { stream, counters ->
            assertFailsWith<AssertionError> { stream.findFirst() }
            counters.next.get() shouldBeEqualTo 1
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `short circuit closes result set and statement exactly once`() {
        withCountingStream { stream, counters ->
            stream.use { it.findFirst().orElseThrow() shouldBeEqualTo "Alice" }
            counters.next.get() shouldBeEqualTo 1
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `statement lookup failure is redacted after releasing the result set`() {
        withCountingStream(statementAccessible = false) { stream, counters ->
            val failure = assertFailsWith<DataAccessResourceFailureException> { stream.close() }
            throwableGraph(failure).contains("statement closed").shouldBeFalse()
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `wrong thread consumption leaves the owner lease open`() {
        withCountingStream { stream, counters ->
            val observed = AtomicReference<Throwable>()
            MultithreadingTester()
                .workers(1)
                .rounds(1)
                .add {
                    try {
                        stream.findFirst()
                    } catch (cause: Throwable) {
                        observed.set(cause)
                    }
                }
                .run()

            observed.get().shouldBeInstanceOf<InvalidDataAccessApiUsageException>()
            counters.resultSetClose.get() shouldBeEqualTo 0
            counters.statementClose.get() shouldBeEqualTo 0

            stream.close()
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `cursor advance failure uses a stable data access exception and closes resources`() {
        val driverFailure = SQLException("sensitive SQL: select secret_column", "42000", 1234)
        withCountingStream(nextFailure = driverFailure) { stream, counters ->
            val failure = assertFailsWith<DataAccessResourceFailureException> { stream.findFirst() }
            throwableGraph(failure).contains("sensitive SQL").shouldBeFalse()
            (failure.cause as SQLException).sqlState shouldBeEqualTo "42000"
            failure.cause?.cause shouldBeEqualTo null
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `row materialization failure redacts the driver cause and closes resources`() {
        val driverFailure = SQLException("sensitive SQL: select secret_column", "S1000", 9876)
        withCountingStream(getObjectFailure = driverFailure) { stream, counters ->
            val failure = assertFailsWith<DataAccessResourceFailureException> { stream.findFirst() }
            throwableGraph(failure).contains("sensitive SQL").shouldBeFalse()
            (failure.cause as SQLException).sqlState shouldBeEqualTo "S1000"
            failure.cause?.cause shouldBeEqualTo null
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `close failure is redacted and does not skip the remaining resource`() {
        withCountingStream(
            resultSetCloseFailure = SQLException("sensitive result-set close"),
            statementCloseFailure = SQLException("sensitive statement close"),
        ) { stream, counters ->
            val failure = assertFailsWith<DataAccessResourceFailureException> { stream.close() }
            throwableGraph(failure).contains("sensitive").shouldBeFalse()
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1

            assertFailsWith<InvalidDataAccessApiUsageException> { Users.select(Users.name).toList() }
        }
    }

    @Test
    fun `unchecked close failure is redacted and does not skip the remaining resource`() {
        withCountingStream(
            resultSetCloseFailure = IllegalStateException("sensitive result-set close"),
            statementCloseFailure = IllegalStateException("sensitive statement close"),
        ) { stream, counters ->
            val failure = assertFailsWith<DataAccessResourceFailureException> { stream.close() }
            throwableGraph(failure).contains("sensitive").shouldBeFalse()
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    private fun withCountingStream(
        mapper: (Int, org.jetbrains.exposed.v1.core.ResultRow) -> String = { _, row -> row[Users.name] },
        statementAccessible: Boolean = true,
        nextFailure: SQLException? = null,
        getObjectFailure: SQLException? = null,
        resultSetCloseFailure: Throwable? = null,
        statementCloseFailure: Throwable? = null,
        block: (java.util.stream.Stream<String>, CloseCounters) -> Unit,
    ) {
        transaction transactionBlock@{
            UserEntity.new { name = "Alice"; email = "alice@cursor.test"; age = 30 }
            UserEntity.new { name = "Bob"; email = "bob@cursor.test"; age = 20 }
            val query = Users.select(Users.name)

            execQuery(query) { rawResultSet ->
                val counters = CloseCounters()
                val stream = JdbcResultRowStream.fromResultSet(
                    transaction = this@transactionBlock,
                    query = query,
                    resultSet = countingResultSet(
                        delegate = rawResultSet,
                        counters = counters,
                        statementAccessible = statementAccessible,
                        nextFailure = nextFailure,
                        getObjectFailure = getObjectFailure,
                        resultSetCloseFailure = resultSetCloseFailure,
                        statementCloseFailure = statementCloseFailure,
                    ),
                    mapper = mapper,
                )
                block(stream, counters)
            }
        }
    }

    private fun countingResultSet(
        delegate: ResultSet,
        counters: CloseCounters,
        statementAccessible: Boolean,
        nextFailure: SQLException?,
        getObjectFailure: SQLException?,
        resultSetCloseFailure: Throwable?,
        statementCloseFailure: Throwable?,
    ): ResultSet {
        val statement = delegate.statement
        val statementProxy = countingStatement(statement, counters, statementCloseFailure)

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ResultSet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getStatement" -> statementFor(statementProxy, statementAccessible)
                "next" -> {
                    counters.next.incrementAndGet()
                    nextResult(delegate, nextFailure)
                }
                "getObject" -> resultObject(delegate, method, args, getObjectFailure)
                "close" -> {
                    closeResultSet(delegate, counters, resultSetCloseFailure)
                    null
                }
                else -> method.invoke(delegate, *(args ?: emptyArray()))
            }
        } as ResultSet
    }

    private fun statementFor(statement: Statement, accessible: Boolean): Statement {
        if (!accessible) throw SQLException("statement closed")
        return statement
    }

    private fun nextResult(delegate: ResultSet, failure: SQLException?): Boolean =
        failure?.let { throw it } ?: delegate.next()

    private fun resultObject(
        delegate: ResultSet,
        method: java.lang.reflect.Method,
        args: Array<out Any?>?,
        failure: SQLException?,
    ): Any? = failure?.let { throw it } ?: method.invoke(delegate, *(args ?: emptyArray()))

    private fun closeResultSet(
        delegate: ResultSet,
        counters: CloseCounters,
        failure: Throwable?,
    ) {
        if (counters.resultSetClose.incrementAndGet() == 1) {
            delegate.close()
            failure?.let { throw it }
        }
    }

    private fun countingStatement(
        delegate: Statement,
        counters: CloseCounters,
        closeFailure: Throwable?,
    ): Statement = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        when (method.name) {
            "close" -> {
                if (counters.statementClose.incrementAndGet() == 1) {
                    delegate.close()
                    closeFailure?.let { throw it }
                }
                null
            }
            else -> method.invoke(delegate, *(args ?: emptyArray()))
        }
    } as Statement

    private fun throwableGraph(failure: Throwable): String = buildString {
        var current: Throwable? = failure
        while (current != null) {
            append(current::class.java.name)
            append(':')
            appendLine(current.message)
            current.suppressed.forEach { suppressed -> appendLine(suppressed.message) }
            current = current.cause
        }
    }

    private class CloseCounters {
        val next = AtomicInteger()
        val resultSetClose = AtomicInteger()
        val statementClose = AtomicInteger()
    }
}
