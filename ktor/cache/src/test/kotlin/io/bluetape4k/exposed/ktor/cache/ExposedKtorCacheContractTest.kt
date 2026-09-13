package io.bluetape4k.exposed.ktor.cache

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ExposedKtorCacheContractTest {

    companion object: KLogging()

    @Test
    fun `cache contributors expose a core cache probe without leaking supplier details`() = runSuspendIO {
        val report = CacheHealthReport(
            mode = CacheWriteMode.WRITE_BEHIND,
            queueDepth = 3,
            workerState = CacheWorkerState.RUNNING,
            lastFlushError = null,
        )
        val config = ExposedKtorCacheReadinessConfig(
            listOf(ExposedKtorCacheContributor.jdbcRepository("orders") { report })
        )
        val probe = exposedKtorCacheReadinessProbes(config).single()

        probe.backend shouldBeEqualTo ExposedKtorReadinessBackend.CACHE
        probe.component shouldBeEqualTo "orders"
        probe.probe(1.seconds) shouldBeEqualTo ExposedKtorReadinessOutcome.UP
    }

    @Test
    fun `cache configuration is defensive and bounded`() {
        val source = mutableListOf(ExposedKtorCacheContributor.custom("orders") { ExposedKtorCacheStatus.UP })
        val config = ExposedKtorCacheReadinessConfig(source)
        source += ExposedKtorCacheContributor.custom("other") { ExposedKtorCacheStatus.UP }
        config.contributors.size shouldBeEqualTo 1

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (config.contributors as MutableList<ExposedKtorCacheContributor>).add(source[1])
        }

        assertFailsWith<IllegalArgumentException> {
            ExposedKtorCacheReadinessConfig(
                listOf(
                    ExposedKtorCacheContributor.custom("orders") { ExposedKtorCacheStatus.UP },
                    ExposedKtorCacheContributor.custom("orders") { ExposedKtorCacheStatus.DOWN },
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExposedKtorCacheContributor.custom("orders/{id}") {
                ExposedKtorCacheStatus.UP
            }
        }
        config.contributors.first() shouldNotBe source[1]
    }

    @Test
    fun `active supplier cancellation is sanitized as down`() = runSuspendIO {
        val probe = exposedKtorCacheReadinessProbes(
            ExposedKtorCacheReadinessConfig(
                listOf(
                    ExposedKtorCacheContributor.custom("orders") {
                        throw CancellationException("supplier detail")
                    },
                ),
            ),
        ).single()

        probe.probe(1.seconds) shouldBeEqualTo ExposedKtorReadinessOutcome.DOWN
    }
}
