package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
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
    fun `close still releases result set when statement lookup fails`() {
        withCountingStream(statementAccessible = false) { stream, counters ->
            stream.close()
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 0
        }
    }

    @Test
    fun `cursor advance failure uses a stable data access exception and closes resources`() {
        withCountingStream(nextFails = true) { stream, counters ->
            assertFailsWith<DataAccessResourceFailureException> { stream.findFirst() }
            counters.resultSetClose.get() shouldBeEqualTo 1
            counters.statementClose.get() shouldBeEqualTo 1
        }
    }

    private fun withCountingStream(
        mapper: (Int, org.jetbrains.exposed.v1.core.ResultRow) -> String = { _, row -> row[Users.name] },
        statementAccessible: Boolean = true,
        nextFails: Boolean = false,
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
                    resultSet = countingResultSet(rawResultSet, counters, statementAccessible, nextFails),
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
        nextFails: Boolean,
    ): ResultSet {
        val statement = delegate.statement
        val statementProxy = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, args ->
            when (method.name) {
                "close" -> {
                    if (counters.statementClose.incrementAndGet() == 1) statement.close()
                    null
                }
                else -> method.invoke(statement, *(args ?: emptyArray()))
            }
        } as Statement

        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ResultSet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getStatement" -> if (statementAccessible) statementProxy else throw SQLException("statement closed")
                "next" -> {
                    counters.next.incrementAndGet()
                    if (nextFails) throw SQLException("cursor invalidated") else delegate.next()
                }
                "close" -> {
                    if (counters.resultSetClose.incrementAndGet() == 1) delegate.close()
                    null
                }
                else -> method.invoke(delegate, *(args ?: emptyArray()))
            }
        } as ResultSet
    }

    private class CloseCounters {
        val next = AtomicInteger()
        val resultSetClose = AtomicInteger()
        val statementClose = AtomicInteger()
    }
}
