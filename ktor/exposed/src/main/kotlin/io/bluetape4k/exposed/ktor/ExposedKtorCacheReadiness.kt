package io.bluetape4k.exposed.ktor

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import java.util.Collections

/** custom contributor가 노출하는 유한 cache-readiness 상태입니다. */
enum class ExposedKtorCacheStatus { UP, DOWN }

/**
 * library가 소유한 고정 kind를 사용하는 sanitized cache-readiness contributor입니다.
 *
 * component 이름은 `[a-z][a-z0-9_-]{0,62}`와 일치해야 합니다. 운영 label이므로 tenant, key, URL, endpoint,
 * namespace, secret 등 데이터를 담는 값을 포함해서는 안 됩니다. repository report supplier는 기존 in-memory
 * 상태를 읽는 side-effect-free O(1) 작업이어야 하며 database, cache, network, file, blocking 등 backend I/O는
 * 지원하지 않습니다. suspending R2DBC와 custom supplier는 non-blocking이어야 하고 cancellation에 협력해야 합니다.
 * request가 활성인 동안 supplier가 던진 cancellation은 `DOWN`으로 정제하며 request context cancellation은 다시 던집니다.
 * library는 isolation thread, dispatcher, scope, worker를 생성하지 않습니다.
 */
class ExposedKtorCacheContributor private constructor(
    internal val component: String,
    internal val kind: ExposedKtorCacheKind,
    internal val probe: suspend () -> ExposedKtorCacheSample,
) {

    companion object {
        /**
         * side-effect-free O(1) JDBC in-memory health report 조회 하나로 contributor를 생성합니다.
         *
         * [component]는 `[a-z][a-z0-9_-]{0,62}`와 일치하고 tenant, key, URL, namespace, endpoint, secret 등
         * 데이터를 담는 값을 인코딩하지 않아야 합니다. [report]는 blocking이나 backend I/O를 수행해서는 안 됩니다.
         * library는 supplier용 isolation thread, dispatcher, scope를 생성하지 않습니다.
         */
        fun jdbcRepository(
            component: String,
            report: () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = repository(component, ExposedKtorCacheKind.JDBC) { report() }

        /**
         * side-effect-free O(1) R2DBC in-memory report 조회 하나로 contributor를 생성합니다.
         *
         * [component]는 `[a-z][a-z0-9_-]{0,62}`와 일치하고 데이터를 담는 값을 인코딩하지 않아야 합니다.
         * [report]는 non-blocking이고 backend I/O 없이 coroutine cancellation에 협력해야 합니다.
         * library는 isolation thread, dispatcher, scope를 생성하지 않습니다.
         */
        fun r2dbcRepository(
            component: String,
            report: suspend () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = repository(component, ExposedKtorCacheKind.R2DBC, report)

        /**
         * local count의 side-effect-free O(1) in-memory read-only snapshot 하나를 취하는 contributor를 생성합니다.
         *
         * [component]는 지정된 형식과 일치하고 데이터를 담는 값을 인코딩하지 않아야 합니다.
         * sampling은 blocking이나 backend I/O를 수행하지 않으며 library는 isolation thread, dispatcher, scope를 생성하지 않습니다.
         */
        fun snapshot(
            component: String,
            buffer: SnapshotCacheFailureBuffer,
        ): ExposedKtorCacheContributor = create(component, ExposedKtorCacheKind.SNAPSHOT) {
            ExposedKtorCacheSample.snapshot(
                pending = buffer.size.toLong(),
                dropped = buffer.droppedCount,
                observerFailures = buffer.observerFailureCount,
            )
        }

        /**
         * 유한 custom contributor를 생성하며 probe는 field, tag, throwable detail을 추가할 수 없습니다.
         *
         * [component]는 지정된 형식과 일치하고 데이터를 담는 값을 인코딩하지 않아야 합니다. [probe]는 side-effect-free
         * O(1) in-memory 조회이면서 non-blocking이고 backend I/O 없이 coroutine cancellation에 협력해야 합니다.
         * library는 isolation thread, dispatcher, scope를 생성하지 않습니다.
         */
        fun custom(
            component: String,
            probe: suspend () -> ExposedKtorCacheStatus,
        ): ExposedKtorCacheContributor = create(component, ExposedKtorCacheKind.CUSTOM) {
            ExposedKtorCacheSample.custom(probe())
        }

        private fun repository(
            component: String,
            kind: ExposedKtorCacheKind,
            report: suspend () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = create(component, kind) {
            ExposedKtorCacheSample.repository(report())
        }

        private fun create(
            component: String,
            kind: ExposedKtorCacheKind,
            probe: suspend () -> ExposedKtorCacheSample,
        ): ExposedKtorCacheContributor {
            validateComponent(component)
            return ExposedKtorCacheContributor(component, kind, probe)
        }
    }
}

/**
 * 불변 cache-readiness 설정입니다.
 *
 * contributor 목록은 defensive copy하며 1..16개 항목과 `[a-z][a-z0-9_-]{0,62}`에 맞는 고유 component 이름을
 * 사용해야 합니다. component 이름은 데이터를 담는 값을 인코딩해서는 안 됩니다. supplier probe는 backend I/O 없는
 * side-effect-free O(1) in-memory 조회여야 합니다. suspending supplier는 non-blocking이고 coroutine cancellation에
 * 협력해야 합니다. library는 isolation thread, dispatcher, scope를 생성하지 않습니다. [ExposedKtorCacheContributor]를 참고합니다.
 */
class ExposedKtorCacheReadinessConfig(
    contributors: List<ExposedKtorCacheContributor>,
) {
    val contributors: List<ExposedKtorCacheContributor> =
        Collections.unmodifiableList(ArrayList(contributors))

    init {
        require(this.contributors.isNotEmpty()) { "Invalid cache contributors: reason=empty." }
        require(this.contributors.size <= MAX_CACHE_CONTRIBUTORS) {
            "Invalid cache contributors: count=${this.contributors.size}, reason=limit_exceeded."
        }
        val firstPositions = HashMap<String, Int>(this.contributors.size)
        this.contributors.forEachIndexed { index, contributor ->
            validateComponent(contributor.component, index)
            val first = firstPositions.putIfAbsent(contributor.component, index)
            require(first == null) {
                "Invalid cache contributor at index=$index: duplicateOf=$first, reason=duplicate_component."
            }
        }
    }
}

internal enum class ExposedKtorCacheKind(val tagValue: String) {
    JDBC("jdbc"),
    R2DBC("r2dbc"),
    SNAPSHOT("snapshot"),
    CUSTOM("custom"),
}

internal class ExposedKtorCacheSample private constructor(
    val status: ExposedKtorCacheStatus,
    val queueDepth: Double,
    val snapshotPending: Double,
    val snapshotDropped: Double,
    val snapshotObserverFailures: Double,
) {
    init {
        validateMeasurement(queueDepth, "queue_depth")
        validateMeasurement(snapshotPending, "snapshot_pending")
        validateMeasurement(snapshotDropped, "snapshot_dropped")
        validateMeasurement(snapshotObserverFailures, "snapshot_observer_failures")
    }

    companion object {
        val UNAVAILABLE: ExposedKtorCacheSample = ExposedKtorCacheSample(
            ExposedKtorCacheStatus.DOWN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
        )

        fun repository(report: CacheHealthReport): ExposedKtorCacheSample {
            require(report.queueDepth >= 0) { "Invalid cache report: reason=negative_queue_depth." }
            val status = if (report.lastFlushError != null) {
                ExposedKtorCacheStatus.DOWN
            } else {
                when (report.workerState) {
                    CacheWorkerState.NOT_APPLICABLE,
                    CacheWorkerState.IDLE,
                    CacheWorkerState.RUNNING,
                    -> ExposedKtorCacheStatus.UP

                    CacheWorkerState.DRAINING,
                    CacheWorkerState.FAILED,
                    CacheWorkerState.STOPPED,
                    -> ExposedKtorCacheStatus.DOWN
                }
            }
            return ExposedKtorCacheSample(
                status,
                report.queueDepth.toDouble(),
                Double.NaN,
                Double.NaN,
                Double.NaN,
            )
        }

        fun snapshot(pending: Long, dropped: Long, observerFailures: Long): ExposedKtorCacheSample {
            require(pending >= 0) { "Invalid snapshot sample: reason=negative_pending." }
            require(dropped >= 0) { "Invalid snapshot sample: reason=negative_dropped." }
            require(observerFailures >= 0) { "Invalid snapshot sample: reason=negative_observer_failures." }
            return ExposedKtorCacheSample(
                ExposedKtorCacheStatus.UP,
                Double.NaN,
                pending.toDouble(),
                dropped.toDouble(),
                observerFailures.toDouble(),
            )
        }

        fun custom(status: ExposedKtorCacheStatus): ExposedKtorCacheSample = ExposedKtorCacheSample(
            status,
            Double.NaN,
            Double.NaN,
            Double.NaN,
            Double.NaN,
        )
    }
}

private val COMPONENT_PATTERN = Regex("[a-z][a-z0-9_-]{0,62}")
internal const val MAX_CACHE_CONTRIBUTORS: Int = 16

private fun validateComponent(component: String, index: Int? = null) {
    require(COMPONENT_PATTERN.matches(component)) {
        val location = index?.let { " at index=$it" }.orEmpty()
        "Invalid cache component$location: length=${component.length}, reason=unsafe_component."
    }
}

private fun validateMeasurement(value: Double, reason: String) {
    require(value.isNaN() || value >= 0.0) { "Invalid cache sample: reason=negative_$reason." }
}
