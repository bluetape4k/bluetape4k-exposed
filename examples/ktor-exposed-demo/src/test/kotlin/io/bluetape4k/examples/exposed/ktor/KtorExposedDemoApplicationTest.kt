package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.examples.exposed.ktor.order.DemoDiagnosticSink
import io.bluetape4k.examples.exposed.ktor.order.DemoErrorResponse
import io.bluetape4k.examples.exposed.ktor.order.OrderCommandService
import io.bluetape4k.examples.exposed.ktor.order.OrderRecord
import io.bluetape4k.examples.exposed.ktor.order.orderRoutes
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID
import io.bluetape4k.assertions.shouldBeEqualTo

class KtorExposedDemoApplicationTest {

    @Test
    fun `docker free composition installs core and order routes`() = testApplication {
        val service = mockk<OrderCommandService>()
        val repository = mockk<R2dbcCaffeineRepository<UUID, OrderRecord>>()
        application {
            installBluetape4kKtorCore()
            routing {
                orderRoutes(service, repository, DemoDiagnosticSink {})
            }
        }

        val response = bluetape4kJsonClient().post("/orders/not-a-uuid/confirm") {
            header("X-Demo-Command", "confirm-order")
        }

        response.shouldHaveStatus(HttpStatusCode.BadRequest)
            .decodeJsonBody<DemoErrorResponse>() shouldBeEqualTo DemoErrorResponse(
            "INVALID_ORDER_ID",
            "Order id must be a canonical non-nil UUID.",
        )
        coVerify(exactly = 0) { service.confirm(any<UUID>()) }
        coVerify(exactly = 0) { repository.get(any()) }
    }
}
