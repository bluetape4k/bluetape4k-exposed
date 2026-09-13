package io.bluetape4k.exposed.clickhouse.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

class TrackingClickHouseConnectionTest {

    companion object: KLogging()

    @Test
    fun `관측 fixture는 실제 close를 한 번 위임하고 원래 JDBC 예외를 전파한다`() {
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<PreparedStatement>(relaxed = true)
        val result = mockk<ResultSet>(relaxed = true)
        val failure = SQLException("original next failure")

        every { connection.prepareStatement("SELECT 1") } returns statement
        every { statement.executeQuery() } returns result
        every { result.next() } throws failure

        val observed = JdbcObservation()
        val tracked = TrackingClickHouseConnection(connection, observed)
        val prepared = tracked.prepareStatement("SELECT 1", 1)
        val cursor = prepared.executeQuery()

        observed.results.get() shouldBeEqualTo 1
        assertFailsWith<SQLException> { cursor.next() }.shouldBeSameInstanceAs(failure)
        repeat(2) {
            cursor.close()
            prepared.close()
            tracked.close()
        }
        verify(exactly = 1) { result.close() }
        verify(exactly = 1) { statement.close() }
        verify(exactly = 1) { connection.close() }

        observed.results.get() shouldBeEqualTo 0
        observed.statements.get() shouldBeEqualTo 0
        observed.connections.get() shouldBeEqualTo 0
    }
}
