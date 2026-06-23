package io.bluetape4k.exposed.ktor

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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
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
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Bluetape4kExposedKtorTest {

    @Test
    fun `default installer is a no-op`() = testApplication {
        application {
            installBluetape4kExposedKtor()
        }

        client.get("/healthz/exposed").shouldHaveStatus(HttpStatusCode.NotFound)
        client.get("/readyz/exposed").shouldHaveStatus(HttpStatusCode.NotFound)
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
            url = "jdbc:h2:mem:ktor-jdbc-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
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
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;")
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
}
