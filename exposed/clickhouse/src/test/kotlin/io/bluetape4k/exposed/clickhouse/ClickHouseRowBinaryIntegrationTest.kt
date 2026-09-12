package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties

/**
 * 실제 ClickHouse JDBC V2 profile에서 WriterStatementImpl과 일반 JDBC 경계를 확인합니다.
 *
 * Docker/ClickHouse가 필요한 테스트이므로 기본 selector에서는 실행하지 않고
 * `-DclickhouseV2Integration=true`를 명시한 검증에서만 활성화합니다.
 */
@EnabledIfSystemProperty(named = "clickhouseV2Integration", matches = "true")
class ClickHouseRowBinaryIntegrationTest: AbstractClickHouseTest() {

    @Test
    fun `enabled profile uses writer and unsupported SQL uses disabled profile`() {
        val table = "rowbinary_issue_867"
        val provider = RecordingProvider()
        val ddlConnection = provider.open(rowBinaryEnabled = false)
        ddlConnection.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS $table")
            statement.execute(
                "CREATE TABLE $table (id Int32, label String DEFAULT 'default-label') ENGINE = Memory",
            )
        }
        ddlConnection.close()

        try {
            val executor = ClickHouseRowBinaryExecutor(
                provider = provider,
                options = ClickHouseRowBinaryOptions(enabled = true, maxRowsPerFlush = 2),
            )
            val rowBinaryResult = executor.executeBatch(
                "INSERT INTO $table (id, label) VALUES (?, ?)",
                listOf(
                    ClickHouseRowBinaryRow { statement -> statement.setInt(1, 1); statement.setString(2, "one") },
                    ClickHouseRowBinaryRow { statement -> statement.setInt(1, 2); statement.setString(2, "two") },
                ),
            )

            rowBinaryResult.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
            rowBinaryResult.updateCounts shouldBeEqualTo listOf(1, 1)
            rowBinaryResult.acceptedCount shouldBeEqualTo 2

            val fallbackResult = executor.executeBatch(
                "INSERT INTO $table (id) SELECT ?",
                listOf(ClickHouseRowBinaryRow { statement -> statement.setInt(1, 3) }),
            )
            fallbackResult.path shouldBeEqualTo ClickHouseBatchPath.JDBC_FALLBACK
            fallbackResult.updateCounts shouldBeEqualTo listOf(1)

            provider.statementClasses.any { it.contains("WriterStatementImpl") } shouldBeEqualTo true
            provider.statementClasses.any { it.contains("PreparedStatementImpl") } shouldBeEqualTo true
            queryCount(provider, table) shouldBeEqualTo 3L
            queryDefaultLabelCount(provider, table) shouldBeEqualTo 1L
        } finally {
            provider.open(rowBinaryEnabled = false).use { connection ->
                connection.createStatement().use { statement -> statement.execute("DROP TABLE IF EXISTS $table") }
            }
        }
    }

    private fun queryCount(provider: RecordingProvider, table: String): Long =
        provider.open(rowBinaryEnabled = false).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count() FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }

    private fun queryDefaultLabelCount(provider: RecordingProvider, table: String): Long =
        provider.open(rowBinaryEnabled = false).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT countIf(label = 'default-label') FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }

    private inner class RecordingProvider: ClickHouseConnectionProvider {
        val statementClasses = mutableListOf<String>()

        override fun open(rowBinaryEnabled: Boolean): Connection {
            val properties = Properties().apply {
                setProperty("user", clickhouse.username ?: "test")
                setProperty("password", clickhouse.password ?: "test")
                setProperty("beta.row_binary_for_simple_insert", rowBinaryEnabled.toString())
            }
            val raw = DriverManager.getConnection(clickhouse.jdbcUrl, properties)
            return recordingConnection(raw, statementClasses)
        }
    }

    private fun recordingConnection(delegate: Connection, statementClasses: MutableList<String>): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> {
                    val statement = invokeJdbc(delegate, method, args) as PreparedStatement
                    statementClasses += statement.javaClass.name
                    statement
                }
                else -> invokeJdbc(delegate, method, args)
            }
        } as Connection
}

private fun invokeJdbc(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (caught: InvocationTargetException) {
    throw caught.targetException
}
