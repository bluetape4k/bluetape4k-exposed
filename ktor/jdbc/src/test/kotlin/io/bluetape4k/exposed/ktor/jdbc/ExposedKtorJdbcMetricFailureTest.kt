package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class ExposedKtorJdbcMetricFailureTest {

    private class RequestFailure : IllegalArgumentException("transaction failed")
    private class RequestError : AssertionError("transaction failed")
    private class RequestCancellation : CancellationException("transaction cancelled")

    @Test
    fun `success metric failure does not change committed jdbc result`() = runSuspendIO {
        withJdbcResources { database, dispatcher, registry, _ ->
            transaction(database) { SchemaUtils.create(MetricItems) }

            val result = mockk<ApplicationCall>().exposedJdbcTransaction(database, dispatcher, registry) {
                MetricItems.insert { it[value] = "committed" }
                "committed"
            }

            result shouldBeEqualTo "committed"
            transaction(database) {
                MetricItems.selectAll().count() shouldBeEqualTo 1L
            }
        }
    }

    @Test
    fun `metric failure does not replace jdbc transaction exception`() = runSuspendIO {
        withJdbcResources { database, dispatcher, registry, metricFailure ->
            val primary = RequestFailure()

            val failure = assertFailsWith<ExposedKtorTransactionException> {
                mockk<ApplicationCall>().exposedJdbcTransaction(database, dispatcher, registry) {
                    throw primary
                }
            }

            failure.cause shouldBeEqualTo primary
            primary.suppressed.toList() shouldHaveSize 1
            primary.suppressed.single() shouldBeEqualTo metricFailure
        }
    }

    @Test
    fun `metric failure does not replace jdbc cancellation`() = runSuspendIO {
        withJdbcResources { database, dispatcher, registry, metricFailure ->
            val primary = RequestCancellation()

            val failure = assertFailsWith<RequestCancellation> {
                mockk<ApplicationCall>().exposedJdbcTransaction(database, dispatcher, registry) {
                    throw primary
                }
            }

            failure shouldBeEqualTo primary
            primary.suppressed.toList() shouldHaveSize 1
            primary.suppressed.single() shouldBeEqualTo metricFailure
        }
    }

    @Test
    fun `metric failure does not replace jdbc Error`() = runSuspendIO {
        withJdbcResources { database, dispatcher, registry, metricFailure ->
            val primary = RequestError()

            val failure = assertFailsWith<RequestError> {
                mockk<ApplicationCall>().exposedJdbcTransaction(database, dispatcher, registry) {
                    throw primary
                }
            }

            failure shouldBeEqualTo primary
            primary.suppressed.toList() shouldHaveSize 1
            primary.suppressed.single() shouldBeEqualTo metricFailure
        }
    }

    private suspend fun withJdbcResources(
        block: suspend (
            Database,
            kotlinx.coroutines.ExecutorCoroutineDispatcher,
            SimpleMeterRegistry,
            IllegalStateException,
        ) -> Unit,
    ) {
        val database = Database.connect(
            url = "jdbc:h2:mem:ktor-jdbc-metric-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val metricFailure = IllegalStateException("metric recording failed")
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id = throw metricFailure
        })

        try {
            block(database, dispatcher, registry, metricFailure)
        } finally {
            registry.close()
            dispatcher.close()
            executor.shutdownNow()
            TransactionManager.closeAndUnregister(database)
        }
    }

    private object MetricItems : Table("ktor_jdbc_metric_items") {
        val value = varchar("value", 64)
    }
}
