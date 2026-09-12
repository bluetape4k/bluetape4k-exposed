package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 실제 ClickHouse JDBC V2 profile에서 WriterStatementImpl과 일반 JDBC 경계를 확인합니다.
 *
 * Docker/ClickHouse가 필요한 테스트이므로 기본 selector에서는 실행하지 않고
 * `-DclickhouseV2Integration=true`를 명시한 검증에서만 활성화합니다.
 */
@EnabledIfSystemProperty(named = "clickhouseV2Integration", matches = "true")
class ClickHouseRowBinaryIntegrationTest: AbstractClickHouseTest() {

    @RepeatedTest(3)
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
                    ClickHouseRowBinaryRow { statement -> statement.setInt(1, 3); statement.setString(2, "three") },
                ),
            )

            rowBinaryResult.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
            rowBinaryResult.updateCounts shouldBeEqualTo listOf(1, 1, 1)
            rowBinaryResult.acceptedCount shouldBeEqualTo 3

            val defaultResult = executor.executeBatch(
                "INSERT INTO $table (id, label) VALUES (?, DEFAULT)",
                listOf(ClickHouseRowBinaryRow { statement -> statement.setInt(1, 4) }),
            )
            defaultResult.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
            defaultResult.updateCounts shouldBeEqualTo listOf(1)
            defaultResult.acceptedCount shouldBeEqualTo 1

            val fallbackResult = executor.executeBatch(
                "INSERT INTO $table (id) SELECT ?",
                listOf(ClickHouseRowBinaryRow { statement -> statement.setInt(1, 5) }),
            )
            fallbackResult.path shouldBeEqualTo ClickHouseBatchPath.JDBC_FALLBACK
            fallbackResult.updateCounts shouldBeEqualTo listOf(1)

            provider.statementClasses.any { it.contains("WriterStatementImpl") } shouldBeEqualTo true
            provider.statementClasses.any { it.contains("PreparedStatementImpl") } shouldBeEqualTo true
            provider.executeBatchSizes shouldBeEqualTo listOf(2, 1, 1, 1)
            provider.statementCloseCount shouldBeEqualTo 3
            provider.connectionCloseCount shouldBeEqualTo provider.openedProfiles.size
            queryCount(provider, table) shouldBeEqualTo 5L
            queryDefaultLabelCount(provider, table) shouldBeEqualTo 2L
        } finally {
            provider.open(rowBinaryEnabled = false).use { connection ->
                connection.createStatement().use { statement -> statement.execute("DROP TABLE IF EXISTS $table") }
            }
        }
    }

    @RepeatedTest(3)
    fun `nullable setter preserves null regardless of writer capability`() {
        val table = "rowbinary_issue_874_nullable"
        val provider = RecordingProvider()
        provider.open(rowBinaryEnabled = false).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS $table")
                statement.execute(
                    "CREATE TABLE $table (id Int32, note Nullable(String)) ENGINE = Memory",
                )
            }
        }

        try {
            val result = ClickHouseRowBinaryExecutor(
                provider = provider,
                options = ClickHouseRowBinaryOptions(enabled = true),
            ).executeBatch(
                "INSERT INTO $table (id, note) VALUES (?, ?)",
                listOf(
                    ClickHouseRowBinaryRow { statement ->
                        statement.setInt(1, 1)
                        statement.setNull(2, Types.VARCHAR)
                    },
                ),
            )

            result.updateCounts shouldBeEqualTo listOf(1)
            result.acceptedCount shouldBeEqualTo 1
            queryCount(provider, table) shouldBeEqualTo 1L
            queryNullCount(provider, table) shouldBeEqualTo 1L
            result.path shouldBeEqualTo ClickHouseBatchPath.ROW_BINARY
            provider.fallbackEvents shouldBeEqualTo emptyList()
            provider.connectionCloseCount shouldBeEqualTo provider.openedProfiles.size
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

    private fun queryNullCount(provider: RecordingProvider, table: String): Long =
        provider.open(rowBinaryEnabled = false).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT countIf(note IS NULL) FROM $table").use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
        }

    private inner class RecordingProvider: ClickHouseConnectionProvider, ClickHouseRowBinaryFallbackRecorder {
        val openedProfiles = mutableListOf<Boolean>()
        val statementClasses = mutableListOf<String>()
        val fallbackEvents = mutableListOf<ClickHouseRowBinaryFallbackEvent>()
        val executeBatchSizes = mutableListOf<Int>()
        var statementCloseCount = 0
        var connectionCloseCount = 0

        override fun record(event: ClickHouseRowBinaryFallbackEvent) {
            fallbackEvents += event
        }

        override fun open(rowBinaryEnabled: Boolean): Connection {
            openedProfiles += rowBinaryEnabled
            val properties = Properties().apply {
                setProperty("user", clickhouse.username ?: "test")
                setProperty("password", clickhouse.password ?: "test")
                setProperty("beta.row_binary_for_simple_insert", rowBinaryEnabled.toString())
            }
            val raw = DriverManager.getConnection(clickhouse.jdbcUrl, properties)
            return recordingConnection(raw)
        }

        private fun recordingConnection(delegate: Connection): Connection {
            val closed = AtomicBoolean(false)
            lateinit var connection: Connection
            connection = Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "prepareStatement" -> {
                        val statement = invokeJdbc(delegate, method, args) as PreparedStatement
                        statementClasses += statement.javaClass.name
                        recordingPreparedStatement(statement)
                    }
                    "close" -> {
                        if (closed.compareAndSet(false, true)) connectionCloseCount++
                        invokeJdbc(delegate, method, args)
                    }
                    else -> invokeJdbc(delegate, method, args)
                }
            } as Connection
            return connection
        }

        private fun recordingPreparedStatement(delegate: PreparedStatement): PreparedStatement {
            val closed = AtomicBoolean(false)
            var pendingRows = 0
            return Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "addBatch" -> {
                        val result = invokeJdbc(delegate, method, args)
                        pendingRows++
                        result
                    }
                    "executeBatch" -> {
                        executeBatchSizes += pendingRows
                        pendingRows = 0
                        invokeJdbc(delegate, method, args)
                    }
                    "clearBatch" -> {
                        pendingRows = 0
                        invokeJdbc(delegate, method, args)
                    }
                    "close" -> {
                        if (closed.compareAndSet(false, true)) statementCloseCount++
                        invokeJdbc(delegate, method, args)
                    }
                    else -> invokeJdbc(delegate, method, args)
                }
            } as PreparedStatement
        }
    }
}

private fun invokeJdbc(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (caught: InvocationTargetException) {
    throw caught.targetException
}
