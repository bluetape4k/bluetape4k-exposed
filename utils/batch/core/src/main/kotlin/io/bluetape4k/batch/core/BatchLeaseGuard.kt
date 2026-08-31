package io.bluetape4k.batch.core

import io.bluetape4k.batch.api.BatchExecutionLeaseSnapshot
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** lease를 잃은 뒤 write를 시작하지 않게 하는 runner 내부 예외. */
internal class LeaseLostException(
    cause: Throwable? = null,
) : RuntimeException("Batch execution lease was lost", cause)

/** 테스트와 production에서 같은 monotonic deadline 계산을 사용하기 위한 clock seam. */
internal fun interface BatchMonotonicClock {
    fun nowNanos(): Long
}

internal val SYSTEM_BATCH_MONOTONIC_CLOCK: BatchMonotonicClock =
    BatchMonotonicClock(System::nanoTime)

/**
 * Job과 현재 Step lease 상태를 하나의 mutex로 소유하는 runner 전용 guard.
 *
 * 외부에 permit이나 mutable execution을 노출하지 않고, write 직전 확인·갱신과
 * heartbeat의 version stream을 직렬화한다. repository I/O는 caller cancellation에
 * 협력하며, timeout만 lease loss로 변환한다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class BatchLeaseGuard(
    private val repository: BatchJobRepository,
    private val ownerId: String,
    executionLease: java.time.Duration,
    initialJobExecution: JobExecution,
    initialStepExecution: StepExecution?,
    private val monotonicClock: BatchMonotonicClock = SYSTEM_BATCH_MONOTONIC_CLOCK,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    initialClaimStartedNanos: Long? = null,
) {
    private val timing = BatchLeaseCalculator.calculate(executionLease)
    private val mutex = Mutex()
    private val leaseNanos = timing.leaseMillis * NANOS_PER_MILLISECOND
    private var latestJobExecution = initialJobExecution
    private var latestStepExecution = initialStepExecution
    private var jobDeadlineNanos = deadlineFrom(initialClaimStartedNanos ?: monotonicClock.nowNanos())
    private var stepDeadlineNanos = latestStepExecution?.let { jobDeadlineNanos }
    private var leaseLost: LeaseLostException? = null
    private var heartbeatJob: Job? = null

    /** 외부 cancellation compensation이 공유하는 repository/cleanup 상한. */
    val repositoryTimeoutMillis: Long
        get() = timing.repositoryTimeoutMillis

    init {
        check(repository.supportsLeaseRenewal) {
            "Batch lease guard requires a repository with lease renewal support"
        }
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
    }

    /** write/checkpoint 직전의 현재 snapshot을 확인하고 필요하면 atomic renewal을 수행한다. */
    suspend fun checkBothAndMaybeRenew(): BatchExecutionLeaseSnapshot = mutex.withLock {
        ensureLeaseAvailable()
        val remaining = remainingNanos(minDeadlineNanos())
        if (remaining <= timing.safeMarginMillis * NANOS_PER_MILLISECOND) {
            renewLocked()
        }
        snapshotLocked()
    }

    /**
     * 전달된 block을 호출하기 직전까지 guard mutex에서 lease를 확인한다.
     * 외부 write 동안에는 mutex를 해제해 heartbeat renewal이 계속 진행되게 한다.
     */
    suspend fun <T> withWritePermit(block: suspend () -> T): T {
        mutex.withLock {
            ensureLeaseAvailable()
            if (remainingNanos(minDeadlineNanos()) <= timing.safeMarginMillis * NANOS_PER_MILLISECOND) {
                renewLocked()
            }
        }
        return block()
    }

    /** heartbeat 간격에 맞춰 Job(+현재 Step)을 한 번에 갱신하는 child를 시작한다. */
    fun startHeartbeat(scope: CoroutineScope): Job {
        check(heartbeatJob == null) { "Batch lease heartbeat is already running" }
        return scope.launch {
            while (isActive) {
                ensureActive()
                pause(timing.heartbeatIntervalMillis)
                ensureActive()
                mutex.withLock {
                    ensureLeaseAvailable()
                    renewLocked()
                }
            }
        }.also { heartbeatJob = it }
    }

    /** heartbeat child를 bounded NonCancellable cleanup gate에서 종료한다. */
    suspend fun stopHeartbeat() {
        withContext(kotlinx.coroutines.NonCancellable) {
            withTimeout(timing.repositoryTimeoutMillis) {
                stopHeartbeatInCurrentContext()
            }
        }
    }

    /** 이미 만들어진 bounded cleanup envelope 안에서 heartbeat를 종료한다. */
    suspend fun stopHeartbeatInCurrentContext() {
        val heartbeat = heartbeatJob ?: return
        heartbeatJob = null
        heartbeat.cancelAndJoin()
    }

    /** 최신 guard snapshot을 읽는다. mutable permit은 반환하지 않는다. */
    suspend fun latestSnapshot(): BatchExecutionLeaseSnapshot = mutex.withLock {
        ensureLeaseAvailable()
        snapshotLocked()
    }

    /**
     * lease를 이미 잃은 뒤에도 마지막 성공 readback을 진단용으로 반환한다.
     *
     * 이 snapshot은 현재 DB 소유권을 나타내지 않으므로 재시작이나 completion의
     * CAS 입력으로 사용해서는 안 된다. 호출자는 owner와 lease를 public report에
     * 복사하지 않는 sanitized projection을 만들어야 한다.
     */
    suspend fun latestSnapshotForDiagnostics(): BatchExecutionLeaseSnapshot = mutex.withLock {
        snapshotLocked()
    }

    /** checkpoint readback으로 증가한 Step version을 guard 기준 데이터에 반영한다. */
    suspend fun recordStepExecution(
        updated: StepExecution,
        claimStartedNanos: Long? = null,
    ) = mutex.withLock {
        ensureLeaseAvailable()
        val current = latestStepExecution
        check(current == null ||
            (updated.id == current.id &&
                updated.jobExecutionId == latestJobExecution.id &&
                updated.ownerId == ownerId &&
                updated.version >= current.version)
        ) {
            "StepExecution update does not belong to the current lease"
        }
        latestStepExecution = updated
        claimStartedNanos?.let { stepDeadlineNanos = deadlineFrom(it) }
    }

    /** 최신 Step lease를 확인한 상태에서 terminal completion을 직렬화한다. */
    suspend fun completeStepExecution(
        complete: suspend (StepExecution) -> Unit,
    ) = mutex.withLock {
        ensureLeaseAvailable()
        val execution = checkNotNull(latestStepExecution) {
            "Step completion requires an active Step lease"
        }
        complete(execution)
        latestStepExecution = null
        stepDeadlineNanos = null
    }

    /** 정상 Step completion 뒤 다음 Step의 Job-only lease 구간으로 전환한다. */
    suspend fun finishStepExecution() = mutex.withLock {
        ensureLeaseAvailable()
        latestStepExecution = null
        stepDeadlineNanos = null
    }

    /** heartbeat가 올린 최신 Job version을 외부 completion 경계에서 사용한다. */
    suspend fun recordJobExecution(updated: JobExecution) = mutex.withLock {
        ensureLeaseAvailable()
        check(
            updated.id == latestJobExecution.id &&
                updated.ownerId == ownerId &&
                updated.version >= latestJobExecution.version,
        ) {
            "JobExecution update does not belong to the current lease"
        }
        latestJobExecution = updated
    }

    /** lease loss는 하나의 canonical 상태로 기록하고 같은 예외를 재사용한다. */
    fun failLease(cause: Throwable? = null): Nothing {
        val failure = leaseLost ?: LeaseLostException(cause).also { leaseLost = it }
        throw failure
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun renewLocked() {
        val callStartedNanos = monotonicClock.nowNanos()
        val renewed = try {
            withTimeoutOrNull(timing.repositoryTimeoutMillis) {
                repository.renewExecutionLeases(
                    latestJobExecution,
                    latestStepExecution,
                    java.time.Duration.ofMillis(timing.leaseMillis),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failLease(failure)
        }

        if (renewed == null) failLease()
        if (renewed.jobExecution.ownerId != ownerId ||
            renewed.stepExecution?.ownerId?.let { it != ownerId } == true
        ) {
            failLease()
        }
        latestJobExecution = renewed.jobExecution
        latestStepExecution = renewed.stepExecution
        val deadline = deadlineFrom(callStartedNanos)
        jobDeadlineNanos = deadline
        stepDeadlineNanos = latestStepExecution?.let { deadline }
    }

    private fun ensureLeaseAvailable() {
        leaseLost?.let { throw it }
    }

    private fun snapshotLocked(): BatchExecutionLeaseSnapshot =
        BatchExecutionLeaseSnapshot(latestJobExecution, latestStepExecution)

    private fun minDeadlineNanos(): Long = minOf(jobDeadlineNanos, stepDeadlineNanos ?: jobDeadlineNanos)

    private fun remainingNanos(deadlineNanos: Long): Long {
        val now = monotonicClock.nowNanos()
        return if (deadlineNanos <= now) 0L else deadlineNanos - now
    }

    private fun deadlineFrom(startNanos: Long): Long =
        if (Long.MAX_VALUE - startNanos < leaseNanos) Long.MAX_VALUE else startNanos + leaseNanos

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
