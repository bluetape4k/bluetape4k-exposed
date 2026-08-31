package io.bluetape4k.batch.core

import io.bluetape4k.batch.api.BatchExecutionLeaseSnapshot
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.api.requireValidBatchLeaseDuration
import io.bluetape4k.batch.api.requireValidBatchName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.runInterruptible
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * 인메모리 [BatchJobRepository] 구현체. 테스트 및 단순 사용에 적합하다.
 *
 * ## 특징
 * - checkpoint를 `Any` 객체 그대로 [ConcurrentHashMap]에 저장
 * - 재시작 시 `RUNNING/FAILED/STOPPED` 상태의 JobExecution을 재사용
 * - thread-safe ([ConcurrentHashMap] + [AtomicLong])
 * - 운영 로그에는 caller-owned 이름, 실행 ID, checkpoint payload를 기록하지 않음
 *
 * ## findOrCreateStepExecution 4-case 계약
 * | 기존 status              | 동작                               |
 * |--------------------------|-----------------------------------|
 * | COMPLETED                | 변경 없이 그대로 반환 (runner skip) |
 * | COMPLETED_WITH_SKIPS     | 변경 없이 그대로 반환 (runner skip) |
 * | FAILED / STOPPED / RUNNING | copy(status=RUNNING) 저장 후 반환  |
 * | 없음                     | 신규 생성 (status=RUNNING)         |
 *
 * ## 사용 예
 * ```kotlin
 * val repository = InMemoryBatchJobRepository()
 * val jobExecution = repository.findOrCreateJobExecution("importOrders", mapOf("date" to "2026-04-10"))
 * val stepExecution = repository.findOrCreateStepExecution(jobExecution, "readStep")
 * ```
 */
@Suppress("TooManyFunctions")
class InMemoryBatchJobRepository(
    private val clock: Clock = Clock.systemUTC(),
) : BatchJobRepository {

    companion object : KLogging()

    private val idCounter = AtomicLong(0L)
    private val jobExecutions = ConcurrentHashMap<Long, JobExecution>()
    private val stepExecutions = ConcurrentHashMap<Long, StepExecution>()
    private val checkpoints = ConcurrentHashMap<Long, Any>()
    private val lock = ReentrantLock()

    override val supportsLeaseRenewal: Boolean = true

    /**
     * jobName + params 조합의 재시작 대상 [JobExecution]을 조회하거나 신규 생성한다.
     *
     * `RUNNING/FAILED/STOPPED` 상태의 기존 실행을 재사용하며 상태를 `RUNNING`으로 복원한다.
     * 해당 상태의 기존 실행이 없으면 신규 [JobExecution]을 생성한다.
     *
     * @param jobName Job 이름 (blank 불가)
     * @param params Job 실행 파라미터
     * @return 재사용하거나 신규 생성한 [JobExecution]
     */
    override suspend fun findOrCreateJobExecution(
        jobName: String,
        params: Map<String, Any>,
    ): JobExecution {
        jobName.requireValidBatchName("jobName")

        return withLock {
            val existing = jobExecutions.values.firstOrNull { je ->
                je.jobName == jobName &&
                    je.params == params &&
                    je.status in setOf(BatchStatus.RUNNING, BatchStatus.FAILED, BatchStatus.STOPPED)
            }

            if (existing != null) {
                log.debug { "기존 JobExecution 재사용: status=${existing.status}" }
                existing
            } else {
                val newId = idCounter.incrementAndGet()
                val newExecution = JobExecution(
                    id = newId,
                    jobName = jobName,
                    params = params,
                    status = BatchStatus.RUNNING,
                    startTime = clock.instant(),
                )
                jobExecutions[newId] = newExecution
                log.debug { "신규 JobExecution 생성" }
                newExecution
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): JobExecution? {
        ownerId.requireValidBatchName("ownerId")
        return withLock {
            val current = jobExecutions[execution.id]
            if (current == null) {
                val inserted = execution.copy(
                    status = BatchStatus.RUNNING,
                    ownerId = ownerId,
                    leaseUntil = leaseUntil,
                    version = execution.version + 1,
                    endTime = null,
                )
                jobExecutions[inserted.id] = inserted
                idCounter.updateAndGet { current -> maxOf(current, inserted.id) }
                return@withLock inserted
            }
            val now = clock.instant()
            val claimable = current.version == execution.version &&
                (
                    current.status in setOf(BatchStatus.FAILED, BatchStatus.STOPPED) ||
                        (
                            current.status == BatchStatus.RUNNING &&
                                (current.ownerId == null || current.leaseUntil == null || current.leaseUntil < now)
                            )
                    )
            if (!claimable) return@withLock null

            val updated = current.copy(
                status = BatchStatus.RUNNING,
                ownerId = ownerId,
                leaseUntil = leaseUntil,
                version = current.version + 1,
                endTime = null,
            )
            jobExecutions[updated.id] = updated
            updated
        }
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseDuration: Duration,
    ): JobExecution? {
        ownerId.requireValidBatchName("ownerId")
        leaseDuration.requireValidBatchLeaseDuration()
        return withLock {
            val now = clock.instant()
            val newLeaseUntil = leaseUntil(now, leaseDuration)
            val current = jobExecutions[execution.id]
            if (current == null) {
                if (execution.status !in setOf(BatchStatus.STARTING, BatchStatus.RUNNING) ||
                    execution.ownerId != null ||
                    execution.leaseUntil != null
                ) {
                    return@withLock null
                }
                val inserted = execution.copy(
                    status = BatchStatus.RUNNING,
                    ownerId = ownerId,
                    leaseUntil = newLeaseUntil,
                    version = execution.version + 1,
                    endTime = null,
                )
                jobExecutions[inserted.id] = inserted
                idCounter.updateAndGet { currentId -> maxOf(currentId, inserted.id) }
                return@withLock inserted
            }

            val claimable = current.version == execution.version && when (current.status) {
                BatchStatus.STARTING -> current.ownerId == null && current.leaseUntil == null
                BatchStatus.FAILED,
                BatchStatus.STOPPED -> true
                BatchStatus.RUNNING -> current.ownerId == null ||
                    current.leaseUntil == null ||
                    !current.leaseUntil.isAfter(now)
                else -> false
            }
            if (!claimable) return@withLock null

            current.copy(
                status = BatchStatus.RUNNING,
                ownerId = ownerId,
                leaseUntil = newLeaseUntil,
                version = current.version + 1,
                endTime = null,
            ).also { jobExecutions[it.id] = it }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun renewExecutionLeases(
        jobExecution: JobExecution,
        stepExecution: StepExecution?,
        leaseDuration: Duration,
    ): BatchExecutionLeaseSnapshot? {
        leaseDuration.requireValidBatchLeaseDuration()
        val ownerId = jobExecution.ownerId
            ?.takeIf { it.isNotBlank() }
            ?.requireValidBatchName("ownerId")
            ?: throw IllegalArgumentException("ownerId must not be blank")
        if (stepExecution != null && stepExecution.ownerId != ownerId) return null

        return withLock {
            val now = clock.instant()
            val currentJob = jobExecutions[jobExecution.id] ?: return@withLock null
            val currentStep = if (stepExecution == null) {
                null
            } else {
                stepExecutions[stepExecution.id] ?: return@withLock null
            }

            if (!isRenewable(currentJob, jobExecution, ownerId, now)) return@withLock null
            if (currentStep != null) {
                val requestedStep = checkNotNull(stepExecution)
                if (currentStep.jobExecutionId != jobExecution.id ||
                    !isRenewable(currentStep, requestedStep, ownerId, now)
                ) {
                    return@withLock null
                }
            }

            val newLeaseUntil = leaseUntil(now, leaseDuration)
            if (!newLeaseUntil.isAfter(currentJob.leaseUntil)) return@withLock null
            if (currentStep != null && !newLeaseUntil.isAfter(currentStep.leaseUntil)) return@withLock null

            val renewedJob = currentJob.copy(
                leaseUntil = newLeaseUntil,
                version = currentJob.version + 1,
            )
            val renewedStep = currentStep?.copy(
                leaseUntil = newLeaseUntil,
                version = currentStep.version + 1,
            )

            // 모든 검증을 통과한 뒤 Job과 Step을 함께 반영해 partial renewal을 막는다.
            jobExecutions[renewedJob.id] = renewedJob
            renewedStep?.let { stepExecutions[it.id] = it }
            BatchExecutionLeaseSnapshot(renewedJob, renewedStep)
        }
    }

    /**
     * [JobExecution]을 완료 상태로 갱신한다.
     *
     * @param execution 갱신할 [JobExecution]
     * @param status 최종 상태 (`COMPLETED`, `COMPLETED_WITH_SKIPS`, `FAILED`, `STOPPED` 중 하나)
     */
    override suspend fun completeJobExecution(execution: JobExecution, status: BatchStatus) {
        val updated = execution.copy(
            status = status,
            ownerId = null,
            leaseUntil = null,
            version = execution.version + 1,
            endTime = clock.instant(),
        )
        withLock {
            val current = jobExecutions[execution.id]
            val ownerMatches = if (execution.ownerId == null) {
                current?.ownerId == null
            } else {
                current?.ownerId == execution.ownerId
            }
            check(current != null && ownerMatches && current.version == execution.version) {
                "JobExecution completion rejected: id=${execution.id}"
            }
            jobExecutions[execution.id] = updated
        }
        log.debug { "JobExecution 완료: status=$status" }
    }

    /**
     * jobExecution + stepName 의 [StepExecution]을 조회하거나 신규 생성한다.
     *
     * `COMPLETED`/`COMPLETED_WITH_SKIPS` 상태의 기존 실행은 UPDATE 없이 그대로 반환하며,
     * runner가 해당 상태를 감지하여 즉시 skip 처리한다.
     *
     * @param jobExecution 소속 [JobExecution]
     * @param stepName Step 이름 (blank 불가)
     * @return 기존 또는 신규 [StepExecution]
     */
    override suspend fun findOrCreateStepExecution(
        jobExecution: JobExecution,
        stepName: String,
    ): StepExecution {
        stepName.requireValidBatchName("stepName")

        return withLock {
            val existing = stepExecutions.values.firstOrNull { se ->
                se.jobExecutionId == jobExecution.id && se.stepName == stepName
            }

            if (existing != null) {
                when (existing.status) {
                    // 완료 상태 — 변경 없이 반환. BatchStepRunner가 즉시 skip 처리
                    BatchStatus.COMPLETED,
                    BatchStatus.COMPLETED_WITH_SKIPS -> {
                        log.debug { "StepExecution skip (이미 완료): status=${existing.status}" }
                        existing
                    }

                    // 재시작 대상 — claim 단계에서 RUNNING으로 복원
                    BatchStatus.FAILED,
                    BatchStatus.STOPPED,
                    BatchStatus.RUNNING -> {
                        log.debug { "StepExecution 재시작 대상: 이전 status=${existing.status}" }
                        existing
                    }

                    // 그 외 예상치 못한 상태 — 변경 없이 반환
                    else -> {
                        log.warn { "StepExecution 예상치 못한 상태: status=${existing.status}" }
                        existing
                    }
                }
            } else {
                val newId = idCounter.incrementAndGet()
                val newExecution = StepExecution(
                    id = newId,
                    jobExecutionId = jobExecution.id,
                    stepName = stepName,
                    status = BatchStatus.RUNNING,
                    startTime = clock.instant(),
                )
                stepExecutions[newId] = newExecution
                log.debug { "신규 StepExecution 생성" }
                newExecution
            }
        }
    }

    override suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): StepExecution? {
        ownerId.requireValidBatchName("ownerId")
        return withLock {
            val current = stepExecutions[execution.id] ?: return@withLock null
            val now = clock.instant()
            val claimable = current.version == execution.version &&
                (
                    current.status in setOf(BatchStatus.FAILED, BatchStatus.STOPPED) ||
                        (
                            current.status == BatchStatus.RUNNING &&
                                (current.ownerId == null || current.leaseUntil == null || current.leaseUntil < now)
                            )
                    )
            if (!claimable) return@withLock null

            val updated = current.copy(
                status = BatchStatus.RUNNING,
                ownerId = ownerId,
                leaseUntil = leaseUntil,
                version = current.version + 1,
                endTime = null,
            )
            stepExecutions[updated.id] = updated
            updated
        }
    }

    override suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseDuration: Duration,
    ): StepExecution? {
        ownerId.requireValidBatchName("ownerId")
        leaseDuration.requireValidBatchLeaseDuration()
        return withLock {
            val current = stepExecutions[execution.id] ?: return@withLock null
            val now = clock.instant()
            val claimable = current.version == execution.version && when (current.status) {
                BatchStatus.STARTING -> current.ownerId == null && current.leaseUntil == null
                BatchStatus.FAILED,
                BatchStatus.STOPPED -> true
                BatchStatus.RUNNING -> current.ownerId == null ||
                    current.leaseUntil == null ||
                    !current.leaseUntil.isAfter(now)
                else -> false
            }
            if (!claimable) return@withLock null

            current.copy(
                status = BatchStatus.RUNNING,
                ownerId = ownerId,
                leaseUntil = leaseUntil(now, leaseDuration),
                version = current.version + 1,
                endTime = null,
            ).also { stepExecutions[it.id] = it }
        }
    }

    /**
     * [StepExecution]을 완료 상태로 갱신한다.
     *
     * [StepReport]의 통계(readCount, writeCount, skipCount, checkpoint)를 반영하고
     * 종료 시각을 기록한다.
     *
     * @param execution 갱신할 [StepExecution]
     * @param report Step 실행 결과 보고서
     */
    override suspend fun completeStepExecution(execution: StepExecution, report: StepReport) {
        withLock {
            val current = stepExecutions[execution.id]
            val ownerMatches = if (execution.ownerId == null) {
                current?.ownerId == null
            } else {
                current?.ownerId == execution.ownerId
            }
            check(current != null && ownerMatches && current.version == execution.version) {
                "StepExecution completion rejected: id=${execution.id}"
            }
            val checkpoint = report.checkpoint ?: current.checkpoint ?: checkpoints[execution.id]
            val updated = execution.copy(
                status = report.status,
                readCount = report.readCount,
                writeCount = report.writeCount,
                skipCount = report.skipCount,
                checkpoint = checkpoint,
                endTime = clock.instant(),
            )
            stepExecutions[execution.id] = updated.copy(
                ownerId = null,
                leaseUntil = null,
                version = execution.version + 1,
            )
            checkpoint?.let { checkpoints[execution.id] = it }
        }
        log.debug {
            "StepExecution 완료: status=${report.status}, " +
                "read=${report.readCount}, write=${report.writeCount}, skip=${report.skipCount}"
        }
    }

    /**
     * 체크포인트를 저장한다.
     *
     * 재시작 시 [loadCheckpoint]로 복원하여 중단 지점부터 재개할 수 있다.
     *
     * @param stepExecutionId 대상 [StepExecution] ID
     * @param checkpoint 저장할 체크포인트 값
     */
    override suspend fun saveCheckpoint(stepExecutionId: Long, checkpoint: Any) {
        checkpoints[stepExecutionId] = checkpoint
        log.debug { "체크포인트 저장 완료" }
    }

    override suspend fun saveCheckpoint(execution: StepExecution, checkpoint: Any) {
        saveCheckpointAndReturn(execution, checkpoint)
    }

    override suspend fun saveCheckpointAndReturn(
        execution: StepExecution,
        checkpoint: Any,
    ): StepExecution {
        val ownerId = checkNotNull(execution.ownerId) {
            "Owner-aware checkpoint update requires ownerId"
        }.also {
            check(it.isNotBlank()) { "Owner-aware checkpoint update requires ownerId" }
        }.requireValidBatchName("ownerId")

        val updated = withLock {
            val current = checkNotNull(stepExecutions[execution.id]) {
                "StepExecution not found: id=${execution.id}"
            }
            check(current.ownerId == ownerId && current.version == execution.version) {
                "Owner-aware checkpoint update rejected: id=${execution.id}"
            }
            val next = current.copy(
                checkpoint = checkpoint,
                version = current.version + 1,
            )
            stepExecutions[next.id] = next
            checkpoints[next.id] = checkpoint
            next
        }
        log.debug { "체크포인트 저장 완료" }
        return updated
    }

    /**
     * 저장된 체크포인트를 조회한다.
     *
     * @param stepExecutionId 대상 [StepExecution] ID
     * @return 저장된 체크포인트 값, 없으면 null
     */
    override suspend fun loadCheckpoint(stepExecutionId: Long): Any? =
        checkpoints[stepExecutionId].also { checkpoint ->
            log.debug { "체크포인트 조회 완료: found=${checkpoint != null}" }
        }

    private suspend fun <T> withLock(block: () -> T): T = runInterruptible {
        lock.lockInterruptibly()
        try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun isRenewable(
        current: JobExecution,
        requested: JobExecution,
        ownerId: String,
        now: Instant,
    ): Boolean = current.id == requested.id &&
        current.status == BatchStatus.RUNNING &&
        current.ownerId == ownerId &&
        current.version == requested.version &&
        current.leaseUntil?.isAfter(now) == true

    private fun isRenewable(
        current: StepExecution,
        requested: StepExecution,
        ownerId: String,
        now: Instant,
    ): Boolean = current.id == requested.id &&
        current.status == BatchStatus.RUNNING &&
        current.ownerId == ownerId &&
        current.version == requested.version &&
        current.leaseUntil?.isAfter(now) == true

    private fun leaseUntil(now: Instant, duration: Duration): Instant = try {
        now.plus(duration)
    } catch (cause: ArithmeticException) {
        throw IllegalArgumentException(
            "Invalid batch execution lease duration: overflow",
            cause,
        )
    } catch (cause: DateTimeException) {
        throw IllegalArgumentException(
            "Invalid batch execution lease duration: overflow",
            cause,
        )
    }
}
