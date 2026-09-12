package io.bluetape4k.exposed.starrocks

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
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
            Thread.sleep(3000L)
            waitForClusterCapacity()
        }

        private const val MAX_ATTEMPTS = 60

        private fun waitForStarRocksReady() {
            repeat(MAX_ATTEMPTS) { attempt ->
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
                    if (attempt == MAX_ATTEMPTS - 1) {
                        throw e
                    }
                    log.warn(e) { "StarRocks not ready (attempt ${attempt + 1}/$MAX_ATTEMPTS), waiting 1s..." }
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

        private const val MAX_ATTEMPTS_CLUSTER = 120

        private fun waitForClusterCapacity() {
            repeat(MAX_ATTEMPTS_CLUSTER) { attempt ->
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
                    if (attempt == (MAX_ATTEMPTS_CLUSTER - 1)) {
                        throw e
                    }
                    log.warn(e) {
                        "StarRocks capacity not ready (attempt ${attempt + 1}/$MAX_ATTEMPTS_CLUSTER), waiting 1s..."
                    }
                    Thread.sleep(3000L)
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
