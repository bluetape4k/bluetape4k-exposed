package io.bluetape4k.exposed.starrocks

import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.ShutdownQueue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties

/**
 * Testcontainers-backed StarRocks server fixture for integration tests.
 */
class StarRocksTestServer private constructor(
    private val container: GenericContainer<*>,
) {

    companion object {
        const val QUERY_PORT = 9030
        const val HTTP_PORT = 8030
        const val BE_HTTP_PORT = 8040
        const val CATALOG = "default_catalog"
        const val USER = "root"
        internal const val REUSE_ENV = "BLUETAPE4K_TESTCONTAINERS_REUSE"

        internal fun shouldReuseContainer(environment: Map<String, String> = System.getenv()): Boolean =
            !environment["CI"].toBoolean() && environment[REUSE_ENV].toBoolean()

        fun create(environment: Map<String, String> = System.getenv()): StarRocksTestServer =
            StarRocksTestServer(
                GenericContainer(DockerImageName.parse("starrocks/allin1-ubuntu"))
                    .withExposedPorts(QUERY_PORT, HTTP_PORT, BE_HTTP_PORT)
                    .withReuse(shouldReuseContainer(environment))
                    .waitingFor(
                        Wait.forListeningPort()
                            .withStartupTimeout(Duration.ofMinutes(4))
                    )
            )
    }

    val databaseName: String = "bt4k_starrocks_${System.currentTimeMillis().toString(36)}"

    internal val reusable: Boolean
        get() = container.isShouldBeReused

    val host: String
        get() = container.host

    val port: Int
        get() = container.getMappedPort(QUERY_PORT)

    val httpPort: Int
        get() = container.getMappedPort(HTTP_PORT)

    val beHttpPort: Int
        get() = container.getMappedPort(BE_HTTP_PORT)

    val jdbcUrl: String
        get() = StarRocksDatabase.buildJdbcUrl(host, port, CATALOG, databaseName)

    val bootstrapJdbcUrl: String
        get() = StarRocksDatabase.buildBootstrapJdbcUrl(host, port)

    val connectionProperties: Properties
        get() = Properties().apply {
            setProperty("user", USER)
            setProperty("password", "")
        }

    fun start() {
        if (!container.isRunning) {
            container.start()
            ShutdownQueue.register { container.stop() }
        }
    }

    fun verifyHostPortMapping() {
        check(host.isNotBlank()) { "StarRocks host must not be blank." }
        check(port > 0) { "StarRocks query port must be mapped." }
        check(httpPort > 0) { "StarRocks HTTP port must be mapped." }
        check(beHttpPort > 0) { "StarRocks BE HTTP port must be mapped." }
    }

    object Launcher: KLogging() {
        val starRocks: StarRocksTestServer by lazy {
            StarRocksTestServer.create()
                .also {
                    it.start()
                    it.verifyHostPortMapping()
                    log.info("StarRocks Testcontainer started at {}:{}", it.host, it.port)
                }
        }
    }
}
