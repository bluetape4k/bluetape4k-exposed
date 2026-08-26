package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.examples.exposed.ktor.order.DemoDiagnosticSink
import io.bluetape4k.examples.exposed.ktor.order.OrderConfirmationResponse
import io.bluetape4k.examples.exposed.ktor.order.OrderResponse
import io.bluetape4k.examples.exposed.ktor.order.OrderStatus
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorExposedDemoPostgresIntegrationTest {

    private val postgres = PostgreSQLContainer("postgres:16-alpine")

    @BeforeAll
    fun startPostgreSql() {
        postgres.start()
    }

    @AfterAll
    fun stopPostgreSql() {
        postgres.stop()
    }

    @Test
    fun `order confirmation persists publishes reads through cache and stays sequentially idempotent`() {
        val resources = KtorExposedDemoResources.create(postgres.config())
        val id = Uuid.V7.nextId()
        try {
            testApplication {
                application {
                    installKtorExposedDemo(resources, DemoDiagnosticSink {})
                }
                val client = bluetape4kJsonClient()

                client.get("/transactions/jdbc-count")
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .bodyAsText() shouldBeEqualTo "2"
                client.get("/transactions/r2dbc-count")
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .bodyAsText() shouldBeEqualTo "0"

                val first = client.confirm(id)
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .decodeJsonBody<OrderConfirmationResponse>()
                first.orderId shouldBeEqualTo id.toString()
                first.status shouldBeEqualTo OrderStatus.CONFIRMED.name
                first.eventPublished shouldBeEqualTo true

                val stored = suspendTransaction(resources.r2dbcDatabase) {
                    io.bluetape4k.examples.exposed.ktor.order.DemoOrders.selectAll().single()
                }
                stored[io.bluetape4k.examples.exposed.ktor.order.DemoOrders.id].value shouldBeEqualTo id

                resources.orderRepository.invalidate(id)
                resources.orderRepository.cache.synchronous().getIfPresent(id.toString()) shouldBeEqualTo null
                val read = client.get("/orders/$id")
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .decodeJsonBody<OrderResponse>()
                read.orderId shouldBeEqualTo id.toString()
                val cached = resources.orderRepository.cache.synchronous().getIfPresent(id.toString())
                cached?.id shouldBeEqualTo id
                resources.orderRepository.get(id) shouldBeSameInstanceAs cached

                val repeated = client.confirm(id)
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .decodeJsonBody<OrderConfirmationResponse>()
                repeated.eventPublished shouldBeEqualTo false
                resources.orderRepository.countFromDb() shouldBeEqualTo 1L
                resources.eventPublisher.latestEvents.size shouldBeEqualTo 1
            }
        } finally {
            resources.closeReport()
        }
    }

    @Test
    fun `readiness exposes jdbc r2dbc and cache orders while health remains probe free`() {
        val resources = KtorExposedDemoResources.create(postgres.config())
        try {
            testApplication {
                application {
                    installKtorExposedDemo(resources, DemoDiagnosticSink {})
                }
                val client = bluetape4kJsonClient()

                client.get("/healthz/exposed")
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .decodeJsonBody<HealthResponse>() shouldBeEqualTo
                    HealthResponse.up(mapOf("exposed" to HealthResponse.UP))
                client.get("/readyz/exposed")
                    .shouldHaveStatus(HttpStatusCode.OK)
                    .decodeJsonBody<HealthResponse>() shouldBeEqualTo HealthResponse.up(
                    mapOf(
                        "jdbc" to HealthResponse.UP,
                        "r2dbc" to HealthResponse.UP,
                        "cache.orders" to HealthResponse.UP,
                    ),
                )
            }
        } finally {
            resources.closeReport()
        }
    }

    @Test
    fun `stopped PostgreSQL keeps liveness up and returns bounded sanitized readiness down`() {
        val outagePostgres = PostgreSQLContainer("postgres:16-alpine")
        outagePostgres.start()
        val outageConfig = outagePostgres.config()
        val secrets = listOf(
            outageConfig.user,
            outageConfig.password,
            outageConfig.r2dbcUrl,
            "SELECT",
            "Exception",
        )
        val resources = KtorExposedDemoResources.create(outageConfig)
        try {
            testApplication {
                application {
                    installKtorExposedDemo(resources, DemoDiagnosticSink {})
                }
                val client = bluetape4kJsonClient()
                outagePostgres.stop()

                client.get("/healthz/exposed").shouldHaveStatus(HttpStatusCode.OK)
                val readinessBody = withTimeout(10.seconds) {
                    client.get("/readyz/exposed")
                        .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
                        .bodyAsText()
                }
                secrets.forEach { secret ->
                    readinessBody.contains(secret, ignoreCase = true) shouldBeEqualTo false
                }
            }
        } finally {
            resources.closeReport()
            if (outagePostgres.isRunning) outagePostgres.stop()
        }
    }

    @Test
    fun `closing restores previous default and a second lifecycle does not reuse the closed pool`() =
        runSuspendIO(timeout = 30.seconds) {
            val previousDefault = TransactionManager.defaultDatabase

            val first = KtorExposedDemoResources.create(postgres.config())
            val existingRows = first.orderRepository.countFromDb()
            first.closeReport().isClean shouldBeEqualTo true
            TransactionManager.defaultDatabase shouldBeEqualTo previousDefault

            val second = KtorExposedDemoResources.create(postgres.config())
            try {
                second.orderRepository.countFromDb() shouldBeEqualTo existingRows
            } finally {
                second.closeReport().isClean shouldBeEqualTo true
            }
            TransactionManager.defaultDatabase shouldBeEqualTo previousDefault
        }

    private fun PostgreSQLContainer.config() = KtorExposedDemoConfig(
        r2dbcUrl = "r2dbc:postgresql://$host:${getMappedPort(5432)}/$databaseName",
        user = username,
        password = password,
    )

    private suspend fun io.ktor.client.HttpClient.confirm(id: UUID) = post("/orders/$id/confirm") {
        header("X-Demo-Command", "confirm-order")
    }
}
