package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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

        registry.meters.size shouldBeEqualTo 8
        (registry.meters.count { it.id.name == CACHE_READINESS_METER_NAME }) shouldBeEqualTo 4
        CACHE_OUTCOMES.forEach { outcome ->
            val timer = registry.find(CACHE_READINESS_METER_NAME)
                    .tags("component", "orders", "kind", "custom", "operation", "readiness", "outcome", outcome)
                    .timer()
            val nonNullTimer = timer.shouldNotBeNull()
            nonNullTimer.id.description shouldBeEqualTo "Cache readiness probe duration."
            nonNullTimer.id.baseUnit shouldBeEqualTo "seconds"
            (nonNullTimer.id.tags.map { it.key }.toSet()) shouldBeEqualTo
                setOf("component", "kind", "operation", "outcome")
        }
        assertGauge(registry, CACHE_QUEUE_DEPTH_METER_NAME, "entries")
        assertGauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "events")
        assertGauge(registry, CACHE_SNAPSHOT_DROPPED_METER_NAME, "events")
        assertGauge(registry, CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME, "events")
        (binding.currentSample().queueDepth.isNaN()).shouldBeTrue()
        assertFailsWith<UnsupportedOperationException> {
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
        (binding.publish(generation, contributor.probe())).shouldBeTrue()
        binding.record(SUCCESS_OUTCOME, 10L)

        (gauge(registry, CACHE_QUEUE_DEPTH_METER_NAME, "snapshots", "snapshot").isNaN()).shouldBeTrue()
        (gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot")) shouldBeEqualTo 3.0
        (gauge(registry, CACHE_SNAPSHOT_DROPPED_METER_NAME, "snapshots", "snapshot")) shouldBeEqualTo 5.0
        (gauge(registry, CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME, "snapshots", "snapshot")) shouldBeEqualTo 7.0
        (registry.find(CACHE_READINESS_METER_NAME)
                .tags(
                    "component", "snapshots",
                    "kind", "snapshot",
                    "operation", "readiness",
                    "outcome", SUCCESS_OUTCOME,
                )
                .timer().shouldNotBeNull()
                .count()) shouldBeEqualTo 1L
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

        (binding.publish(newGeneration, contributor.probe())).shouldBeTrue()
        (binding.publish(oldGeneration, ExposedKtorCacheSample.snapshot(99, 99, 99))).shouldBeFalse()
        (gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot")) shouldBeEqualTo 1.0
        (binding.publishUnavailable(newGeneration)).shouldBeTrue()
        (gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "snapshots", "snapshot").isNaN()).shouldBeTrue()
    }

    @Test
    fun `sixteen contributors register exactly 128 IDs and repeated request updates add none`() {
        val registry = SimpleMeterRegistry()
        val config = config16()
        val bindings = registerExposedKtorCacheMetrics(registry, config)
        registry.meters.size shouldBeEqualTo 128

        repeat(100) {
            bindings.forEach { binding ->
                val generation = binding.claimGeneration()
                binding.publish(generation, ExposedKtorCacheSample.custom(ExposedKtorCacheStatus.UP))
                binding.record(SUCCESS_OUTCOME, 1L)
            }
        }
        registry.meters.size shouldBeEqualTo 128
    }

    @Test
    fun `preflight rejects extra-tag and incompatible-type collisions without adding IDs`() {
        val registry = SimpleMeterRegistry()
        Gauge.builder(CACHE_READINESS_METER_NAME, AtomicInteger()) { it.get().toDouble() }
            .tags("component", "orders", "kind", "custom", "unexpected", "secret")
            .register(registry)
        val before = registry.meters.map { it.id }.toSet()

        val error = assertFailsWith<IllegalArgumentException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        (error.message.orEmpty().contains("reason=identity_collision")).shouldBeTrue()
        (registry.meters.map { it.id }.toSet()) shouldBeEqualTo before
        error.cause.shouldBeNull()
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

        val error = assertFailsWith<IllegalArgumentException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        error.message shouldBeEqualTo "Cache metric installation rejected: reason=identity_collision."
        error.cause.shouldNotBeNull()
        registry.meters.size shouldBeEqualTo 1
        (registry.meters.single()) shouldBeSameInstanceAs preexisting
        (preexisting.value()) shouldBeEqualTo 7.0
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

        val error = assertFailsWith<IllegalArgumentException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        error.message shouldBeEqualTo "Cache metric installation rejected: reason=identity_collision."
        error.cause.shouldNotBeNull()
        (registry.meters.isEmpty()).shouldBeTrue()
        (registry.removals.get()) shouldBeEqualTo 1
    }

    @Test
    fun `meter filter denial returns sanitized registration failure with no residual IDs`() {
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(MeterFilter.denyNameStartsWith("bluetape4k.exposed.ktor.cache"))

        val error = assertFailsWith<IllegalStateException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        error.message shouldBeEqualTo "Cache metric installation failed: reason=registration_failed."
        (error.cause as CacheMeterInstallationFailure).reason shouldBeEqualTo
            CacheMeterFailureReason.REGISTRATION_FAILED
        (registry.meters.isEmpty()).shouldBeTrue()
    }

    @Test
    fun `registration failure rolls back only current attempt and preserves sanitized reason`() {
        val registry = FailingSimpleMeterRegistry(failAt = 6)
        val unrelated = registry.counter("application.unrelated")

        val error = assertFailsWith<IllegalStateException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        error.message shouldBeEqualTo "Cache metric installation failed: reason=registration_failed."
        (error.cause as CacheMeterInstallationFailure).reason shouldBeEqualTo
            CacheMeterFailureReason.REGISTRATION_FAILED
        (registry.meters.map { it.id }) shouldBeEqualTo listOf(unrelated.id)
    }

    @Test
    fun `rollback remove failure is reported with structured residual diagnostic`() {
        val registry = FailingRemovalRegistry(failAt = 6)
        registry.counter("application.unrelated")

        val error = assertFailsWith<IllegalStateException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        val diagnostic = error.suppressed.single() as CacheMeterRollbackDiagnostic
        diagnostic.attempted shouldBeEqualTo 5
        diagnostic.removed shouldBeEqualTo 4
        diagnostic.notFound shouldBeEqualTo 0
        diagnostic.failed shouldBeEqualTo 1
        diagnostic.residual shouldBeEqualTo 1
        diagnostic.message shouldBeEqualTo
            "Cache metric rollback failed: attempted=5,removed=4,notFound=0,failed=1,residual=1."
        diagnostic.suppressed.single().message.orEmpty().contains("remove-secret") shouldBeEqualTo false
        (error.cause as CacheMeterInstallationFailure).primaryFailureType shouldBeEqualTo
            IllegalArgumentException::class.java.name
        registry.meters.size shouldBeEqualTo 2
        registry.meters
            .filter { it.id.name != "application.unrelated" }
            .map { it.id.name }
            .toSet() shouldBeEqualTo setOf(CACHE_READINESS_METER_NAME)
    }

    @Test
    fun `rollback missing meter is reported without stopping remaining cleanup`() {
        val registry = MissingRemovalRegistry(failAt = 6)

        val error = assertFailsWith<IllegalStateException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }

        val diagnostic = error.suppressed.single() as CacheMeterRollbackDiagnostic
        diagnostic.attempted shouldBeEqualTo 5
        diagnostic.removed shouldBeEqualTo 4
        diagnostic.notFound shouldBeEqualTo 1
        diagnostic.failed shouldBeEqualTo 0
        diagnostic.residual shouldBeEqualTo 1
        registry.meters.size shouldBeEqualTo 1
    }

    @Test
    fun `successful rollback leaves registry clean and allows deterministic retry`() {
        val registry = FailingOnceRegistrationRegistry(failAt = 6)

        assertFailsWith<IllegalStateException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        registry.meters.isEmpty().shouldBeTrue()

        registerExposedKtorCacheMetrics(registry, config("orders"))

        registry.meters.size shouldBeEqualTo 8
    }

    @Test
    fun `cancellation is rethrown after rollback without sanitizing it as registration failure`() {
        val registry = CancellingRegistrationRegistry()

        assertFailsWith<CancellationException> {
            registerExposedKtorCacheMetrics(registry, config("orders"))
        }
        registry.meters.isEmpty().shouldBeTrue()
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

            (results.count { it.isSuccess }) shouldBeEqualTo 1
            (results.count { it.isFailure }) shouldBeEqualTo 1
            registry.meters.size shouldBeEqualTo 128
            val winnerBindings = results.single { it.isSuccess }.getOrThrow()
            val winnerGeneration = winnerBindings.first().claimGeneration()
            (winnerBindings.first().publish(
                    winnerGeneration,
                    ExposedKtorCacheSample.snapshot(42, 43, 44),
                )).shouldBeTrue()
            (gauge(registry, CACHE_SNAPSHOT_PENDING_METER_NAME, "cache_0", "custom")) shouldBeEqualTo 42.0
            val loser = results.single { it.isFailure }.exceptionOrNull().shouldNotBeNull()
            (loser.message.orEmpty().contains("reason=identity_collision")).shouldBeTrue()
            loser.cause.shouldBeNull()
        } finally {
            executor.shutdownNow()
            (executor.awaitTermination(10, TimeUnit.SECONDS)).shouldBeTrue()
        }
    }

    @Test
    fun `two distinct maximum routes register 128 IDs each`() {
        val registry = SimpleMeterRegistry()
        registerExposedKtorCacheMetrics(registry, config16("cache"))
        registerExposedKtorCacheMetrics(registry, config16("other"))
        registry.meters.size shouldBeEqualTo 256
    }

    private fun assertGauge(registry: SimpleMeterRegistry, name: String, baseUnit: String) {
        val meter = registry.find(name).tags("component", "orders", "kind", "custom").gauge()
        val nonNullMeter = meter.shouldNotBeNull()
        nonNullMeter.id.baseUnit shouldBeEqualTo baseUnit
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
        nonNullMeter.id.description shouldBeEqualTo expectedDescription
        (nonNullMeter.value().isNaN()).shouldBeTrue()
        (nonNullMeter.id.tags.map { it.key }.toSet()) shouldBeEqualTo setOf("component", "kind")
    }

    private fun gauge(
        registry: SimpleMeterRegistry,
        name: String,
        component: String,
        kind: String,
    ): Double = registry.find(name).tags("component", component, "kind", kind).gauge().shouldNotBeNull().value()

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

    private class FailingRemovalRegistry(
        private val failAt: Int,
    ) : SimpleMeterRegistry() {
        private val registrations = AtomicInteger()
        private var failRemoval = true

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

        override fun remove(meter: Meter): Meter? {
            if (failRemoval) {
                failRemoval = false
                throw IllegalStateException("remove-secret")
            }
            return super.remove(meter)
        }

        private fun failIfNeeded() {
            if (registrations.incrementAndGet() == failAt) {
                throw IllegalArgumentException("registry-secret")
            }
        }
    }

    private class FailingOnceRegistrationRegistry(
        private val failAt: Int,
    ) : SimpleMeterRegistry() {
        private val registrations = AtomicInteger()
        private var fail = true

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
            if (fail && registrations.incrementAndGet() == failAt) {
                fail = false
                throw IllegalArgumentException("registry-secret")
            }
        }
    }

    private class MissingRemovalRegistry(
        private val failAt: Int,
    ) : SimpleMeterRegistry() {
        private val registrations = AtomicInteger()
        private var missingRemoval = true

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

        override fun remove(meter: Meter): Meter? {
            if (missingRemoval) {
                missingRemoval = false
                return null
            }
            return super.remove(meter)
        }

        private fun failIfNeeded() {
            if (registrations.incrementAndGet() == failAt) {
                throw IllegalArgumentException("registry-secret")
            }
        }
    }

    private class CancellingRegistrationRegistry : SimpleMeterRegistry() {
        private var cancel = true

        override fun <T : Any> newGauge(
            id: Meter.Id,
            obj: T?,
            valueFunction: ToDoubleFunction<T>,
        ): Gauge {
            if (cancel) {
                cancel = false
                throw CancellationException("registry-secret")
            }
            return super.newGauge(id, obj, valueFunction)
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
