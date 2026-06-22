package io.bluetape4k.exposed.cache

import java.io.Serializable

/**
 * Snapshot of cache write-behind consistency state.
 *
 * @property mode configured cache write mode
 * @property queueDepth write-behind entries accepted but not yet observed as flushed
 * @property isFlushJobRunning whether the write-behind worker job is currently active
 * @property lastFlushError last non-cancellation flush failure, or null when the last flush succeeded
 */
data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val isFlushJobRunning: Boolean,
    val lastFlushError: Throwable?,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -1428853048381429258L
    }
}
