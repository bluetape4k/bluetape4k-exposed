package io.bluetape4k.exposed.starrocks

import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.sql.DriverManager

/**
 * Shared StarRocks container fixture for integration tests.
 */
@Execution(ExecutionMode.SAME_THREAD)
abstract class AbstractStarRocksTest {

    companion object: KLogging() {
        private val starRocks: StarRocksTestServer by lazy { StarRocksTestServer.Launcher.starRocks }

        val host: String
            get() = starRocks.host

        val port: Int
            get() = starRocks.port

        val databaseName: String
            get() = starRocks.databaseName

        val jdbcUrl: String
            get() = starRocks.jdbcUrl

        val bootstrapJdbcUrl: String
            get() = starRocks.bootstrapJdbcUrl

        val connectionProperties
            get() = starRocks.connectionProperties

        val db: Database by lazy {
            StarRocksDatabase.connect(
                host = host,
                port = port,
                catalog = StarRocksTestServer.CATALOG,
                database = databaseName,
                user = StarRocksTestServer.USER,
            )
        }

        @JvmStatic
        @BeforeAll
        fun startStarRocks() {
            Class.forName(StarRocksDatabase.DRIVER)
            starRocks.verifyHostPortMapping()
            waitForStarRocksReady()
            createDatabase()
            waitForClusterCapacity()
        }

        private fun waitForStarRocksReady() {
            repeat(60) { attempt ->
                runCatching {
                    DriverManager.getConnection(bootstrapJdbcUrl, connectionProperties).use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT 1").use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    }
                }.onSuccess {
                    return
                }.onFailure { e ->
                    if (attempt == 59) {
                        throw e
                    }
                    log.warn("StarRocks not ready (attempt {}/60), waiting 1s...", attempt + 1)
                    Thread.sleep(1000L)
                }
            }
        }

        private fun createDatabase() {
            DriverManager.getConnection(bootstrapJdbcUrl, connectionProperties).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE DATABASE IF NOT EXISTS `$databaseName`")
                }
            }
        }

        private fun waitForClusterCapacity() {
            repeat(120) { attempt ->
                runCatching {
                    DriverManager.getConnection(jdbcUrl, connectionProperties).use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.execute("DROP TABLE IF EXISTS __bt4k_capacity_probe")
                            stmt.execute(
                                """
                                CREATE TABLE __bt4k_capacity_probe (
                                    id BIGINT NOT NULL
                                )
                                ENGINE=OLAP
                                DUPLICATE KEY(id)
                                DISTRIBUTED BY HASH(id) BUCKETS 1
                                PROPERTIES ("replication_num" = "1")
                                """.trimIndent()
                            )
                            stmt.execute("DROP TABLE IF EXISTS __bt4k_capacity_probe")
                        }
                    }
                }.onSuccess {
                    return
                }.onFailure { e ->
                    if (attempt == 119) {
                        throw e
                    }
                    log.warn("StarRocks capacity not ready (attempt {}/120), waiting 1s...", attempt + 1)
                    Thread.sleep(1000L)
                }
            }
        }

        fun resetEventsTable() {
            DriverManager.getConnection(jdbcUrl, connectionProperties).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TABLE IF EXISTS events")
                    stmt.execute(
                        """
                        CREATE TABLE events (
                            event_id BIGINT NOT NULL,
                            event_name VARCHAR(100) NOT NULL,
                            region VARCHAR(32) NOT NULL
                        )
                        ENGINE=OLAP
                        DUPLICATE KEY(event_id)
                        DISTRIBUTED BY HASH(event_id) BUCKETS 1
                        PROPERTIES ("replication_num" = "1")
                        """.trimIndent()
                    )
                }
            }
        }
    }

    @BeforeEach
    fun resetFixture() {
        resetEventsTable()
    }
}
