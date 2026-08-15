package io.bluetape4k.examples.exposed.webflux

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.examples.exposed.webflux.config.DataInitializer
import io.bluetape4k.examples.exposed.webflux.domain.ProductRecord
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList
import java.math.BigDecimal

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProductControllerTest {

    @Autowired
    private lateinit var dataInitializer: DataInitializer

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    @BeforeEach
    fun `wait for deterministic data readiness`(): Unit = runSuspendIO {
        dataInitializer.awaitReady()
    }

    @Test
    @Order(1)
    fun `GET products returns list`() {
        val products = webTestClient.get().uri("/products")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<ProductRecord>()
            .returnResult()
            .responseBody
            .orEmpty()
        products shouldHaveSize 3
    }

    @Test
    @Order(2)
    fun `re-running initialization keeps seed idempotent`() = runSuspendIO {
        dataInitializer.initializeData()
        dataInitializer.initializeData()

        val products = webTestClient.get().uri("/products")
            .exchange()
            .expectStatus().isOk
            .expectBodyList<ProductRecord>()
            .returnResult()
            .responseBody
            .orEmpty()
        products shouldHaveSize 3
    }

    @Test
    fun `GET readyz reports accepting traffic after initialization`() {
        webTestClient.get().uri("/readyz")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
    }

    @Test
    fun `POST product creates new entity`() {
        val dto = ProductRecord(name = "New Product", price = BigDecimal("15.00"), stock = 10)
        webTestClient.post().uri("/products")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(dto)
            .exchange()
            .expectStatus().isCreated
            .expectBody<ProductRecord>()
            .consumeWith { result ->
                result.responseBody?.id.shouldNotBeNull()
                result.responseBody?.name shouldBeEqualTo "New Product"
            }
    }

    @Test
    fun `GET product by id returns entity`() {
        // 먼저 생성
        val dto = ProductRecord(name = "Findable", price = BigDecimal("5.00"), stock = 1)
        val created = webTestClient.post().uri("/products")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
            .exchange().expectBody<ProductRecord>().returnResult().responseBody!!

        webTestClient.get().uri("/products/${created.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody<ProductRecord>()
            .consumeWith { result ->
                result.responseBody?.name shouldBeEqualTo "Findable"
            }
    }

    @Test
    fun `PUT product updates entity`() {
        val dto = ProductRecord(name = "Before Update", price = BigDecimal("5.00"), stock = 1)
        val created = webTestClient.post().uri("/products")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
            .exchange().expectBody<ProductRecord>().returnResult().responseBody!!

        val updated = dto.copy(name = "After Update", price = BigDecimal("10.00"))
        webTestClient.put().uri("/products/${created.id}")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(updated)
            .exchange()
            .expectStatus().isOk
            .expectBody<ProductRecord>()
            .consumeWith { result ->
                result.responseBody?.name shouldBeEqualTo "After Update"
            }
    }

    @Test
    fun `DELETE product removes entity`() {
        val dto = ProductRecord(name = "To Be Deleted", price = BigDecimal("1.00"), stock = 1)
        val created = webTestClient.post().uri("/products")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
            .exchange().expectBody<ProductRecord>().returnResult().responseBody!!

        webTestClient.delete().uri("/products/${created.id}")
            .exchange()
            .expectStatus().isNoContent()

        webTestClient.get().uri("/products/${created.id}")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `GET missing product returns 404`() {
        webTestClient.get().uri("/products/999999")
            .exchange()
            .expectStatus().isNotFound
    }

}
