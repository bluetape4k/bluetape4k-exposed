package io.bluetape4k.exposed.ktor.cache

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ExposedKtorCacheContractTest {

    @Test
    fun `cache contributors expose a core cache probe without leaking supplier details`() = runBlocking {
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

        probe.backend shouldBeEqualTo io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend.CACHE
        probe.component shouldBeEqualTo "orders"
        probe.probe(1.seconds) shouldBeEqualTo
            io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome.UP
        Unit
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
            ExposedKtorCacheContributor.custom("orders/{id}") { ExposedKtorCacheStatus.UP }
        }
        (config.contributors.first() !== source[1]).shouldBeTrue()
    }

    @Test
    fun `active supplier cancellation is sanitized as down`() = runBlocking {
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
        Unit
    }
}
