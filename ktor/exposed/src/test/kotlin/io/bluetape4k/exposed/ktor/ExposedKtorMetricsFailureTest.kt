package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
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

@Suppress("DEPRECATION")
class ExposedKtorMetricsFailureTest {

    @Test
    fun `compatibility public transaction keeps committed result when success metric fails`() = runSuspendIO {
        val database = Database.connect(
            url = "jdbc:h2:mem:ktor-compat-metric-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("metric recording failed")
        })

        try {
            transaction(database) { SchemaUtils.create(CompatibilityMetricItems) }

            val result = mockk<ApplicationCall>().exposedJdbcTransaction(database, dispatcher, registry) {
                CompatibilityMetricItems.insert { it[value] = "committed" }
                "committed"
            }

            result shouldBeEqualTo "committed"
            transaction(database) {
                CompatibilityMetricItems.selectAll().count() shouldBeEqualTo 1L
            }
        } finally {
            registry.close()
            dispatcher.close()
            executor.shutdownNow()
            TransactionManager.closeAndUnregister(database)
        }
    }

    private object CompatibilityMetricItems : Table("ktor_compat_metric_items") {
        val value = varchar("value", 64)
    }
}
