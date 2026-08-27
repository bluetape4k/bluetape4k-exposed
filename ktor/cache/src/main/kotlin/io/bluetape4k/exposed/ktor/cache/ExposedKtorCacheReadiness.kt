package io.bluetape4k.exposed.ktor.cache

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.ktor.core.ExposedKtorCooperativeReadinessProbe
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome
import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessProbe
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration

/** custom observer가 제공하는 유한 cache readiness 상태입니다. */
enum class ExposedKtorCacheStatus {
    UP,
    DOWN,
}

/**
 * 정제된 호출자 소유 cache readiness contributor입니다.
 *
 * supplier는 side-effect-free O(1) 메모리 조회여야 합니다. database, network,
 * blocking, dispatcher 작업을 수행하면 안 되며 library는 supplier를 위한 worker나
 * 격리 scope를 만들지 않습니다.
 */
class ExposedKtorCacheContributor private constructor(
    internal val component: String,
    private val kind: Kind,
    private val sampleSupplier: suspend () -> ExposedKtorCacheSample,
) {
    internal suspend fun sample(): ExposedKtorCacheSample = sampleSupplier()

    companion object {
        /** O(1) JDBC cache health 상태에서 contributor를 생성합니다. */
        fun jdbcRepository(
            component: String,
            report: () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = create(component, Kind.JDBC) {
            ExposedKtorCacheSample.fromReport(report())
        }

        /** O(1) suspending R2DBC cache health 상태에서 contributor를 생성합니다. */
        fun r2dbcRepository(
            component: String,
            report: suspend () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = create(component, Kind.R2DBC) {
            ExposedKtorCacheSample.fromReport(report())
        }

        /** O(1) 실패 버퍼 상태 관찰에서 contributor를 생성합니다. */
        fun snapshot(
            component: String,
            buffer: SnapshotCacheFailureBuffer,
        ): ExposedKtorCacheContributor = create(component, Kind.SNAPSHOT) {
            ExposedKtorCacheSample(
                status = ExposedKtorCacheStatus.UP,
                queueDepth = Double.NaN,
                snapshotPending = buffer.size.toDouble(),
                snapshotDropped = buffer.droppedCount.toDouble(),
                snapshotObserverFailures = buffer.observerFailureCount.toDouble(),
            )
        }

        /** 유한한 custom status observer에서 contributor를 생성합니다. */
        fun custom(
            component: String,
            probe: suspend () -> ExposedKtorCacheStatus,
        ): ExposedKtorCacheContributor = create(component, Kind.CUSTOM) {
            ExposedKtorCacheSample(
                status = probe(),
                queueDepth = Double.NaN,
                snapshotPending = Double.NaN,
                snapshotDropped = Double.NaN,
                snapshotObserverFailures = Double.NaN,
            )
        }

        private fun create(
            component: String,
            kind: Kind,
            supplier: suspend () -> ExposedKtorCacheSample,
        ): ExposedKtorCacheContributor {
            validateCacheComponent(component)
            return ExposedKtorCacheContributor(component, kind, supplier)
        }
    }

    private enum class Kind {
        JDBC,
        R2DBC,
        SNAPSHOT,
        CUSTOM,
    }
}

/** 불변 cache contributor 등록입니다. */
class ExposedKtorCacheReadinessConfig(
    contributors: List<ExposedKtorCacheContributor>,
) {
    val contributors: List<ExposedKtorCacheContributor> =
        Collections.unmodifiableList(ArrayList(contributors))

    init {
        require(this.contributors.isNotEmpty()) { "Cache contributors must not be empty." }
        require(this.contributors.size <= MAX_CACHE_CONTRIBUTORS) {
            "Cache contributors must contain at most $MAX_CACHE_CONTRIBUTORS entries."
        }
        val components = HashSet<String>(this.contributors.size)
        this.contributors.forEachIndexed { index, contributor ->
            validateCacheComponent(contributor.component, index)
            require(components.add(contributor.component)) {
                "Cache contributor at index=$index has duplicate component."
            }
        }
    }
}

/** cache contributor를 core 소유 cooperative readiness probe로 변환합니다. */
fun exposedKtorCacheReadinessProbes(
    config: ExposedKtorCacheReadinessConfig,
): List<ExposedKtorReadinessProbe> = config.contributors.map { contributor ->
    CacheReadinessProbe(contributor)
}

private class CacheReadinessProbe(
    private val contributor: ExposedKtorCacheContributor,
) : ExposedKtorCooperativeReadinessProbe {
    override val component: String = contributor.component
    override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.CACHE

    @Suppress("TooGenericExceptionCaught")
    override suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome {
        require(timeout.isFinite() && timeout.isPositive()) { "timeout must be finite and positive." }
        return try {
            contributor.sample().status.toReadinessOutcome()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            ExposedKtorReadinessOutcome.DOWN
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            ExposedKtorReadinessOutcome.DOWN
        }
    }
}

internal class ExposedKtorCacheSample(
    val status: ExposedKtorCacheStatus,
    val queueDepth: Double,
    val snapshotPending: Double,
    val snapshotDropped: Double,
    val snapshotObserverFailures: Double,
) {
    init {
        listOf(queueDepth, snapshotPending, snapshotDropped, snapshotObserverFailures).forEach {
            require(it.isNaN() || it >= 0.0) { "Cache measurements must be non-negative." }
        }
    }

    companion object {
        fun fromReport(report: CacheHealthReport): ExposedKtorCacheSample {
            require(report.queueDepth >= 0) { "Cache queue depth must be non-negative." }
            val status = when {
                report.lastFlushError != null -> ExposedKtorCacheStatus.DOWN
                report.workerState in setOf(
                    CacheWorkerState.NOT_APPLICABLE,
                    CacheWorkerState.IDLE,
                    CacheWorkerState.RUNNING,
                ) -> ExposedKtorCacheStatus.UP
                else -> ExposedKtorCacheStatus.DOWN
            }
            return ExposedKtorCacheSample(
                status = status,
                queueDepth = report.queueDepth.toDouble(),
                snapshotPending = Double.NaN,
                snapshotDropped = Double.NaN,
                snapshotObserverFailures = Double.NaN,
            )
        }
    }
}

private fun ExposedKtorCacheStatus.toReadinessOutcome(): ExposedKtorReadinessOutcome = when (this) {
    ExposedKtorCacheStatus.UP -> ExposedKtorReadinessOutcome.UP
    ExposedKtorCacheStatus.DOWN -> ExposedKtorReadinessOutcome.DOWN
}

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_.-]{0,62}")
private const val MAX_CACHE_CONTRIBUTORS = 16

private fun validateCacheComponent(component: String, index: Int? = null) {
    require(COMPONENT_PATTERN.matches(component)) {
        val location = index?.let { " at index=$it" }.orEmpty()
        "Invalid cache readiness component$location: reason=unsafe_component."
    }
}
