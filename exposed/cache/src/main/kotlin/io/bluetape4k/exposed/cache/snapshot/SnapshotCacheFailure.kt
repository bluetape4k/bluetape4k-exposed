package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded, sanitized buffer of snapshot-cache failures.
 *
 * Events are removed before observer delivery. An ordinary observer exception consumes the current event,
 * increments [observerFailureCount], and stops the drain. Fatal errors propagate on the caller thread.
 */
sealed interface SnapshotCacheFailureBuffer {
    /** Maximum number of retained failures. */
    val capacity: Int

    /** Current number of retained failures. */
    val size: Int

    /** Number of failures discarded because the buffer was full. */
    val droppedCount: Long

    /** Number of ordinary observer exceptions encountered while draining. */
    val observerFailureCount: Long

    /** Removes and returns the oldest retained failure, or `null` when empty. */
    fun poll(): SnapshotCacheFailure?

    /**
     * Delivers at most [maxElements] failures to [observer] on the caller thread.
     *
     * @return counts after this drain attempt
     */
    fun drainTo(
        observer: SnapshotCacheFailureObserver,
        maxElements: Int = capacity,
    ): SnapshotCacheDrainResult
}

/**
 * Summarizes one caller-thread failure-buffer drain.
 *
 * @property deliveredCount events delivered successfully
 * @property observerFailedCount events consumed by an ordinary observer exception
 * @property remainingCount events still retained after the drain
 * @property observerExceptionType sanitized observer exception class name, when delivery stopped
 */
data class SnapshotCacheDrainResult(
    val deliveredCount: Int,
    val observerFailedCount: Int,
    val remainingCount: Int,
    val observerExceptionType: String? = null,
) {
    init {
        require(deliveredCount >= 0) { "deliveredCount[$deliveredCount] must not be negative." }
        require(observerFailedCount >= 0) { "observerFailedCount[$observerFailedCount] must not be negative." }
        require(remainingCount >= 0) { "remainingCount[$remainingCount] must not be negative." }
        require(observerFailedCount <= 1) {
            "observerFailedCount[$observerFailedCount] must not exceed one drain stop."
        }
        observerExceptionType?.validateExceptionType()
    }
}

/** Creates a library-owned bounded failure buffer with non-blocking admission. */
fun snapshotCacheFailureBuffer(capacity: Int = 1_024): SnapshotCacheFailureBuffer {
    require(capacity > 0) { "capacity[$capacity] must be positive." }
    return BoundedSnapshotCacheFailureBuffer(capacity)
}

/** Receives one sanitized snapshot-cache failure on the caller thread. */
fun interface SnapshotCacheFailureObserver {
    /** Handles [failure] without access to cache values, identifiers, or exception payloads. */
    fun onFailure(failure: SnapshotCacheFailure)
}

/** Creates an observer that logs only the sanitized structural failure fields. */
fun loggingSnapshotCacheFailureObserver(): SnapshotCacheFailureObserver =
    SnapshotCacheFailureObserver { failure ->
        SnapshotCacheFailureLogging.log.warn {
            "Snapshot cache failure: storeId=${failure.storeId}, operation=${failure.operation}, " +
                    "outcome=${failure.outcome}, affectedCount=${failure.affectedCount}, " +
                    "exceptionType=${failure.exceptionType}"
        }
    }

/**
 * Sanitized structural description of one snapshot-cache failure.
 *
 * The record deliberately retains no cache identifier, value, snapshot, exception object, message, cause,
 * stack trace, SQL, URL, endpoint, or credential.
 */
data class SnapshotCacheFailure(
    val storeId: SnapshotStoreId,
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
) {
    init {
        require(affectedCount >= 0) { "affectedCount[$affectedCount] must not be negative." }
        exceptionType?.validateExceptionType()
    }
}

internal fun SnapshotCacheFailureBuffer.recordFailure(failure: SnapshotCacheFailure) {
    check(this is BoundedSnapshotCacheFailureBuffer) { "Unsupported snapshot cache failure buffer implementation." }
    offer(failure)
}

internal fun failureFromException(
    storeId: SnapshotStoreId,
    operation: SnapshotCacheOperation,
    affectedCount: Int,
    exception: Exception,
): SnapshotCacheFailure = SnapshotCacheFailure(
    storeId = storeId,
    operation = operation,
    outcome = SnapshotCacheOutcome.FAILED,
    affectedCount = affectedCount,
    exceptionType = exception.javaClass.name,
)

private class BoundedSnapshotCacheFailureBuffer(
    override val capacity: Int,
) : SnapshotCacheFailureBuffer {
    private val failures = ArrayBlockingQueue<SnapshotCacheFailure>(capacity)
    private val dropped = AtomicLong()
    private val observerFailures = AtomicLong()

    override val size: Int
        get() = failures.size

    override val droppedCount: Long
        get() = dropped.get()

    override val observerFailureCount: Long
        get() = observerFailures.get()

    fun offer(failure: SnapshotCacheFailure) {
        if (!failures.offer(failure)) {
            dropped.incrementAndGet()
        }
    }

    override fun poll(): SnapshotCacheFailure? = failures.poll()

    override fun drainTo(
        observer: SnapshotCacheFailureObserver,
        maxElements: Int,
    ): SnapshotCacheDrainResult {
        require(maxElements >= 0) { "maxElements[$maxElements] must not be negative." }
        var delivered = 0
        repeat(maxElements) {
            val failure = failures.poll() ?: return SnapshotCacheDrainResult(delivered, 0, size)
            try {
                observer.onFailure(failure)
                delivered++
            } catch (exception: Exception) {
                observerFailures.incrementAndGet()
                return SnapshotCacheDrainResult(
                    deliveredCount = delivered,
                    observerFailedCount = 1,
                    remainingCount = size,
                    observerExceptionType = exception.javaClass.name,
                )
            }
        }
        return SnapshotCacheDrainResult(delivered, 0, size)
    }
}

private object SnapshotCacheFailureLogging : KLogging()

private fun String.validateExceptionType() {
    require(isNotBlank()) { "exceptionType must not be blank when set." }
    require(length <= MAX_EXCEPTION_TYPE_LENGTH) {
        "exceptionType length[$length] must not exceed $MAX_EXCEPTION_TYPE_LENGTH."
    }
    require(EXCEPTION_TYPE_PATTERN.matches(this)) {
        "exceptionType must be a fully qualified JVM class name."
    }
}

private const val MAX_EXCEPTION_TYPE_LENGTH: Int = 512
private val EXCEPTION_TYPE_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
