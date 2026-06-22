package io.bluetape4k.exposed.trino

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * [TrinoDatabase.connect(DataSource)] 동작 검증 테스트.
 *
 * - mock DataSource 단위 테스트: autocommit 강제, close-on-failure, 예외 전파
 * - Testcontainers 통합 smoke: 실제 DataSource로 SELECT 1
 */
class TrinoDatabaseDataSourceTest: AbstractTrinoTest() {

    private val mockStatement = mockk<PreparedStatement>(relaxed = true)
    private val mockConn = mockk<Connection>(relaxed = true)
    private val mockDataSource = mockk<DataSource>()

    @BeforeEach
    fun setUp() {
        clearMocks(mockStatement, mockConn, mockDataSource)
        every { mockConn.prepareStatement(any<String>()) } returns mockStatement
        every { mockConn.autoCommit = true } just runs
        every { mockConn.autoCommit } returns true
    }

    // ----------------------------------------------------------------
    // autocommit 강제 검증
    // ----------------------------------------------------------------

    @Test
    fun `DataSource 연결 시 autoCommit 이 true 로 강제된다`() {
        every { mockDataSource.connection } returns mockConn

        val db = TrinoDatabase.connect(mockDataSource)
        // getNewConnection 람다는 transaction 시작 시 호출됨
        runCatching { transaction(db) { exec("SELECT 1") } }

        verify { mockConn.autoCommit = true }
    }

    // ----------------------------------------------------------------
    // close-on-failure: TrinoConnectionWrapper 생성 실패
    // ----------------------------------------------------------------

    @Test
    fun `TrinoConnectionWrapper 생성 실패 시 raw connection 이 닫힌다`() {
        every { mockDataSource.connection } returns mockConn
        every { mockConn.autoCommit = true } throws RuntimeException("autocommit forbidden")
        every { mockConn.close() } just runs

        val db = TrinoDatabase.connect(mockDataSource)
        assertFailsWith<Exception> {
            transaction(db) { exec("SELECT 1") }
        }

        verify { mockConn.close() }
    }

    // ----------------------------------------------------------------
    // DataSource.getConnection() 예외 전파
    // ----------------------------------------------------------------

    @Test
    fun `DataSource getConnection 실패 시 예외가 전파된다`() {
        every { mockDataSource.connection } throws SQLException("pool exhausted")

        val db = TrinoDatabase.connect(mockDataSource)
        assertFailsWith<Exception> {
            transaction(db) { exec("SELECT 1") }
        }
    }

    // ----------------------------------------------------------------
    // Testcontainers 통합 smoke
    // ----------------------------------------------------------------

    @Test
    fun `실제 DataSource 로 connect 후 SELECT 1 이 실행된다`() {
        val db = TrinoDatabase.connect(trinoDataSource())
        db.shouldNotBeNull()
        transaction(db) {
            val result = exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) }
            result.shouldNotBeNull()
            (result == 1).shouldBeTrue()
        }
    }

    @Test
    fun `DataSource 연결 후 autoCommit 이 항상 true 이다`() {
        val db = TrinoDatabase.connect(trinoDataSource())
        transaction(db) {
            connection.autoCommit.shouldBeTrue()
        }
    }

    // ----------------------------------------------------------------
    // 헬퍼
    // ----------------------------------------------------------------

    private fun trinoDataSource(): DataSource {
        val jdbcUrl = "jdbc:trino://${trino.host}:${trino.port}/memory/default"
        val user = trino.username ?: "trino"
        return SimpleDataSource(jdbcUrl, user)
    }

    private class SimpleDataSource(
        private val jdbcUrl: String,
        private val user: String,
    ): DataSource {
        override fun getConnection(): Connection {
            val props = Properties().apply { setProperty("user", user) }
            return DriverManager.getConnection(jdbcUrl, props)
        }

        override fun getConnection(username: String, password: String) = getConnection()
        override fun getLogWriter(): PrintWriter = PrintWriter(System.out)
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getAnonymousLogger()
        override fun <T> unwrap(iface: Class<T>): T = throw SQLException("Not a wrapper")
        override fun isWrapperFor(iface: Class<*>): Boolean = false
    }
}
