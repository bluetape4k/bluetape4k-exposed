package io.bluetape4k.exposed.ktor

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.testcontainers.database.CockroachServer
import io.bluetape4k.testcontainers.database.JdbcServer
import io.bluetape4k.testcontainers.database.MariaDBServer
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.Testcontainers
import org.testcontainers.containers.Network
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * 실제 비-H2 JDBC/R2DBC 드라이버의 statement timeout과 Toxiproxy 네트워크 장애를 분리해 검증합니다.
 *
 * 이 테스트는 `test` 기본 task에서 제외하고 `driverTimeoutTest`에서만 실행합니다.
 * 직접 statement timeout은 드라이버의 `Statement` 계약을 관찰하고, Toxiproxy는
 * transport 장애와 proxy 정리·재생성을 별도로 검증합니다.
 */
@Tag("driver-timeout")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedKtorDriverTimeoutTest {

    private lateinit var network: Network
    private lateinit var postgres: PostgreSQLServer
    private lateinit var mysql: MySQL8Server
    private lateinit var mariadb: MariaDBServer
    private lateinit var cockroach: CockroachServer

    @BeforeAll
    fun startDatabases() {
        network = Network.newNetwork()
        try {
            postgres = PostgreSQLServer().apply {
                withNetwork(network)
                withNetworkAliases("postgresql")
                start()
            }
            mysql = MySQL8Server().apply {
                withNetwork(network)
                withNetworkAliases("mysql")
                start()
            }
            mariadb = MariaDBServer().apply {
                withNetwork(network)
                withNetworkAliases("mariadb")
                start()
            }
            cockroach = CockroachServer().apply { start() }
        } catch (failure: Throwable) {
            stopDatabases()
            throw failure
        }
    }

    @AfterAll
    fun stopDatabases() {
        if (::mariadb.isInitialized) runCatching { mariadb.stop() }
        if (::cockroach.isInitialized) runCatching { cockroach.stop() }
        if (::mysql.isInitialized) runCatching { mysql.stop() }
        if (::postgres.isInitialized) runCatching { postgres.stop() }
        if (::network.isInitialized) runCatching { network.close() }
    }

    @Test
    fun `JDBC 드라이버는 기본 timeout과 receiver override를 적용하고 연결을 복구한다`() {
        jdbcCases().forEach { case ->
            runJdbcProbe(case)
        }
    }

    @Test
    fun `R2DBC 드라이버는 timeout 지원 여부를 기록하고 실패 후 연결을 재사용한다`() {
        r2dbcCases().forEach { case ->
            runR2dbcProbe(case)
        }
    }

    @Test
    fun `R2DBC coroutine cancellation은 slow statement를 정리하고 session을 재사용한다`() = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl(r2dbcCases().first().r2dbcUrl())
                defaultQueryTimeout = 30
            },
        )
        val statementStarted = CompletableDeferred<Unit>()
        val requestCompleted = CompletableDeferred<Unit>()

        application {
            routing {
                get("/cancel") {
                    statementStarted.complete(Unit)
                    try {
                        call.exposedR2dbcTransaction(database) {
                            exec("SELECT pg_sleep(5)")
                        }
                    } finally {
                        requestCompleted.complete(Unit)
                    }
                }
                get("/ping") {
                    call.exposedR2dbcTransaction(database) {
                        exec("SELECT 1")
                    }
                    call.respondText("COMPLETED")
                }
            }
        }

        coroutineScope {
            val request = async { client.get("/cancel") }
            withTimeout(5.seconds) { statementStarted.await() }
            request.cancelAndJoin()
            request.isCancelled.shouldBeTrue()
            // 드라이버별 query 정리와 request finally 실행은 statement timeout과 별도 예산이 필요합니다.
            withTimeout(10.seconds) { requestCompleted.await() }
        }

        withTimeout(5.seconds) { client.get("/ping").bodyAsText() } shouldBeEqualTo "COMPLETED"
    }

    @Test
    fun `ToxiproxyServer가 TCP proxy 중단 후 새 proxy로 복구한다`() {
        val echoServer = ServerSocket(0)
        val echoExecutor = Executors.newSingleThreadExecutor()
        echoExecutor.submit {
            while (!echoServer.isClosed) {
                runCatching {
                    echoServer.accept().use { socket ->
                        val request = socket.getInputStream().bufferedReader().readLine()
                        if (request == "PING") {
                            socket.getOutputStream().bufferedWriter().use { writer ->
                                writer.appendLine("PONG")
                            }
                        }
                    }
                }.onFailure { failure ->
                    if (!echoServer.isClosed) throw failure
                }
            }
        }

        Testcontainers.exposeHostPorts(echoServer.localPort)
        var toxiproxy = ToxiproxyServer().apply {
            withAccessToHost(true)
            start()
        }
        var proxy: Proxy? = null

        try {
            var toxiproxyClient = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
            proxy = toxiproxyClient.createProxy(
                "tcp-echo",
                "0.0.0.0:$PROXY_PORT",
                "host.testcontainers.internal:${echoServer.localPort}",
            )
            var proxyPort = toxiproxy.getMappedPort(PROXY_PORT)

            tcpExchange(toxiproxy.host, proxyPort) shouldBeEqualTo "PONG"
            val activeProxy = checkNotNull(proxy)
            activeProxy.disable()
            runCatching { tcpExchange(toxiproxy.host, proxyPort) }.isFailure shouldBeEqualTo true

            activeProxy.delete()
            proxy = null
            toxiproxy.stop()
            toxiproxy = ToxiproxyServer().apply {
                withAccessToHost(true)
                start()
            }
            toxiproxyClient = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
            proxy = toxiproxyClient.createProxy(
                "tcp-echo-recovered",
                "0.0.0.0:$PROXY_PORT",
                "host.testcontainers.internal:${echoServer.localPort}",
            )
            proxyPort = toxiproxy.getMappedPort(PROXY_PORT)

            tcpExchange(toxiproxy.host, proxyPort) shouldBeEqualTo "PONG"
        } finally {
            proxy?.let { runCatching { it.delete() } }
            toxiproxy.stop()
            echoServer.close()
            echoExecutor.shutdownNow()
        }
    }

    @Test
    fun `ToxiproxyServer는 테스트가 직접 소유할 수 있다`() {
        val server = ToxiproxyServer()

        try {
            server.isRunning.shouldBeFalse()
        } finally {
            server.stop()
        }
    }

    private fun tcpExchange(host: String, port: Int): String = Socket(host, port).use { socket ->
        socket.soTimeout = 1_000
        val writer = socket.getOutputStream().bufferedWriter()
        writer.appendLine("PING")
        writer.flush()
        checkNotNull(socket.getInputStream().bufferedReader().readLine()) {
            "TCP echo response missing"
        }
    }

    private fun runJdbcProbe(case: DriverCase) = testApplication {
        val database = Database.connect(
            url = case.server.getJdbcUrl(),
            driver = case.server.getDriverClassName(),
            user = checkNotNull(case.server.getUsername()),
            password = checkNotNull(case.server.getPassword()),
            databaseConfig = DatabaseConfig { defaultQueryTimeout = DEFAULT_TIMEOUT_SECONDS },
        )
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            application {
                routing {
                    get("/slow") {
                        var inherited: Int? = null
                        val outcome = runCatching {
                            call.exposedJdbcTransaction(database, dispatcher) {
                                inherited = queryTimeout
                                call.request.queryParameters["override"]?.toIntOrNull()?.let { queryTimeout = it }
                                exec(case.slowSql)
                            }
                        }
                        call.respondText(
                            "case=${case.name};capability=${case.capability};" +
                                "inherited=$inherited;outcome=${outcome.label()};" +
                                "failure=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
                        )
                    }
                    get("/ping") {
                        val outcome = runCatching {
                            call.exposedJdbcTransaction(database, dispatcher) {
                                exec("SELECT 1")
                            }
                        }
                        call.respondText(if (outcome.isSuccess) "COMPLETED" else "FAILED")
                    }
                }
            }

            val default = withTimeout(8.seconds) { client.get("/slow") }.bodyAsText()
            default shouldContain "inherited=$DEFAULT_TIMEOUT_SECONDS"
            default shouldContain "capability=${case.capability}"
            default shouldContain "outcome=FAILED"

            val override = withTimeout(8.seconds) { client.get("/slow?override=$OVERRIDE_TIMEOUT_SECONDS") }
            override.bodyAsText() shouldContain "inherited=$DEFAULT_TIMEOUT_SECONDS"
            override.bodyAsText() shouldContain "capability=${case.capability}"
            override.bodyAsText() shouldContain "outcome=COMPLETED"

            withTimeout(8.seconds) { client.get("/ping") }.bodyAsText() shouldBeEqualTo "COMPLETED"
        } finally {
            dispatcher.close()
        }
    }

    private fun runR2dbcProbe(case: DriverCase) = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl(case.r2dbcUrl())
                defaultQueryTimeout = DEFAULT_TIMEOUT_SECONDS
            },
        )

        application {
            routing {
                get("/slow") {
                    var inherited: Int? = null
                    val outcome = runCatching {
                        call.exposedR2dbcTransaction(database) {
                            inherited = queryTimeout
                            call.request.queryParameters["override"]?.toIntOrNull()?.let { queryTimeout = it }
                            exec(case.slowSql)
                        }
                    }
                    call.respondText(
                        "case=${case.name};capability=${case.capability};" +
                            "inherited=$inherited;outcome=${outcome.label()};" +
                            "failure=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
                    )
                }
                get("/ping") {
                    val outcome = runCatching {
                        call.exposedR2dbcTransaction(database) {
                            exec("SELECT 1")
                        }
                    }
                    call.respondText(if (outcome.isSuccess) "COMPLETED" else "FAILED")
                }
            }
        }

        val override = withTimeout(8.seconds) { client.get("/slow?override=$OVERRIDE_TIMEOUT_SECONDS") }
        override.bodyAsText() shouldContain "inherited=$DEFAULT_TIMEOUT_SECONDS"
        override.bodyAsText() shouldContain "capability=${case.capability}"
        override.bodyAsText() shouldContain "outcome=${case.expectedOverrideOutcome}"

        val default = withTimeout(8.seconds) { client.get("/slow") }.bodyAsText()
        default shouldContain "inherited=$DEFAULT_TIMEOUT_SECONDS"
        default shouldContain "capability=${case.capability}"
        default shouldContain "outcome=${case.expectedDefaultOutcome}"

        withTimeout(8.seconds) { client.get("/ping") }.bodyAsText() shouldBeEqualTo "COMPLETED"
    }

    private fun jdbcCases() = listOf(
        DriverCase(
            name = "postgresql-jdbc",
            server = postgres,
            r2dbcScheme = "postgresql",
            slowSql = "SELECT pg_sleep(3)",
            capability = TimeoutCapability.SUPPORTED,
            expectedDefaultOutcome = Outcome.FAILED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
        DriverCase(
            name = "mysql8-jdbc",
            server = mysql,
            r2dbcScheme = "mysql",
            slowSql = "SELECT SLEEP(3)",
            capability = TimeoutCapability.SUPPORTED,
            expectedDefaultOutcome = Outcome.FAILED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
        DriverCase(
            name = "cockroach-jdbc",
            server = cockroach,
            r2dbcScheme = "postgresql",
            slowSql = "SELECT pg_sleep(3)",
            capability = TimeoutCapability.SUPPORTED,
            expectedDefaultOutcome = Outcome.FAILED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
    )

    private fun r2dbcCases() = listOf(
        DriverCase(
            name = "postgresql-r2dbc",
            server = postgres,
            r2dbcScheme = "postgresql",
            slowSql = "SELECT pg_sleep(3)",
            capability = TimeoutCapability.SUPPORTED,
            expectedDefaultOutcome = Outcome.FAILED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
        DriverCase(
            name = "mysql8-r2dbc",
            server = mysql,
            r2dbcScheme = "mysql",
            slowSql = "SELECT SLEEP(3)",
            capability = TimeoutCapability.UNSUPPORTED,
            expectedDefaultOutcome = Outcome.COMPLETED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
        DriverCase(
            name = "mariadb-r2dbc",
            server = mariadb,
            r2dbcScheme = "mariadb",
            slowSql = "SELECT SLEEP(3)",
            capability = TimeoutCapability.SUPPORTED,
            expectedDefaultOutcome = Outcome.FAILED,
            expectedOverrideOutcome = Outcome.COMPLETED,
        ),
    )

    private fun DriverCase.r2dbcUrl(): String {
        val databaseName = server.getDatabaseName() ?: error("database name is required for $name")
        return "r2dbc:$r2dbcScheme://${server.getUsername()}:${server.getPassword()}" +
            "@${server.host}:${server.port}/$databaseName"
    }

    private fun Result<*>.label(): Outcome = if (isSuccess) Outcome.COMPLETED else Outcome.FAILED

    private data class DriverCase(
        val name: String,
        val server: JdbcServer,
        val r2dbcScheme: String,
        val slowSql: String,
        val capability: TimeoutCapability,
        val expectedDefaultOutcome: Outcome,
        val expectedOverrideOutcome: Outcome,
    )

    private enum class Outcome {
        COMPLETED,
        FAILED,
    }

    private enum class TimeoutCapability {
        SUPPORTED,
        UNSUPPORTED,
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 1
        private const val OVERRIDE_TIMEOUT_SECONDS = 5
        private const val PROXY_PORT = 8666
    }
}
