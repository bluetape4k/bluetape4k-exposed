package io.bluetape4k.exposed.cache.internal

import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Write-behind 큐의 adapter-independent admission/lifecycle 상한입니다.
 *
 * 이 값은 public 설정 API가 아닙니다. 각 Caffeine adapter와
 * [io.bluetape4k.exposed.cache.LocalCacheConfig]가 동일한 fail-closed 상한을
 * 사용하도록 이 파일에만 선언합니다.
 */
internal const val MAX_WRITE_BEHIND_QUEUE_CAPACITY: Int = 100_000

/** Adapter-owned bounded retry policy constants shared by the conformance contract. */
internal const val MAX_FLUSH_RETRY_ATTEMPTS: Int = 8
internal const val INITIAL_FLUSH_RETRY_BACKOFF_MILLIS: Long = 10L
internal const val MAX_FLUSH_RETRY_BACKOFF_MILLIS: Long = 1_000L

internal fun flushRetryBackoffMillis(failedAttempt: Int): Long {
    require(failedAttempt >= 1) { "failedAttempt must be positive" }
    val exponent = (failedAttempt - 1).coerceAtMost(7)
    return (INITIAL_FLUSH_RETRY_BACKOFF_MILLIS * (1L shl exponent))
        .coerceAtMost(MAX_FLUSH_RETRY_BACKOFF_MILLIS)
}

/** Worker가 flush를 포기한 이유를 안정적인 상태 값으로 표현합니다. */
internal enum class WriteBehindFailureKind {
    FLUSH,
    WORKER,
    CLOSE_TIMEOUT,
    CLOSE_INTERRUPTED,
}

/** Worker가 close 이후 관찰한 terminal 결과입니다. */
internal enum class WriteBehindWorkerCompletion {
    DRAINED,
    CANCELLED,
    FAILED,
}

/** Coordinator가 adapter에 제공하는 raw 예외 없는 lifecycle snapshot입니다. */
internal data class CoordinatorSnapshot(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val failureKind: WriteBehindFailureKind?,
)

/**
 * 한 번의 enqueue 시도에 대한 내부 capability입니다.
 *
 * Token은 coordinator가 발급한 인스턴스 identity로만 유효합니다. adapter가
 * 임의의 token을 만들거나 이미 정산된 token을 재사용하면 거부됩니다.
 */
internal class AdmissionToken internal constructor(
    internal val owner: WriteBehindCoordinator,
    internal val sequence: Long,
)

/** close owner만 completion을 발행할 수 있도록 하는 내부 lease입니다. */
internal sealed interface CloseLease {

    class Owner private constructor(internal val token: Any): CloseLease {
        internal companion object {
            fun mint(token: Any): Owner = Owner(token)
        }
    }

    data object Follower: CloseLease
}

internal enum class CloseCompletionKind {
    COMPLETED,
    TIMEOUT,
    INTERRUPTED,
    FAILED,
}

internal data class CloseCompletion(
    val kind: CloseCompletionKind,
    val workerState: CacheWorkerState,
    val queueDepth: Int,
)

/**
 * 세 Caffeine adapter가 공유하는 write-behind logical state machine입니다.
 *
 * Coordinator는 channel, scope, database, cache key/entity, blocking wait와
 * Throwable을 보유하지 않습니다. 실제 I/O와 close wait는 adapter가 소유하고,
 * 이 클래스는 admission token과 immutable lifecycle state만 선형화합니다.
 */
internal class WriteBehindCoordinator(
    mode: CacheWriteMode,
) {

    private enum class Lifecycle {
        OPEN,
        DRAINING,
        STOPPED,
        FAILED,
    }

    private enum class TokenState {
        RESERVED,
        ENQUEUED,
        SETTLED,
    }

    private val lock = Any()
    private val nextToken = AtomicLong(0L)
    private val tokens = IdentityHashMap<AdmissionToken, TokenState>()

    private val mode = mode
    private var lifecycle = if (mode == CacheWriteMode.WRITE_BEHIND) Lifecycle.OPEN else Lifecycle.STOPPED
    private var queueDepth = 0
    private var workerState =
        if (mode == CacheWriteMode.WRITE_BEHIND) CacheWorkerState.IDLE else CacheWorkerState.NOT_APPLICABLE
    private var failureKind: WriteBehindFailureKind? = null
    private var activeOwner: CloseLease.Owner? = null
    private var completionPublished = false

    /** OPEN 상태에서만 admission capability를 예약합니다. */
    fun reserveAdmission(): AdmissionToken = synchronized(lock) {
        check(mode == CacheWriteMode.WRITE_BEHIND) {
            "Write-behind admission is not applicable for mode=$mode"
        }
        check(lifecycle == Lifecycle.OPEN) {
            "Write-behind worker is not accepting admissions in terminal state=$workerState"
        }
        AdmissionToken(this, nextToken.incrementAndGet()).also { tokens[it] = TokenState.RESERVED }
    }

    /**
     * enqueue 결과를 정확히 한 번 정산합니다.
     *
     * accepted=true일 때만 queue depth가 증가합니다. close gate가 먼저 닫힌
     * 경우 accepted handoff를 선형화하지 않고 token을 rejected로 정산합니다.
     */
    fun settleEnqueue(token: AdmissionToken, accepted: Boolean): Boolean {
        synchronized(lock) {
            val state = tokens[token] ?: error("Unknown write-behind admission token")
            check(token.owner === this) { "Admission token belongs to another coordinator" }
            check(state == TokenState.RESERVED || state == TokenState.ENQUEUED) {
                "Write-behind admission token was already settled"
            }
            check(!accepted || state == TokenState.ENQUEUED) {
                "Accepted write-behind admission must be marked enqueued first"
            }

            // A close gate may win after the channel handoff but before the
            // caller publishes acceptance. Settle that token as rejected so
            // the adapter can roll back without a second state mutation.
            if (accepted && lifecycle != Lifecycle.OPEN) {
                tokens.remove(token)
                return false
            }

            tokens.remove(token)
            if (accepted) {
                check(queueDepth < MAX_WRITE_BEHIND_QUEUE_CAPACITY) {
                    "Write-behind queue depth exceeded the canonical capacity"
                }
                queueDepth += 1
                if (workerState == CacheWorkerState.IDLE) workerState = CacheWorkerState.RUNNING
            }
            return accepted
        }
    }

    /** adapter가 send 성공 전에 worker가 관찰하지 않도록 token을 명시적으로 표시합니다. */
    internal fun markEnqueued(token: AdmissionToken) {
        synchronized(lock) {
            val state = tokens[token] ?: error("Unknown write-behind admission token")
            check(token.owner === this) { "Admission token belongs to another coordinator" }
            check(state == TokenState.RESERVED) { "Write-behind admission token was already enqueued" }
            tokens[token] = TokenState.ENQUEUED
        }
    }

    /** 성공한 flush만 depth를 감소시키고 이전 flush failure를 회복합니다. */
    fun onFlushSucceeded(count: Int) {
        synchronized(lock) {
            if (lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.FAILED) return
            require(count >= 0) { "Flush count must not be negative" }
            require(count <= queueDepth) {
                "Flush count[$count] exceeds queue depth[$queueDepth]"
            }
            queueDepth -= count
            failureKind = null
        }
    }

    /** 일반 flush 실패는 retained batch/depth를 보존합니다. */
    fun onFlushFailed() {
        synchronized(lock) {
            if (lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.FAILED) return
            failureKind = WriteBehindFailureKind.FLUSH
        }
    }

    /** close/worker terminal failure를 stable kind으로 기록합니다. */
    fun onCloseFailed(kind: WriteBehindFailureKind) {
        require(kind == WriteBehindFailureKind.WORKER ||
            kind == WriteBehindFailureKind.CLOSE_TIMEOUT ||
            kind == WriteBehindFailureKind.CLOSE_INTERRUPTED) {
            "Close failure kind must describe worker or close termination: $kind"
        }
        synchronized(lock) {
            if (lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.FAILED) return
            failureKind = kind
            lifecycle = Lifecycle.FAILED
            workerState = CacheWorkerState.FAILED
        }
    }

    /** worker terminal callback을 한 번 선형화합니다. */
    fun onWorkerCompleted(completion: WriteBehindWorkerCompletion) {
        synchronized(lock) {
            if (lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.FAILED) return
            when (completion) {
                WriteBehindWorkerCompletion.DRAINED -> {
                    if (queueDepth == 0) {
                        // A close owner still has to publish its completion after the
                        // adapter has finished cache invalidation and scope cleanup.
                        // Keep DRAINING until that publication so a late worker callback
                        // cannot race the owner/follower cleanup barrier.
                        if (lifecycle == Lifecycle.OPEN) {
                            workerState = CacheWorkerState.STOPPED
                            lifecycle = Lifecycle.STOPPED
                        } else if (lifecycle == Lifecycle.DRAINING) {
                            workerState = CacheWorkerState.DRAINING
                        }
                    } else {
                        workerState = CacheWorkerState.FAILED
                        lifecycle = Lifecycle.FAILED
                        failureKind = WriteBehindFailureKind.WORKER
                    }
                }

                WriteBehindWorkerCompletion.CANCELLED,
                WriteBehindWorkerCompletion.FAILED,
                -> {
                    workerState = CacheWorkerState.FAILED
                    lifecycle = Lifecycle.FAILED
                    failureKind = WriteBehindFailureKind.WORKER
                }
            }
        }
    }

    /** OPEN close를 owner로, 동시 호출을 follower로 분배합니다. */
    fun beginClose(): CloseLease = synchronized(lock) {
        when (lifecycle) {
            Lifecycle.OPEN -> {
                lifecycle = Lifecycle.DRAINING
                workerState = CacheWorkerState.DRAINING
                CloseLease.Owner.mint(Any()).also { activeOwner = it }
            }

            Lifecycle.DRAINING -> CloseLease.Follower
            Lifecycle.STOPPED,
            Lifecycle.FAILED,
            -> CloseLease.Follower
        }
    }

    /** owner identity와 unpublished→published CAS를 함께 검증하여 completion을 발행합니다. */
    fun publishCloseCompletion(owner: CloseLease.Owner, completion: CloseCompletion) {
        synchronized(lock) {
            check(activeOwner === owner) { "Close completion owner is not active" }
            check(!completionPublished) { "Close completion was already published" }
            check(lifecycle == Lifecycle.DRAINING || lifecycle == Lifecycle.FAILED) {
                "Close completion cannot be published in state=$lifecycle"
            }
            check(completion.queueDepth >= 0) { "Close completion queue depth must not be negative" }
            completionPublished = true
            if (completion.kind == CloseCompletionKind.COMPLETED &&
                completion.workerState == CacheWorkerState.STOPPED &&
                completion.queueDepth == 0
            ) {
                lifecycle = Lifecycle.STOPPED
                workerState = CacheWorkerState.STOPPED
                failureKind = null
            } else {
                lifecycle = Lifecycle.FAILED
                workerState = CacheWorkerState.FAILED
                failureKind = when (completion.kind) {
                    CloseCompletionKind.TIMEOUT -> WriteBehindFailureKind.CLOSE_TIMEOUT
                    CloseCompletionKind.INTERRUPTED -> WriteBehindFailureKind.CLOSE_INTERRUPTED
                    CloseCompletionKind.FAILED -> WriteBehindFailureKind.WORKER
                    CloseCompletionKind.COMPLETED -> WriteBehindFailureKind.WORKER
                }
            }
        }
    }

    /** raw exception을 포함하지 않는 현재 coordinator 상태입니다. */
    fun snapshot(): CoordinatorSnapshot = synchronized(lock) {
        CoordinatorSnapshot(mode, queueDepth, workerState, failureKind)
    }

    /** adapter 테스트/진단용 terminal 판정입니다. */
    internal fun isTerminal(): Boolean = synchronized(lock) {
        lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.FAILED
    }
}
