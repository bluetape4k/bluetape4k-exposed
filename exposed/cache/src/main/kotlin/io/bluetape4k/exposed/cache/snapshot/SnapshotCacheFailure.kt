@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot-cache failure를 정제된 형태로 보관하는 bounded buffer입니다.
 *
 * event는 observer 전달 전에 buffer에서 제거됩니다. 일반 observer 예외는 현재 event를 소비하고
 * [observerFailureCount]를 증가시킨 뒤 drain을 중단합니다. 치명적 error는 caller thread로 전파됩니다.
 */
sealed interface SnapshotCacheFailureBuffer {
    /** 보관 가능한 failure event 최대 개수입니다. */
    val capacity: Int

    /** 현재 보관 중인 failure event 개수입니다. */
    val size: Int

    /** buffer 포화로 버려진 failure event 누적 개수입니다. */
    val droppedCount: Long

    /** drain 중 관찰된 일반 observer 예외 누적 개수입니다. */
    val observerFailureCount: Long

    /** 가장 오래된 failure를 제거해 반환하거나, 비어 있으면 `null`을 반환합니다. */
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
 * caller thread에서 수행한 failure-buffer drain 결과를 요약합니다.
 *
 * @property deliveredCount observer에 성공적으로 전달된 event 개수입니다.
 * @property observerFailedCount 일반 observer 예외 때문에 소비되고 전달 실패한 event 개수입니다. 한 번 실패하면
 * drain을 중단하므로 0 또는 1이어야 합니다.
 * @property remainingCount drain 이후 buffer에 남아 있는 event 개수입니다.
 * @property observerExceptionType delivery 중단 원인이 된 observer 예외의 정제된 class name입니다.
 */
data class SnapshotCacheDrainResult(
    /** observer에 성공적으로 전달된 failure event 개수입니다. */
    val deliveredCount: Int,
    /** observer 예외로 소비된 failure event 개수입니다. */
    val observerFailedCount: Int,
    /** drain 이후 buffer에 남은 failure event 개수입니다. */
    val remainingCount: Int,
    /** observer 예외의 안전하게 정제된 JVM class name입니다. */
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
 * 하나의 snapshot-cache failure를 정제된 구조 정보로 표현합니다.
 *
 * 이 record는 cache identifier, value, snapshot, exception 객체, message, cause, stack trace, SQL, URL, endpoint,
 * credential을 의도적으로 보관하지 않습니다.
 *
 * @property affectedCount 영향을 받은 input 개수입니다. 측정값으로만 사용하고 metrics tag로 사용하지 않아야 합니다.
 * @property exceptionType 안전하게 정제된 bounded JVM exception class name입니다. 없거나 unsafe이면 `null`입니다.
 */
data class SnapshotCacheFailure(
    /** failure가 발생한 logical snapshot store identity입니다. */
    val storeId: SnapshotStoreId,
    /** 실패하거나 비정상 outcome을 반환한 cache operation입니다. */
    val operation: SnapshotCacheOperation,
    /** operation의 구조적 outcome입니다. */
    val outcome: SnapshotCacheOutcome,
    /** 이 failure event가 대표하는 input 개수입니다. high-cardinality tag로 쓰지 않습니다. */
    val affectedCount: Int,
    /** 정제된 exception class metadata입니다. 민감한 message/cause/stack trace는 포함하지 않습니다. */
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
    exceptionType = sanitizeSnapshotCacheExceptionType(exception.javaClass.name),
)

/** Returns [exceptionType] only when it is safe bounded JVM class-name metadata. */
@InternalSnapshotCacheApi
fun sanitizeSnapshotCacheExceptionType(exceptionType: String): String? =
    exceptionType.takeIf { it.length <= MAX_EXCEPTION_TYPE_LENGTH && it.isJvmClassName() }

/** Rejects exception-type metadata that is not a safe bounded JVM class name. */
@InternalSnapshotCacheApi
fun requireSafeSnapshotCacheExceptionType(exceptionType: String) {
    exceptionType.validateExceptionType()
}

internal fun sanitizeExceptionType(exceptionType: String): String? =
    sanitizeSnapshotCacheExceptionType(exceptionType)

private class BoundedSnapshotCacheFailureBuffer(
    /** buffer가 보관할 수 있는 failure event 최대 개수입니다. */
    override val capacity: Int,
) : SnapshotCacheFailureBuffer {
    /** non-blocking admission을 제공하는 bounded failure queue입니다. */
    private val failures = ArrayBlockingQueue<SnapshotCacheFailure>(capacity)
    /** capacity 초과로 drop된 failure event 누적 개수입니다. */
    private val dropped = AtomicLong()
    /** observer 예외가 발생한 drain 시도 누적 개수입니다. */
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
                    observerExceptionType = sanitizeSnapshotCacheExceptionType(exception.javaClass.name),
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
    require(isJvmClassName()) {
        "exceptionType must be a fully qualified JVM class name."
    }
}

private const val MAX_EXCEPTION_TYPE_LENGTH: Int = 512

private fun String.isJvmClassName(): Boolean = split('.').all { it.isJvmIdentifier() }

private fun String.isJvmIdentifier(): Boolean {
    if (isEmpty()) return false
    var offset = 0
    val first = codePointAt(offset)
    if (!Character.isJavaIdentifierStart(first) || first.isUnsafeJvmIdentifierCodePoint()) return false
    offset += Character.charCount(first)
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (!Character.isJavaIdentifierPart(codePoint) || codePoint.isUnsafeJvmIdentifierCodePoint()) return false
        offset += Character.charCount(codePoint)
    }
    return true
}

private fun Int.isUnsafeJvmIdentifierCodePoint(): Boolean {
    val type = Character.getType(this)
    return Character.isIdentifierIgnorable(this) ||
            type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt()
}
