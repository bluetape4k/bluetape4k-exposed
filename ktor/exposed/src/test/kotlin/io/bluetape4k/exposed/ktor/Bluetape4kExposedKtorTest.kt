package io.bluetape4k.exposed.ktor

import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.ExpectedApiError
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveApiError
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Bluetape4kExposedKtorTest {

    @Test
    fun `configuration rejects equivalent health and readiness paths`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Bluetape4kExposedKtorConfig(
                healthPath = "/ops/status/",
                readinessPath = "/ops/status",
            )
        }

        error.message shouldContain "healthPath and readinessPath must be distinct"
    }

    @Test
    fun `default installer is a no-op`() = testApplication {
        application {
            installBluetape4kExposedKtor()
        }

        client.get("/healthz/exposed").shouldHaveStatus(HttpStatusCode.NotFound)
        client.get("/readyz/exposed").shouldHaveStatus(HttpStatusCode.NotFound)
    }

    @Test
    fun `cache-only installer exposes readiness without a database`() = testApplication {
        val invocations = AtomicInteger()
        val cacheReadiness = cacheReadiness {
            invocations.incrementAndGet()
            ExposedKtorCacheStatus.UP
        }
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
            )
            installBluetape4kExposedKtor(
                config = Bluetape4kExposedKtorConfig(installHealthRoutes = true),
                cacheReadiness = cacheReadiness,
            )
        }

        val readiness = bluetape4kJsonClient().get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>()

        readiness shouldBeEqualTo HealthResponse.up(mapOf("cache.ops" to HealthResponse.UP))
        invocations.get() shouldBeEqualTo 1
    }

    @Test
    fun `database-only installer rejects health routes without a database`() = testApplication {
        application {
            val error = assertFailsWith<IllegalArgumentException> {
                installBluetape4kExposedKtor(
                    Bluetape4kExposedKtorConfig(installHealthRoutes = true)
                )
            }

            error.message shouldContain "At least one of jdbcDatabase or r2dbcDatabase"
        }
    }

    @Test
    fun `caller authentication protects direct cache readiness while installer routes stay disabled`() = testApplication {
        val invocations = AtomicInteger()
        val cacheReadiness = cacheReadiness {
            invocations.incrementAndGet()
            ExposedKtorCacheStatus.UP
        }
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
            )
            install(Authentication) {
                basic("ops") {
                    realm = "ops"
                    validate { credentials ->
                        credentials.takeIf { it.name == "operator" && it.password == "secret" }
                            ?.let { UserIdPrincipal(it.name) }
                    }
                }
            }
            installBluetape4kExposedKtor(
                config = Bluetape4kExposedKtorConfig(installHealthRoutes = false),
                cacheReadiness = cacheReadiness,
            )
            routing {
                authenticate("ops") {
                    bluetape4kExposedHealthRoutes(
                        jdbcDatabase = null,
                        jdbcBlockingDispatcher = null,
                        r2dbcDatabase = null,
                        cacheReadiness = cacheReadiness,
                    )
                }
            }
        }

        val denied = bluetape4kJsonClient().get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.Unauthorized)
        denied.bodyAsText() shouldNotContain "cache.ops"
        denied.bodyAsText() shouldNotContain HealthResponse.UP
        invocations.get() shouldBeEqualTo 0

        val authorization = Base64.getEncoder().encodeToString("operator:secret".toByteArray())
        val allowed = bluetape4kJsonClient().get("/readyz/exposed") {
            header(HttpHeaders.Authorization, "Basic $authorization")
        }.shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>()

        allowed shouldBeEqualTo HealthResponse.up(mapOf("cache.ops" to HealthResponse.UP))
        invocations.get() shouldBeEqualTo 1
    }

    @Test
    fun `cache installer KDoc pins security deadline unsupported probes and resource ownership`() {
        val source = exposedKtorInstallerSource()
        val declaration = "fun Application.installBluetape4kExposedKtor("
        val second = source.indexOf(declaration, source.indexOf(declaration) + declaration.length)
        val prefix = source.substring(0, second)
        val end = prefix.lastIndexOf("*/")
        val start = prefix.lastIndexOf("/**", end)
        val kdoc = prefix.substring(start, end + 2)
            .lineSequence()
            .map { it.trim().removePrefix("/**").removePrefix("*").removeSuffix("*/").trim() }
            .joinToString(" ")

        listOf(
            "cache-only",
            "installHealthRoutes",
            "shared monotonic",
            "authentication",
            "security",
            "caller owns",
            "creates or closes no",
            "blocking",
            "backend-I/O",
            "dispatchers",
            "repositories",
            "registries",
            "shutdown",
        ).forEach { phrase -> kdoc shouldContain phrase }
    }

    @Test
    fun `status pages installation fails fast when StatusPages is already installed`() = testApplication {
        application {
            val app = this
            app.install(StatusPages)

            val error = assertFailsWith<IllegalArgumentException> {
                app.installBluetape4kExposedKtor(
                    Bluetape4kExposedKtorConfig(installStatusPages = true)
                )
            }

            error.message shouldContain "StatusPages is already installed"
            error.message shouldContain "bluetape4kExposedErrors()"
        }
    }

    @Test
    fun `status pages redact database exception details`() = testApplication {
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(
                    installStatusPages = false,
                    installHealthRoutes = false,
                )
            )
            this.install(StatusPages) {
                bluetape4kExposedErrors()
            }
            routing {
                get("/db") {
                    throw SQLException(
                        "jdbc:h2:mem:secret; user=sa password=top-secret SELECT * FROM payments"
                    )
                }
            }
        }

        val response = client.get("/db")
        response.shouldHaveApiError(
            ExpectedApiError(
                status = HttpStatusCode.ServiceUnavailable,
                error = "EXPOSED_DATABASE_UNAVAILABLE",
                message = "Exposed database operation failed",
                path = "/db",
            )
        )

        val rawBody = response.bodyAsText()
        rawBody shouldNotContain "top-secret"
        rawBody shouldNotContain "SELECT"
        rawBody shouldNotContain "payments"
        rawBody shouldNotContain "jdbc:h2"
    }

    @Test
    fun `jdbc health and readiness routes expose allowlisted details and metrics`() = testApplication {
        val database = Database.connect(
            url = "jdbc:h2:mem:ktor-jdbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val meterRegistry = SimpleMeterRegistry()

        try {
            application {
                installBluetape4kKtorCore(
                    Bluetape4kKtorCoreConfig(
                        installStatusPages = false,
                        installHealthRoutes = false,
                    )
                )
                installBluetape4kExposedKtor(
                    Bluetape4kExposedKtorConfig(
                        jdbcDatabase = database,
                        jdbcBlockingDispatcher = dispatcher,
                        installHealthRoutes = true,
                        readinessProbeTimeout = 2.seconds,
                        meterRegistry = meterRegistry,
                    )
                )
            }

            val jsonClient = bluetape4kJsonClient()

            val health = jsonClient.get("/healthz/exposed")
                .shouldHaveStatus(HttpStatusCode.OK)
                .decodeJsonBody<HealthResponse>()
            health shouldBeEqualTo HealthResponse.up(mapOf("exposed" to HealthResponse.UP))

            val readiness = jsonClient.get("/readyz/exposed")
                .shouldHaveStatus(HttpStatusCode.OK)
                .decodeJsonBody<HealthResponse>()
            readiness shouldBeEqualTo HealthResponse.up(mapOf("jdbc" to HealthResponse.UP))

            meterRegistry.find("bluetape4k.exposed.ktor.readiness")
                .tag("backend", "jdbc")
                .tag("operation", "readiness")
                .tag("outcome", "success")
                .timer()
                ?.count() shouldBeEqualTo 1L
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `r2dbc readiness route exposes allowlisted details`() = testApplication {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1;")
            }
        )

        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(
                    installStatusPages = false,
                    installHealthRoutes = false,
                )
            )
            installBluetape4kExposedKtor(
                Bluetape4kExposedKtorConfig(
                    r2dbcDatabase = database,
                    installHealthRoutes = true,
                    readinessProbeTimeout = 2.seconds,
                )
            )
        }

        val readiness = bluetape4kJsonClient().get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>()

        readiness shouldBeEqualTo HealthResponse.up(mapOf("r2dbc" to HealthResponse.UP))
    }

    private fun cacheReadiness(
        probe: suspend () -> ExposedKtorCacheStatus,
    ): ExposedKtorCacheReadinessConfig = ExposedKtorCacheReadinessConfig(
        listOf(ExposedKtorCacheContributor.custom("ops", probe))
    )

    private fun exposedKtorInstallerSource(): String {
        val relative = "ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt"
        val paths = listOf(Path.of(relative), Path.of("src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt"))
        return Files.readString(paths.first(Files::exists))
    }
}
