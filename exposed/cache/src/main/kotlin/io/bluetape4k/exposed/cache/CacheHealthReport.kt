package io.bluetape4k.exposed.cache

import java.io.Serializable

/** Lifecycle state of the cache background worker. */
enum class CacheWorkerState {

    /** The current cache write mode does not use a background worker. */
    NOT_APPLICABLE,

    /** The worker is ready but has no work in progress. */
    IDLE,

    /** The worker has started and remains available after accepting a write. */
    RUNNING,

    /** The worker is finishing queued writes before stopping. */
    DRAINING,

    /** The worker encountered a terminal failure, entered cancellation, or could not stop cleanly. */
    FAILED,

    /** The worker has completed shutdown and will not process more work. */
    STOPPED,
}

/**
 * Snapshot of cache worker health and consistency state.
 *
 * @property mode configured cache write mode
 * @property queueDepth write-behind entries accepted but not yet observed as flushed
 * @property workerState current lifecycle state of the cache background worker
 * @property lastFlushError last non-cancellation flush failure, or null when the last flush succeeded
 */
data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val lastFlushError: Throwable?,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -1428853048381429257L
    }
}
