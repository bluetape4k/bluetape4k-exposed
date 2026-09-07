package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.flow.single
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Test

class ExposedKtorR2dbcMetricFailureTest {

    @Test
    fun `success metric failure does not change committed r2dbc result`() = runSuspendIO {
        val database = R2dbcDatabase.connect(
            databaseConfig = R2dbcDatabaseConfig {
                setUrl("r2dbc:h2:mem:///ktor-r2dbc-metric-${System.nanoTime()};DB_CLOSE_DELAY=-1;")
            },
        )
        val registry = failingRegistry()

        try {
            suspendTransaction(database) {
                exec("CREATE TABLE ktor_r2dbc_metric_items (metric_value VARCHAR(64))")
            }

            val result = mockk<ApplicationCall>().exposedR2dbcTransaction(database, registry) {
                exec("INSERT INTO ktor_r2dbc_metric_items (metric_value) VALUES ('committed')")
                "committed"
            }

            result shouldBeEqualTo "committed"
            val count = suspendTransaction(database) {
                exec("SELECT COUNT(*) FROM ktor_r2dbc_metric_items") { row ->
                    (row.get(0) as Number).toInt()
                }?.single()
            }
            count shouldBeEqualTo 1
        } finally {
            registry.close()
            TransactionManager.closeAndUnregister(database)
        }
    }

    private fun failingRegistry(): SimpleMeterRegistry = SimpleMeterRegistry().apply {
        config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("metric recording failed")
        })
    }
}
