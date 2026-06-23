package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class KtorExposedDemoApplicationTest {

    @Test
    fun `demo exposes health readiness and transaction route`() = testApplication {
        KtorExposedDemoResources.create("smoke").use { resources ->
            application {
                installKtorExposedDemo(resources)
            }

            val jsonClient = bluetape4kJsonClient()

            val health = jsonClient.get("/healthz/exposed")
                .shouldHaveStatus(HttpStatusCode.OK)
                .decodeJsonBody<HealthResponse>()
            health shouldBeEqualTo HealthResponse.up(mapOf("exposed" to HealthResponse.UP))

            val readiness = jsonClient.get("/readyz/exposed")
                .shouldHaveStatus(HttpStatusCode.OK)
                .decodeJsonBody<HealthResponse>()
            readiness shouldBeEqualTo HealthResponse.up(
                mapOf(
                    "jdbc" to HealthResponse.UP,
                    "r2dbc" to HealthResponse.UP,
                )
            )

            jsonClient.get("/transactions/jdbc-count")
                .shouldHaveStatus(HttpStatusCode.OK)
                .bodyAsText() shouldBeEqualTo "2"
        }
    }
}
