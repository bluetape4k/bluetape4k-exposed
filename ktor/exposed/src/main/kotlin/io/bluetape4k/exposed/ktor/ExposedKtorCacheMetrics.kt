package io.bluetape4k.exposed.ktor

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
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
 * 설치 실패 시 현재 시도에서 claim된 meter만 역순으로 best-effort 제거합니다. 제거 실패가 있으면
 * 안정적인 설치 실패 원인과 잔여 meter 수를 담은 [CacheMeterRollbackDiagnostic]을 suppressed
 * exception으로 보존하며, registry가 일부 오염된 경우에도 후속 재설치가 결정적으로 관찰되도록 합니다.
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
            val rollbackDiagnostic = ownership.rollback()
            if (failure is CancellationException || failure is Error) {
                rollbackDiagnostic?.let(failure::addSuppressed)
                throw failure
            }

            val primaryFailure = failure as? CacheMeterInstallationFailure
                ?: CacheMeterInstallationFailure(
                    reason = CacheMeterFailureReason.REGISTRATION_FAILED,
                    primaryFailureType = failure.javaClass.name,
                )
            val installationFailure = when (primaryFailure.reason) {
                CacheMeterFailureReason.IDENTITY_COLLISION ->
                    IllegalArgumentException("Cache metric installation rejected: reason=identity_collision.")

                CacheMeterFailureReason.REGISTRATION_FAILED ->
                    IllegalStateException("Cache metric installation failed: reason=registration_failed.")
            }.also { it.initCause(primaryFailure) }
            rollbackDiagnostic?.let(installationFailure::addSuppressed)
            throw installationFailure
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

    @Suppress("TooGenericExceptionCaught")
    fun rollback(): CacheMeterRollbackDiagnostic? {
        var removed = 0
        var notFound = 0
        val failures = ArrayList<CacheMeterRollbackFailure>()
        ownedMeters.asReversed().forEach { meter ->
            try {
                if (registry.remove(meter) == null) {
                    notFound++
                } else {
                    removed++
                }
            } catch (failure: RuntimeException) {
                failures += CacheMeterRollbackFailure(meter, failure)
            }
        }
        if (failures.isEmpty() && notFound == 0) return null

        val residual = ownedMeters.count { owned -> registry.meters.any { it === owned } }
        return CacheMeterRollbackDiagnostic(
            attempted = ownedMeters.size,
            removed = removed,
            notFound = notFound,
            failed = failures.size,
            residual = residual,
            failures = failures,
        )
    }
}

/** 설치 실패를 외부에 노출할 때 사용할 안정적인 분류입니다. */
internal enum class CacheMeterFailureReason { IDENTITY_COLLISION, REGISTRATION_FAILED }

/** registry의 원본 예외 메시지나 내부 상태를 노출하지 않는 설치 실패 원인입니다. */
internal class CacheMeterInstallationFailure(
    val reason: CacheMeterFailureReason,
    val primaryFailureType: String? = null,
) : RuntimeException()

/** 설치 rollback 결과를 secret 없이 보존하는 구조화된 진단입니다. */
internal class CacheMeterRollbackDiagnostic(
    val attempted: Int,
    val removed: Int,
    val notFound: Int,
    val failed: Int,
    val residual: Int,
    failures: List<CacheMeterRollbackFailure>,
) : RuntimeException(
    "Cache metric rollback failed: " +
            "attempted=$attempted,removed=$removed,notFound=$notFound,failed=$failed,residual=$residual."
) {
    init {
        failures.forEach(::addSuppressed)
    }
}

/** 개별 meter 제거 실패를 secret 없이 보존하는 suppressed 진단입니다. */
internal class CacheMeterRollbackFailure(
    meter: Meter,
    failure: RuntimeException,
) : RuntimeException(
    "Cache metric rollback remove failed: " +
            "meter=${meter.id.name},component=${meter.id.getTag("component") ?: "unknown"}," +
            "kind=${meter.id.getTag("kind") ?: "unknown"},reason=${failure.javaClass.name}."
)

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
