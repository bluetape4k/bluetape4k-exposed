package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import io.r2dbc.spi.R2dbcException
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedKtorR2dbcContractTest : AbstractExposedR2dbcTest() {

    @Test
    fun `r2dbc status pages redact database exception details`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { bluetape4kExposedR2dbcErrors() }
            routing {
                get("/r2dbc/private") {
                    throw object : R2dbcException("r2dbc:h2:mem:secret password=top-secret SELECT payments") {}
                }
            }
        }

        val response = client.get("/r2dbc/private")
        response.status shouldBeEqualTo HttpStatusCode.ServiceUnavailable
        val body = response.bodyAsText()
        body.contains("EXPOSED_DATABASE_UNAVAILABLE").shouldBeTrue()
        body.contains("top-secret").shouldBeFalse()
        body.contains("r2dbc:h2").shouldBeFalse()
        body.contains("/r2dbc/private").shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `r2dbc probe supports each enabled database`(testDB: TestDB) = runSuspendIO {
        withDb(testDB) {
            val database = checkNotNull(testDB.db)
            val probe = exposedKtorR2dbcReadinessProbe(database, component = "orders")
            probe.probe(5.seconds) shouldBeEqualTo ExposedKtorReadinessOutcome.UP
            Unit
        }
    }

    @Test
    fun `r2dbc probe runs select one without a blocking dispatcher`() {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-contract;DB_CLOSE_DELAY=-1;")
            }
        )
        val probe = exposedKtorR2dbcReadinessProbe(database, component = "orders")

        probe.backend shouldBeEqualTo ExposedKtorReadinessBackend.R2DBC
        probe.component shouldBeEqualTo "orders"
        runBlocking { probe.probe(2.seconds) } shouldBeEqualTo ExposedKtorReadinessOutcome.UP
        Unit
    }

    @Test
    fun `r2dbc probe rejects unsafe component`() {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-validation;DB_CLOSE_DELAY=-1;")
            }
        )

        assertFailsWith<IllegalArgumentException> {
            exposedKtorR2dbcReadinessProbe(database, component = "orders/{id}")
        }
    }
}
