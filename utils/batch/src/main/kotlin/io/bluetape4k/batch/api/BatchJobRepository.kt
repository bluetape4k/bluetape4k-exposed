package io.bluetape4k.batch.api

import java.time.Instant

/**
 * 배치 Job/Step 실행 이력을 저장하고 재시작을 지원하는 리포지토리 인터페이스.
 *
 * ## 구현체
 * - `InMemoryBatchJobRepository` — 테스트/단순 용도
 * - `ExposedJdbcBatchJobRepository` — Exposed JDBC 기반 영속
 * - `ExposedR2dbcBatchJobRepository` — Exposed R2DBC 기반 영속
 *
 * ## 재시작 시나리오
 * ```
 * findOrCreateJobExecution("importOrders", params)
 *   → 기존 FAILED/STOPPED 실행 재사용 or 신규 생성
 * findOrCreateStepExecution(jobExecution, "readStep")
 *   → COMPLETED/COMPLETED_WITH_SKIPS이면 그대로 반환 (runner가 skip)
 *   → 그 외는 재실행 대상
 * ```
 */
interface BatchJobRepository {
    /**
     * jobName + params 조합의 재시작 대상 [JobExecution]을 조회하거나 신규 생성한다.
     *
     * RUNNING/FAILED/STOPPED 상태의 기존 실행을 재사용한다.
     *
     * @param jobName Job 이름
     * @param params Job 실행 파라미터
     * @return 기존 또는 신규 [JobExecution]
     */
    suspend fun findOrCreateJobExecution(
        jobName: String,
        params: Map<String, Any> = emptyMap(),
    ): JobExecution

    /**
     * [JobExecution] 실행 소유권을 원자적으로 획득한다.
     *
     * 이미 다른 owner가 유효 lease로 실행 중이면 null을 반환해야 한다.
     */
    suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): JobExecution? =
        execution.copy(
            status = BatchStatus.RUNNING,
            ownerId = ownerId,
            leaseUntil = leaseUntil,
            version = execution.version + 1,
        )

    /**
     * [JobExecution]을 완료 상태로 갱신한다.
     *
     * @param execution 갱신할 [JobExecution]
     * @param status 최종 상태 (COMPLETED, COMPLETED_WITH_SKIPS, FAILED, STOPPED 중 하나)
     *
     * 저장 실패는 호출자에게 전파해야 한다. 실행기는 일반적인 FAILED/STOPPED 보정
     * 저장 실패를 실행 원인의 suppressed cause로 보존하고, 저장 중 발생한
     * [kotlinx.coroutines.CancellationException]은 원인 예외와 함께 재던진다. 자동
     * 재시도나 outbox 전환은 저장소 구현체와 운영자가 별도로 결정한다.
     */
    suspend fun completeJobExecution(execution: JobExecution, status: BatchStatus)

    /**
     * jobExecution + stepName 의 [StepExecution]을 조회하거나 신규 생성한다.
     *
     * **COMPLETED / COMPLETED_WITH_SKIPS** 상태의 기존 실행은 UPDATE 없이 그대로 반환한다.
     * runner가 해당 상태를 감지하여 즉시 skip 처리한다.
     *
     * @param jobExecution 소속 [JobExecution]
     * @param stepName Step 이름
     * @return 기존 또는 신규 [StepExecution]
     */
    suspend fun findOrCreateStepExecution(
        jobExecution: JobExecution,
        stepName: String,
    ): StepExecution

    /**
     * [StepExecution] 실행 소유권을 원자적으로 획득한다.
     *
     * 이미 완료된 step은 claim 대상이 아니며, 이미 다른 owner가 유효 lease로 실행 중이면 null을 반환해야 한다.
     */
    suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseUntil: Instant,
    ): StepExecution? =
        execution.copy(
            status = BatchStatus.RUNNING,
            ownerId = ownerId,
            leaseUntil = leaseUntil,
            version = execution.version + 1,
        )

    /**
     * [StepExecution]을 완료 상태로 갱신한다.
     *
     * @param execution 갱신할 [StepExecution]
     * @param report Step 실행 결과 보고서
     *
     * 저장 실패는 호출자에게 전파해야 한다. 실행기는 일반적인 FAILED/STOPPED 보정
     * 저장 실패를 실행 원인의 suppressed cause로 보존하고, 저장 중 발생한
     * [kotlinx.coroutines.CancellationException]은 원인 예외와 함께 재던진다.
     */
    suspend fun completeStepExecution(execution: StepExecution, report: StepReport)

    /**
     * 체크포인트를 저장한다.
     *
     * @param stepExecutionId 대상 [StepExecution] ID
     * @param checkpoint 저장할 체크포인트 값
     */
    suspend fun saveCheckpoint(stepExecutionId: Long, checkpoint: Any)

    /**
     * claim된 [StepExecution]의 소유권을 확인하며 체크포인트를 저장한다.
     */
    suspend fun saveCheckpoint(execution: StepExecution, checkpoint: Any) {
        saveCheckpoint(execution.id, checkpoint)
    }

    /**
     * 저장된 체크포인트를 조회한다.
     *
     * @param stepExecutionId 대상 [StepExecution] ID
     * @return 저장된 체크포인트 값, 없으면 null
     */
    suspend fun loadCheckpoint(stepExecutionId: Long): Any?
}
