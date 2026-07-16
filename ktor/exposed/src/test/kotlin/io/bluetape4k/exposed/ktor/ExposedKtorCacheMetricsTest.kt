package io.bluetape4k.exposed.ktor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.ToDoubleFunction

class ExposedKtorCacheMetricsTest {

    @Test
    fun `one contributor registers exact fixed meter IDs tags descriptions and base units`() {
        val registry = SimpleMeterRegistry()
        val binding = registerExposedKtorCacheMetrics(registry, config("orders")).single()

        assertEquals(8, registry.meters.size)
        assertEquals(4, registry.meters.count { it.id.name == CACHE_READINESS_METER_NAME })
        CACHE_OUTCOMES.forEach { outcome ->
            val timer = registry.find(CACHE_READINESS_METER_NAME)
                    .tags("component", "orders", "kind", "custom", "operation", "readiness", "outcome", outcome)
                    .timer()
            assertNotNull(timer)
            assertEquals("Cache readiness probe duration.", timer!!.id.description)
            assertEquals("seconds", timer.id.baseUnit)
            assertEquals(
                setOf("component", "kind", "operation", "outcome"),
                timer.id.tags.map { it.key }.toSet(),
            )
        }
        assertGauge(registry, CACHE_QUEUE_DEPTH_METER_NAME, "entries")
        assertGauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "events")
        assertGauge(registry, CACHE_SNAPSHOT_DROPPED_METER_NAME, "events")
        assertGauge(registry, CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME, "events")
        assertTrue(binding.currentSample().queueDepth.isNaN())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (binding.tags as MutableList<io.micrometer.core.instrument.Tag>).add(
                io.micrometer.core.instrument.Tag.of("secret", "value")
            )
        }
    }

    @Test
    fun `successful publication uses one immutable sample and non-applicable gauges remain NaN`() = runTest {
        val registry = SimpleMeterRegistry()
        val contributor = ExposedKtorCacheContributor.snapshot("snapshots", fixedFailureBuffer(3, 5, 7))
        val binding = registerExposedKtorCacheMetrics(
            registry,
            ExposedKtorCacheReadinessConfig(listOf(contributor)),
        ).single()

        val generation = binding.claimGeneration()
        assertTrue(binding.publish(generation, contributor.probe()))
        binding.record(SUCCESS_OUTCOME, 10L)

        assertTrue(gauge(registry, CACHE_QUEUE_DEPTH_METER_NAME, "snapshots", "snapshot").isNaN())
        assertEquals(3.0, gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot"))
        assertEquals(5.0, gauge(registry, CACHE_SNAPSHOT_DROPPED_METER_NAME, "snapshots", "snapshot"))
        assertEquals(7.0, gauge(registry, CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME, "snapshots", "snapshot"))
        assertEquals(
            1L,
            registry.find(CACHE_READINESS_METER_NAME)
                .tags(
                    "component", "snapshots",
                    "kind", "snapshot",
                    "operation", "readiness",
                    "outcome", SUCCESS_OUTCOME,
                )
                .timer()!!
                .count(),
        )
    }

    @Test
    fun `newer claimed generation prevents late publication and error clears gauges`() = runTest {
        val registry = SimpleMeterRegistry()
        val contributor = ExposedKtorCacheContributor.snapshot("snapshots", fixedFailureBuffer(1, 2, 3))
        val binding = registerExposedKtorCacheMetrics(
            registry,
            ExposedKtorCacheReadinessConfig(listOf(contributor)),
        ).single()
        val oldGeneration = binding.claimGeneration()
        val newGeneration = binding.claimGeneration()

        assertTrue(binding.publish(newGeneration, contributor.probe()))
        assertFalse(binding.publish(oldGeneration, ExposedKtorCacheSample.snapshot(99, 99, 99)))
        assertEquals(1.0, gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot"))
        assertTrue(binding.publishUnavailable(newGeneration))
        assertTrue(gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot").isNaN())
    }

    @Test
    fun `sixteen contributors register exactly 128 IDs and repeated request updates add none`() {
        val registry = SimpleMeterRegistry()
        val config = config16()
        val bindings = registerExposedKtorCacheMetrics(registry, config)
        assertEquals(128, registry.meters.size)

        repeat(100) {
            bindings.forEach { binding ->
                val generation = binding.claimGeneration()
                binding.publish(generation, ExposedKtorCacheSample.custom(ExposedKtorCacheStatus.UP))
                binding.record(SUCCESS_OUTCOME, 1L)
            }
        }
        assertEquals(128, registry.meters.size)
    }

    @Test
    fun `preflight rejects extra-tag and incompatible-type collisions without adding IDs`() {
        val registry = SimpleMeterRegistry()
        Gauge.builder(CACHE_READINESS_METER_NAME, AtomicInteger()) { it.get().toDouble() }
            .tags("component", "orders", "kind", "custom", "unexpected", "secret")
            .register(registry)
        val before = registry.meters.map { it.id }.toSet()

        val error = assertThrows(IllegalArgumentException::class.java) {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        assertTrue(error.message.orEmpty().contains("reason=identity_collision"))
        assertEquals(before, registry.meters.map { it.id }.toSet())
        assertNull(error.cause)
    }

    @Test
    fun `meter filter transformed collision preserves the preexisting meter and returns no binding`() {
        val registry = SimpleMeterRegistry()
        val preexisting = Gauge.builder("filtered.shared", AtomicInteger(7)) { it.get().toDouble() }
            .tags("owner", "existing")
            .register(registry)
        registry.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id =
                if (id.name.startsWith("bluetape4k.exposed.ktor.cache")) {
                    id.withName("filtered.shared").replaceTags(Tags.of("owner", "existing"))
                } else {
                    id
                }
        })

        val error = assertThrows(IllegalArgumentException::class.java) {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        assertEquals("Cache metric installation rejected: reason=identity_collision.", error.message)
        assertNull(error.cause)
        assertEquals(1, registry.meters.size)
        assertSame(preexisting, registry.meters.single())
        assertEquals(7.0, preexisting.value())
    }

    @Test
    fun `meter filter collapse of two current IDs rolls back the owned meter exactly once`() {
        val registry = CountingRemovalRegistry()
        registry.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id =
                if (id.name.startsWith("bluetape4k.exposed.ktor.cache")) {
                    id.withName("collapsed.current").replaceTags(Tags.of("owner", "attempt"))
                } else {
                    id
                }
        })

        val error = assertThrows(IllegalArgumentException::class.java) {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        assertEquals("Cache metric installation rejected: reason=identity_collision.", error.message)
        assertNull(error.cause)
        assertTrue(registry.meters.isEmpty())
        assertEquals(1, registry.removals.get())
    }

    @Test
    fun `meter filter denial returns sanitized registration failure with no residual IDs`() {
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(MeterFilter.denyNameStartsWith("bluetape4k.exposed.ktor.cache"))

        val error = assertThrows(IllegalStateException::class.java) {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        assertEquals("Cache metric installation failed: reason=registration_failed.", error.message)
        assertNull(error.cause)
        assertTrue(registry.meters.isEmpty())
    }

    @Test
    fun `registration failure rolls back only current attempt and discards registry cause`() {
        val registry = FailingSimpleMeterRegistry(failAt = 6)
        val unrelated = registry.counter("application.unrelated")

        val error = assertThrows(IllegalStateException::class.java) {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        assertEquals("Cache metric installation failed: reason=registration_failed.", error.message)
        assertNull(error.cause)
        assertEquals(listOf(unrelated.id), registry.meters.map { it.id })
    }

    @Test
    fun `concurrent identical installs have one winner one sanitized loser and 128 IDs`() {
        val registry = SimpleMeterRegistry()
        val config = config16()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<Result<List<ExposedKtorCacheMetricBinding>>> {
                    start.await()
                    runCatching { registerExposedKtorCacheMetrics(registry, config) }
                }
            }
            start.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertEquals(128, registry.meters.size)
            val winnerBindings = results.single { it.isSuccess }.getOrThrow()
            val winnerGeneration = winnerBindings.first().claimGeneration()
            assertTrue(
                winnerBindings.first().publish(
                    winnerGeneration,
                    ExposedKtorCacheSample.snapshot(42, 43, 44),
                )
            )
            assertEquals(42.0, gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "cache_0", "custom"))
            val loser = results.single { it.isFailure }.exceptionOrNull()!!
            assertTrue(loser.message.orEmpty().contains("reason=identity_collision"))
            assertNull(loser.cause)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `two distinct maximum routes register 128 IDs each`() {
        val registry = SimpleMeterRegistry()
        registerExposedKtorCacheMetrics(registry, config16("cache"))
        registerExposedKtorCacheMetrics(registry, config16("other"))
        assertEquals(256, registry.meters.size)
    }

    private fun assertGauge(registry: SimpleMeterRegistry, name: String, baseUnit: String) {
        val meter = registry.find(name).tags("component", "orders", "kind", "custom").gauge()
        assertNotNull(meter)
        assertEquals(baseUnit, meter!!.id.baseUnit)
        val expectedDescription = when (name) {
            CACHE_QUEUE_DEPTH_METER_NAME ->
                "Accepted write-behind entries not yet observed as flushed; NaN means unavailable, not zero."
            CACHE_SNAPSHOT_PENDING_METER_NAME ->
                "Currently retained snapshot failure events; NaN means unavailable, not zero."
            CACHE_SNAPSHOT_DROPPED_METER_NAME ->
                "Cumulative snapshot events dropped by the bounded buffer; NaN means unavailable, not zero."
            CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME ->
                "Cumulative snapshot observer callback failures; NaN means unavailable, not zero."
            else -> error("Unexpected gauge")
        }
        assertEquals(expectedDescription, meter.id.description)
        assertTrue(meter.value().isNaN())
        assertEquals(setOf("component", "kind"), meter.id.tags.map { it.key }.toSet())
    }

    private fun gauge(
        registry: SimpleMeterRegistry,
        name: String,
        component: String,
        kind: String,
    ): Double = registry.find(name).tags("component", component, "kind", kind).gauge()!!.value()

    private fun config(component: String) = ExposedKtorCacheReadinessConfig(
        listOf(ExposedKtorCacheContributor.custom(component) { ExposedKtorCacheStatus.UP })
    )

    private fun config16(prefix: String = "cache") = ExposedKtorCacheReadinessConfig(
        (0 until 16).map { index ->
            ExposedKtorCacheContributor.custom("${prefix}_$index") { ExposedKtorCacheStatus.UP }
        }
    )

    private fun fixedFailureBuffer(
        size: Int,
        dropped: Long,
        observerFailures: Long,
    ): SnapshotCacheFailureBuffer = mockk(relaxed = true) {
        every { this@mockk.size } returns size
        every { droppedCount } returns dropped
        every { observerFailureCount } returns observerFailures
    }

    private class FailingSimpleMeterRegistry(
        private val failAt: Int,
    ) : SimpleMeterRegistry() {
        private val registrations = AtomicInteger()

        override fun <T : Any> newGauge(
            id: Meter.Id,
            obj: T?,
            valueFunction: ToDoubleFunction<T>,
        ): Gauge {
            failIfNeeded()
            return super.newGauge(id, obj, valueFunction)
        }

        override fun newTimer(
            id: Meter.Id,
            distributionStatisticConfig: io.micrometer.core.instrument.distribution.DistributionStatisticConfig,
            pauseDetector: io.micrometer.core.instrument.distribution.pause.PauseDetector,
        ): Timer {
            failIfNeeded()
            return super.newTimer(id, distributionStatisticConfig, pauseDetector)
        }

        private fun failIfNeeded() {
            if (registrations.incrementAndGet() == failAt) {
                throw IllegalArgumentException("registry-secret")
            }
        }
    }

    private class CountingRemovalRegistry : SimpleMeterRegistry() {
        val removals = AtomicInteger()

        override fun remove(meter: Meter): Meter? {
            removals.incrementAndGet()
            return super.remove(meter)
        }
    }
}
