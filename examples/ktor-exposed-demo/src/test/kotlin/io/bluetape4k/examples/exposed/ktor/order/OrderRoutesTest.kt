package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OrderRoutesTest {

    private val id = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36")
    private val confirmedAt = Instant.parse("2026-07-17T00:01:00Z")

    @Test
    fun `missing or wrong header wins over invalid id with 403`() = testApplication {
        val fixture = installOrderRoutes()
        val client = bluetape4kJsonClient()

        val missing = client.post("/orders/not-a-uuid/confirm")
        val wrong = client.post("/orders/not-a-uuid/confirm") {
            header(DEMO_COMMAND_HEADER, "wrong-command")
        }

        listOf(missing, wrong).forEach { response ->
            response.shouldHaveStatus(HttpStatusCode.Forbidden)
                .decodeJsonBody<DemoErrorResponse>() shouldBeEqualTo DemoErrorResponse(
                code = "DEMO_COMMAND_REQUIRED",
                message = "Required demo command header is missing or invalid.",
            )
        }
        coVerify(exactly = 0) { fixture.service.confirm(any<UUID>()) }
        coVerify(exactly = 0) { fixture.repository.get(any()) }
        fixture.diagnostics.items shouldBeEqualTo emptyList()
    }

    @Test
    fun `hostile origin preflight receives no permissive CORS grant`() = testApplication {
        val fixture = installOrderRoutes()

        val response = bluetape4kJsonClient().options("/orders/$id/confirm") {
            header(HttpHeaders.Origin, "https://hostile.example")
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            header(HttpHeaders.AccessControlRequestHeaders, DEMO_COMMAND_HEADER)
        }

        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBeEqualTo null
        response.headers[HttpHeaders.AccessControlAllowCredentials] shouldBeEqualTo null
        (response.status.value in 200..299) shouldBeEqualTo false
        coVerify(exactly = 0) { fixture.service.confirm(any<UUID>()) }
        coVerify(exactly = 0) { fixture.repository.get(any()) }
        fixture.diagnostics.items shouldBeEqualTo emptyList()
    }

    @Test
    fun `valid header and invalid uppercase nil or oversized id return constant 400`() = testApplication {
        val fixture = installOrderRoutes()
        val invalidIds = listOf(
            "not-a-uuid",
            id.toString().uppercase(),
            "00000000-0000-0000-0000-000000000000",
            "${id}0",
        )
        val client = bluetape4kJsonClient()

        invalidIds.forEach { invalidId ->
            val response = client.post("/orders/$invalidId/confirm") {
                header(DEMO_COMMAND_HEADER, DEMO_COMMAND_VALUE)
            }.shouldHaveStatus(HttpStatusCode.BadRequest)
            response.decodeJsonBody<DemoErrorResponse>() shouldBeEqualTo DemoErrorResponse(
                code = "INVALID_ORDER_ID",
                message = "Order id must be a canonical non-nil UUID.",
            )
            response.bodyAsText().contains(invalidId) shouldBeEqualTo false
        }
        coVerify(exactly = 0) { fixture.service.confirm(any<UUID>()) }
        fixture.diagnostics.items shouldBeEqualTo emptyList()
    }

    @Test
    fun `confirmation returns serialized eventPublished result`() = testApplication {
        val fixture = installOrderRoutes()
        coEvery { fixture.service.confirm(id) } returns OrderConfirmationResult(confirmedRecord(), true)

        val response = confirm(id)
            .shouldHaveStatus(HttpStatusCode.OK)
        val body = response.bodyAsText()

        response.headers[HttpHeaders.ContentType]?.startsWith("application/json") shouldBeEqualTo true
        Json.parseToJsonElement(body).jsonObject.keys shouldBeEqualTo
            setOf("orderId", "status", "updatedAt", "eventPublished")
        Json.decodeFromString<OrderConfirmationResponse>(body) shouldBeEqualTo OrderConfirmationResponse(
            orderId = id.toString(),
            status = "CONFIRMED",
            updatedAt = confirmedAt.toString(),
            eventPublished = true,
        )
    }

    @Test
    fun `sequential confirmation returns eventPublished false`() = testApplication {
        val fixture = installOrderRoutes()
        coEvery { fixture.service.confirm(id) } returns OrderConfirmationResult(confirmedRecord(), false)

        confirm(id).shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<OrderConfirmationResponse>() shouldBeEqualTo OrderConfirmationResponse(
            orderId = id.toString(),
            status = "CONFIRMED",
            updatedAt = confirmedAt.toString(),
            eventPublished = false,
        )
    }

    @Test
    fun `get returns 404 for missing order and 200 for stored order`() = testApplication {
        val fixture = installOrderRoutes()
        coEvery { fixture.repository.get(id) } returnsMany listOf(null, confirmedRecord())
        val client = bluetape4kJsonClient()

        client.get("/orders/$id")
            .shouldHaveStatus(HttpStatusCode.NotFound)
            .decodeJsonBody<DemoErrorResponse>() shouldBeEqualTo DemoErrorResponse(
            code = "ORDER_NOT_FOUND",
            message = "Order was not found.",
        )
        client.get("/orders/$id")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<OrderResponse>() shouldBeEqualTo OrderResponse(
            orderId = id.toString(),
            status = "CONFIRMED",
            updatedAt = confirmedAt.toString(),
        )
    }

    @Test
    fun `typed command failures map to distinct sanitized 503 responses`() = testApplication {
        val fixture = installOrderRoutes()
        val cases = listOf(
            OrderPersistenceException(IllegalStateException("db")) to
                ("ORDER_PERSISTENCE_FAILED" to "Order could not be stored."),
            OrderEventHandoffException(IllegalStateException("event")) to
                ("ORDER_EVENT_HANDOFF_FAILED" to "Order was stored but its event was not handed off."),
            IllegalStateException("unknown") to
                ("ORDER_CONFIRMATION_FAILED" to "Order confirmation failed."),
        )

        cases.forEach { (failure, expected) ->
            coEvery { fixture.service.confirm(id) } throws failure
            val error = confirm(id)
                .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
                .decodeJsonBody<DemoErrorResponse>()
            error.code shouldBeEqualTo expected.first
            error.message shouldBeEqualTo expected.second
            UUID.fromString(requireNotNull(error.correlationId)).toString() shouldBeEqualTo error.correlationId
        }
        fixture.diagnostics.items.map { it.code } shouldBeEqualTo cases.map { it.second.first }
        fixture.diagnostics.items.map { it.operation } shouldBeEqualTo listOf("confirm", "confirm", "confirm")
    }

    @Test
    fun `get repository failure maps to ORDER_READ_FAILED and operation read`() = testApplication {
        val fixture = installOrderRoutes()
        coEvery { fixture.repository.get(id) } throws IllegalStateException("read failed")

        val error = bluetape4kJsonClient().get("/orders/$id")
            .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
            .decodeJsonBody<DemoErrorResponse>()

        error.code shouldBeEqualTo "ORDER_READ_FAILED"
        error.message shouldBeEqualTo "Order could not be loaded."
        UUID.fromString(requireNotNull(error.correlationId)).toString() shouldBeEqualTo error.correlationId
        fixture.diagnostics.items.single().operation shouldBeEqualTo "read"
    }

    @Test
    fun `secret bearing primary and suppressed messages never enter body or diagnostic`() = testApplication {
        val fixture = installOrderRoutes()
        val failure = IllegalStateException(
            "r2dbc:postgresql://demo:password@localhost/orders SELECT secret",
        ).apply {
            addSuppressed(IllegalArgumentException("user=demo password=top-secret"))
        }
        coEvery { fixture.service.confirm(id) } throws failure

        val body = confirm(id)
            .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
            .bodyAsText()
        val diagnostic = fixture.diagnostics.items.single().let {
            listOfNotNull(it.code, it.correlationId, it.component, it.operation, it.phase, it.outcome).joinToString(" ")
        }

        listOf("r2dbc", "postgresql", "demo", "password", "SELECT", "secret", "top-secret").forEach { token ->
            body.contains(token, ignoreCase = true) shouldBeEqualTo false
            diagnostic.contains(token, ignoreCase = true) shouldBeEqualTo false
        }
    }

    private fun ApplicationTestBuilder.installOrderRoutes(): Fixture {
        val fixture = Fixture()
        application {
            installBluetape4kKtorCore()
            routing {
                orderRoutes(fixture.service, fixture.repository, fixture.diagnostics)
            }
        }
        return fixture
    }

    private suspend fun ApplicationTestBuilder.confirm(orderId: UUID) =
        bluetape4kJsonClient().post("/orders/$orderId/confirm") {
            header(DEMO_COMMAND_HEADER, DEMO_COMMAND_VALUE)
        }

    private fun confirmedRecord() = OrderRecord(id, OrderStatus.CONFIRMED, confirmedAt)

    private inner class Fixture {
        val repository: R2dbcCaffeineRepository<UUID, OrderRecord> = mockk(relaxed = true)
        val service: OrderCommandService = mockk()
        val diagnostics = RecordingDiagnosticSink()
    }

    private class RecordingDiagnosticSink : DemoDiagnosticSink {
        val items = mutableListOf<DemoDiagnostic>()

        override fun emit(diagnostic: DemoDiagnostic) {
            items += diagnostic
        }
    }

    companion object {
        private const val DEMO_COMMAND_HEADER = "X-Demo-Command"
        private const val DEMO_COMMAND_VALUE = "confirm-order"
    }
}
