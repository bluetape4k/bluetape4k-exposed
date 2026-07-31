package io.bluetape4k.exposed.ktor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val CACHE_READINESS_METER_NAME = "bluetape4k.exposed.ktor.cache.readiness"
internal const val CACHE_QUEUE_DEPTH_METER_NAME = "bluetape4k.exposed.ktor.cache.queue.depth"
internal const val CACHE_SNAPSHOT_PENDING_METER_NAME = "bluetape4k.exposed.ktor.cache.snapshot.pending"
internal const val CACHE_SNAPSHOT_DROPPED_METER_NAME = "bluetape4k.exposed.ktor.cache.snapshot.dropped"
internal const val CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME =
    "bluetape4k.exposed.ktor.cache.snapshot.observer.failures"

internal val CACHE_OUTCOMES: List<String> = listOf(
    SUCCESS_OUTCOME,
    ERROR_OUTCOME,
    TIMEOUT_OUTCOME,
    CANCELLED_OUTCOME,
)

/** 하나의 cache contributor에 설치된 fixed-cardinality metric입니다. */
internal class ExposedKtorCacheMetricBinding internal constructor(
    val contributor: ExposedKtorCacheContributor,
    val tags: List<Tag>,
    private val state: ExposedKtorCacheMetricState,
    private val timers: Map<String, Timer>,
) {
    fun claimGeneration(): Long = state.claimGeneration()

    fun publish(generation: Long, sample: ExposedKtorCacheSample): Boolean =
        state.publish(generation, sample)

    fun publishUnavailable(generation: Long): Boolean = publish(generation, ExposedKtorCacheSample.UNAVAILABLE)

    fun currentSample(): ExposedKtorCacheSample = state.currentSample()

    fun record(outcome: String, elapsedNanos: Long) {
        require(outcome in CACHE_OUTCOMES) { "Invalid cache metric outcome." }
        require(elapsedNanos >= 0L) { "Invalid cache metric duration." }
        timers[outcome]?.record(elapsedNanos, TimeUnit.NANOSECONDS)
    }
}

/**
 * contributor마다 gauge 4개와 finite-outcome timer 4개를 정확히 등록합니다.
 *
 * 등록은 설치 중에만 직렬화합니다. 반환된 binding은 meter state와 timer reference를 직접 보관하므로
 * request 처리 시 registry 조회, builder 호출, tag 구성, meter 등록을 수행하지 않습니다.
 * 내보낸 backend time-series 수는 registry와 distribution config에 따라 달라집니다.
 */
internal fun registerExposedKtorCacheMetrics(
    meterRegistry: MeterRegistry?,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
): List<ExposedKtorCacheMetricBinding> {
    if (meterRegistry == null) {
        return cacheReadiness.contributors.map(::bindingWithoutMeters)
    }

    return CACHE_METER_INSTALL_LOCK.withLock {
        preflightCacheMeterCollisions(meterRegistry, cacheReadiness.contributors)
        val ownership = CacheMeterOwnership(meterRegistry)
        try {
            cacheReadiness.contributors.map { contributor ->
                registerContributorMeters(meterRegistry, contributor, ownership)
            }
        } catch (failure: Throwable) {
            ownership.rollback()
            if (failure is Error) throw failure
            when ((failure as? CacheMeterInstallationFailure)?.reason) {
                CacheMeterFailureReason.IDENTITY_COLLISION ->
                    throw IllegalArgumentException("Cache metric installation rejected: reason=identity_collision.")

                else -> throw IllegalStateException("Cache metric installation failed: reason=registration_failed.")
            }
        }
    }
}

private fun bindingWithoutMeters(contributor: ExposedKtorCacheContributor): ExposedKtorCacheMetricBinding =
    ExposedKtorCacheMetricBinding(
        contributor = contributor,
        tags = contributor.metricTags(),
        state = ExposedKtorCacheMetricState(),
        timers = emptyMap(),
    )

private fun registerContributorMeters(
    registry: MeterRegistry,
    contributor: ExposedKtorCacheContributor,
    ownership: CacheMeterOwnership,
): ExposedKtorCacheMetricBinding {
    val tags = contributor.metricTags()
    val state = ExposedKtorCacheMetricState()

    ownership.claim(
        Gauge.builder(CACHE_QUEUE_DEPTH_METER_NAME, state) { it.currentSample().queueDepth }
            .description("Accepted write-behind entries not yet observed as flushed; NaN means unavailable, not zero.")
            .baseUnit("entries")
            .tags(tags)
            .register(registry)
    )
    ownership.claim(
        Gauge.builder(CACHE_SNAPSHOT_PENDING_METER_NAME, state) { it.currentSample().snapshotPending }
            .description("Currently retained snapshot failure events; NaN means unavailable, not zero.")
            .baseUnit("events")
            .tags(tags)
            .register(registry)
    )
    ownership.claim(
        Gauge.builder(CACHE_SNAPSHOT_DROPPED_METER_NAME, state) { it.currentSample().snapshotDropped }
            .description("Cumulative snapshot events dropped by the bounded buffer; NaN means unavailable, not zero.")
            .baseUnit("events")
            .tags(tags)
            .register(registry)
    )
    ownership.claim(
        Gauge.builder(CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME, state) {
            it.currentSample().snapshotObserverFailures
        }
            .description("Cumulative snapshot observer callback failures; NaN means unavailable, not zero.")
            .baseUnit("events")
            .tags(tags)
            .register(registry)
    )

    val timers = CACHE_OUTCOMES.associateWith { outcome ->
        Timer.builder(CACHE_READINESS_METER_NAME)
            .description("Cache readiness probe duration.")
            .tags(tags)
            .tag("operation", READINESS_OPERATION)
            .tag("outcome", outcome)
            .register(registry)
            .also(ownership::claim)
    }
    return ExposedKtorCacheMetricBinding(contributor, tags, state, timers)
}

private fun ExposedKtorCacheContributor.metricTags(): List<Tag> =
    Collections.unmodifiableList(
        arrayListOf(Tag.of("component", component), Tag.of("kind", kind.tagValue))
    )

private fun preflightCacheMeterCollisions(
    registry: MeterRegistry,
    contributors: List<ExposedKtorCacheContributor>,
) {
    val identities = contributors.mapTo(HashSet(contributors.size)) { it.component to it.kind.tagValue }
    val collision = registry.meters.any { meter ->
        meter.id.name in CACHE_METER_NAMES &&
                (meter.id.getTag("component") to meter.id.getTag("kind")) in identities
    }
    require(!collision) { "Cache metric installation rejected: reason=identity_collision." }
}

internal class ExposedKtorCacheMetricState {
    private val published = AtomicReference(PublishedCacheSample(0L, ExposedKtorCacheSample.UNAVAILABLE))

    fun claimGeneration(): Long {
        while (true) {
            val current = published.get()
            val next = PublishedCacheSample(Math.incrementExact(current.generation), current.sample)
            if (published.compareAndSet(current, next)) return next.generation
        }
    }

    fun publish(generation: Long, sample: ExposedKtorCacheSample): Boolean {
        while (true) {
            val current = published.get()
            if (current.generation != generation) return false
            if (published.compareAndSet(current, PublishedCacheSample(generation, sample))) return true
        }
    }

    fun currentSample(): ExposedKtorCacheSample = published.get().sample
}

private data class PublishedCacheSample(
    val generation: Long,
    val sample: ExposedKtorCacheSample,
)

private class CacheMeterOwnership(
    private val registry: MeterRegistry,
) {
    private val preexisting: Set<Meter> = registry.meters.toIdentitySet()
    private val ownedIdentities: MutableSet<Meter> = identitySet()
    private val ownedMeters = ArrayList<Meter>()

    fun claim(meter: Meter) {
        if (registry.meters.none { it === meter }) {
            throw CacheMeterInstallationFailure(CacheMeterFailureReason.REGISTRATION_FAILED)
        }
        if (preexisting.contains(meter) || !ownedIdentities.add(meter)) {
            throw CacheMeterInstallationFailure(CacheMeterFailureReason.IDENTITY_COLLISION)
        }
        ownedMeters += meter
    }

    fun rollback() {
        ownedMeters.asReversed().forEach { meter ->
            runCatching { registry.remove(meter) }
        }
    }
}

private enum class CacheMeterFailureReason { IDENTITY_COLLISION, REGISTRATION_FAILED }

private class CacheMeterInstallationFailure(
    val reason: CacheMeterFailureReason,
) : RuntimeException()

private fun Collection<Meter>.toIdentitySet(): Set<Meter> =
    identitySet<Meter>().also { it.addAll(this) }

private fun <T> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap())

private val CACHE_METER_INSTALL_LOCK = ReentrantLock()
private const val METERS_PER_CONTRIBUTOR = 8
private val CACHE_METER_NAMES = setOf(
    CACHE_READINESS_METER_NAME,
    CACHE_QUEUE_DEPTH_METER_NAME,
    CACHE_SNAPSHOT_PENDING_METER_NAME,
    CACHE_SNAPSHOT_DROPPED_METER_NAME,
    CACHE_SNAPSHOT_OBSERVER_FAILURES_METER_NAME,
)
