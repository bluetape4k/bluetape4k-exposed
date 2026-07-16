package io.bluetape4k.exposed.ktor

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import java.util.Collections

/** Finite cache-readiness status exposed by custom contributors. */
enum class ExposedKtorCacheStatus { UP, DOWN }

/**
 * A sanitized cache-readiness contributor with a fixed library-owned kind.
 *
 * Component names must match `[a-z][a-z0-9_-]{0,62}`. They are operational labels and must not contain
 * tenant, key, URL, endpoint, namespace, secret, or other data-bearing values. Repository report suppliers
 * must be side-effect-free O(1) reads of existing in-memory state; database, cache, network, file, blocking,
 * and other backend I/O are unsupported. Suspending R2DBC and custom suppliers must be non-blocking and
 * cooperate with cancellation. A supplier-thrown cancellation while the request remains active is sanitized as
 * `DOWN`; cancellation of the request context is rethrown. The library creates no isolation thread, dispatcher,
 * scope, or worker.
 */
class ExposedKtorCacheContributor private constructor(
    internal val component: String,
    internal val kind: ExposedKtorCacheKind,
    internal val probe: suspend () -> ExposedKtorCacheSample,
) {

    companion object {
        /**
         * Creates a contributor from one side-effect-free O(1) JDBC in-memory health-report read.
         *
         * [component] must match `[a-z][a-z0-9_-]{0,62}` and must not encode a tenant, key, URL, namespace,
         * endpoint, secret, or other data-bearing value. [report] must perform no blocking or backend I/O.
         * The library creates no isolation thread, dispatcher, or scope for the supplier.
         */
        fun jdbcRepository(
            component: String,
            report: () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = repository(component, ExposedKtorCacheKind.JDBC) { report() }

        /**
         * Creates a contributor from one side-effect-free O(1) R2DBC in-memory report read.
         *
         * [component] must match `[a-z][a-z0-9_-]{0,62}` and must not encode a tenant, key, URL, namespace,
         * endpoint, secret, or other data-bearing value. [report] must be non-blocking, perform no backend I/O,
         * and cooperate with coroutine cancellation. The library creates no isolation thread, dispatcher, or scope.
         */
        fun r2dbcRepository(
            component: String,
            report: suspend () -> CacheHealthReport,
        ): ExposedKtorCacheContributor = repository(component, ExposedKtorCacheKind.R2DBC, report)

        /**
         * Creates a contributor that takes one side-effect-free O(1) in-memory read-only snapshot of local counts.
         *
         * [component] must match `[a-z][a-z0-9_-]{0,62}` and must not encode a tenant, key, URL, namespace,
         * endpoint, secret, or other data-bearing value. Sampling performs no blocking or backend I/O, and the
         * library creates no isolation thread, dispatcher, or scope.
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
         * Creates a finite custom contributor; the probe cannot add fields, tags, or throwable details.
         *
         * [component] must match `[a-z][a-z0-9_-]{0,62}` and must not encode a tenant, key, URL, namespace,
         * endpoint, secret, or other data-bearing value. [probe] must be a side-effect-free O(1) in-memory read,
         * must be non-blocking, perform no backend I/O, and cooperate with coroutine cancellation. The library
         * creates no isolation thread, dispatcher, or scope.
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
 * Immutable cache-readiness configuration.
 *
 * The contributor list is defensively copied, must contain 1..16 entries, and must use unique component names
 * matching `[a-z][a-z0-9_-]{0,62}`. Component names must never encode tenant, key, URL, endpoint, namespace,
 * secret, or other data-bearing values. Supplier probes must be side-effect-free O(1) in-memory reads with no
 * backend I/O. Suspending suppliers must be non-blocking and cooperate with coroutine cancellation. The library
 * creates no isolation thread, dispatcher, or scope; see [ExposedKtorCacheContributor].
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
