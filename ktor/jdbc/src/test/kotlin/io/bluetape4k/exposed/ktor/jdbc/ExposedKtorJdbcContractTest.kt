package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.Executors
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedKtorJdbcContractTest : AbstractExposedTest() {

    @Test
    fun `jdbc status pages redact database exception details`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { bluetape4kExposedJdbcErrors() }
            routing {
                get("/jdbc/private") {
                    throw SQLException("jdbc:h2:mem:secret password=top-secret SELECT payments")
                }
            }
        }

        val response = client.get("/jdbc/private")
        response.status shouldBeEqualTo HttpStatusCode.ServiceUnavailable
        val body = response.bodyAsText()
        body.contains("EXPOSED_DATABASE_UNAVAILABLE").shouldBeTrue()
        body.contains("top-secret").shouldBeFalse()
        body.contains("jdbc:h2").shouldBeFalse()
        body.contains("/jdbc/private").shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedTest.ENABLE_DIALECTS_METHOD)
    fun `jdbc probe supports each enabled database`(testDB: TestDB) {
        val database = testDB.connect()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val probe = exposedKtorJdbcReadinessProbe(database, dispatcher, component = "orders")
            runBlocking { probe.probe(5.seconds) } shouldBeEqualTo ExposedKtorReadinessOutcome.UP
            Unit
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `jdbc probe runs select one on caller dispatcher and exposes only jdbc metadata`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ktor-jdbc-contract;DB_CLOSE_DELAY=-1")
            user = "sa"
        }
        val database = Database.connect(dataSource)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val probe = exposedKtorJdbcReadinessProbe(database, dispatcher, component = "orders")
            probe.backend shouldBeEqualTo ExposedKtorReadinessBackend.JDBC
            probe.component shouldBeEqualTo "orders"
            runBlocking { probe.probe(2.seconds) } shouldBeEqualTo ExposedKtorReadinessOutcome.UP
            Unit
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `jdbc probe rejects non-finite timeout and unsafe component`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ktor-jdbc-validation;DB_CLOSE_DELAY=-1")
            user = "sa"
        }
        val database = Database.connect(dataSource)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            assertFailsWith<IllegalArgumentException> {
                exposedKtorJdbcReadinessProbe(database, dispatcher, jdbcQueryTimeout = Duration.ZERO)
            }
            assertFailsWith<IllegalArgumentException> {
                exposedKtorJdbcReadinessProbe(database, dispatcher, component = "orders/{id}")
            }
        } finally {
            dispatcher.close()
        }
    }
}
