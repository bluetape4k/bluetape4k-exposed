package io.bluetape4k.batch.api

import java.time.Duration
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
@Suppress("TooManyFunctions")
interface BatchJobRepository {
    /**
     * 이 repository가 authoritative lease claim/renewal을 지원하는지 나타낸다.
     *
     * custom 구현은 새 Duration API와 원자적 renewal을 모두 구현한 뒤에만 true를
     * 선언해야 한다. 기본값은 fail-closed를 위한 false다.
     */
    val supportsLeaseRenewal: Boolean
        get() = false

    /**
     * jobName + params 조합의 재시작 대상 [JobExecution]을 조회하거나 신규 생성한다.
     *
     * RUNNING/FAILED/STOPPED 상태의 기존 실행을 재사용한다.
     * 호출이 끝날 때까지 [params]와 모든 nested collection/array를 변경하지 않아야 한다.
     * 동시 mutation은 hash와 영속 payload의 동일성을 보장하지 않으므로 지원하지 않는다.
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
    @Deprecated(
        message = "Use the Duration-based claimJobExecution overload",
        replaceWith = ReplaceWith("claimJobExecution(execution, ownerId, leaseDuration)"),
    )
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
     * authoritative clock를 사용하는 [JobExecution] lease claim.
     *
     * legacy Instant overload로 fallback하지 않고 unsupported repository를 명확히
     * fail-closed 한다.
     */
    suspend fun claimJobExecution(
        execution: JobExecution,
        ownerId: String,
        leaseDuration: Duration,
    ): JobExecution? = throw UnsupportedOperationException(
        "Duration-based job lease claim is not implemented by this repository",
    )

    /**
     * Job과 현재 Step lease를 하나의 atomic transaction으로 갱신한다.
     *
     * [stepExecution]이 null이면 Job-only renewal이며, 경쟁·stale version·terminal
     * 전이·lease 만료가 하나라도 있으면 양쪽 모두 변경하지 않고 null을 반환한다.
     */
    suspend fun renewExecutionLeases(
        jobExecution: JobExecution,
        stepExecution: StepExecution?,
        leaseDuration: Duration,
    ): BatchExecutionLeaseSnapshot? = throw UnsupportedOperationException(
        "Atomic batch lease renewal is not implemented by this repository",
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
    @Deprecated(
        message = "Use the Duration-based claimStepExecution overload",
        replaceWith = ReplaceWith("claimStepExecution(execution, ownerId, leaseDuration)"),
    )
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
     * authoritative clock를 사용하는 [StepExecution] lease claim.
     *
     * legacy Instant overload로 fallback하지 않고 unsupported repository를 명확히
     * fail-closed 한다.
     */
    suspend fun claimStepExecution(
        execution: StepExecution,
        ownerId: String,
        leaseDuration: Duration,
    ): StepExecution? = throw UnsupportedOperationException(
        "Duration-based step lease claim is not implemented by this repository",
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
     * 보고서의 checkpoint가 null이면 마지막으로 저장된 성공 checkpoint를 유지해야 하며,
     * null은 기존 checkpoint를 지우라는 의미가 아니다.
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
     * 소유자·버전 CAS로 체크포인트를 저장하고 갱신된 실행 상태를 반환한다.
     *
     * 기본 구현은 legacy ID-only 경로로 우회하지 않고 fail-closed 한다. 영속 구현체는
     * owner와 version을 함께 조건으로 검사하고 정확히 한 행을 갱신한 뒤 증가한 version을
     * 가진 [StepExecution]을 반환해야 한다.
     */
    suspend fun saveCheckpointAndReturn(execution: StepExecution, checkpoint: Any): StepExecution =
        throw UnsupportedOperationException(
            "Owner-aware checkpoint update is not implemented by this repository",
        )

    /**
     * 저장된 체크포인트를 조회한다.
     *
     * @param stepExecutionId 대상 [StepExecution] ID
     * @return 저장된 체크포인트 값, 없으면 null
     */
    suspend fun loadCheckpoint(stepExecutionId: Long): Any?
}
