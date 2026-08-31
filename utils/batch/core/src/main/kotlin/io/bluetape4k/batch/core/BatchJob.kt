package io.bluetape4k.batch.core

import io.bluetape4k.codec.Base58
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchExecutionAlreadyClaimedException
import io.bluetape4k.batch.api.BatchInfrastructureFailureException
import io.bluetape4k.batch.api.BatchReport
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.batch.api.requireValidBatchLeaseDuration
import io.bluetape4k.batch.api.requireValidBatchName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.workflow.api.SuspendWork
import io.bluetape4k.workflow.api.WorkContext
import io.bluetape4k.workflow.api.WorkReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 배치 Job 실행기. 여러 [BatchStep]을 순차적으로 실행하며 재시작을 지원합니다.
 *
 * ## 재시작 계약
 * - `RUNNING/FAILED/STOPPED` 상태의 기존 JobExecution을 재사용합니다.
 * - 이미 `COMPLETED/COMPLETED_WITH_SKIPS`인 StepExecution은 내부적으로 skip됩니다.
 *
 * ## Workflow 통합
 * [SuspendWork] 구현으로 Workflow DSL 안에 임베딩할 수 있습니다.
 * `PartiallyCompleted`는 skip이 의도된 동작이므로 [WorkReport.success]로 매핑합니다.
 *
 * ```kotlin
 * val job = batchJob("importOrders") {
 *     repository(myRepository)
 *     params("date" to "2026-04-10")
 *     step<Order, OrderEntity>("loadStep") {
 *         reader(orderReader)
 *         writer(orderWriter)
 *         chunkSize(500)
 *     }
 * }
 * val report = job.run()
 * ```
 */
@Suppress("TooManyFunctions")
class BatchJob(
    val name: String,
    val params: Map<String, Any> = emptyMap(),
    val steps: List<BatchStep<*, *>>,
    val repository: BatchJobRepository,
    val executionLease: Duration = Duration.ofMinutes(15),
) : SuspendWork {

    companion object : KLoggingChannel()

    init {
        name.requireValidBatchName("name")
        steps.requireNotEmpty("steps")
        executionLease.requireValidBatchLeaseDuration("executionLease")
    }

    /**
     * Job을 실행하고 [BatchReport]를 반환합니다.
     *
     * ## 동작
     * 1. [BatchJobRepository.findOrCreateJobExecution]으로 JobExecution 생성/재사용
     * 2. 각 Step을 [BatchStepRunner]로 실행 — COMPLETED Step은 내부적으로 skip
     * 3. Step FAILED → [BatchReport.Failure] 반환 (Job 전체 중단)
     * 4. 모든 Step 성공 → skip 여부에 따라 [BatchReport.Success] 또는 [BatchReport.PartiallyCompleted] 반환
     *
     * ## 취소 처리
     * - 외부 코루틴 취소([CancellationException]) → STOPPED 영속화 후 **반드시 재던짐**
     * - 치명적 예외([Throwable]) → FAILED 영속화 후 [BatchReport.Failure] 반환
     * - 일반적인 FAILED/STOPPED 영속화 실패는 원인 예외의 suppressed cause로 보존하고
     *   error 로그를 남긴다. 영속화 중 발생한 [CancellationException]은 원인 예외를
     *   suppressed로 연결한 뒤 전파한다. 자동 재시도·outbox는 이 실행기의 책임이 아니다.
     *
     * @return [BatchReport.Success], [BatchReport.PartiallyCompleted], 또는 [BatchReport.Failure]
     */
    @Suppress("LongMethod", "ReturnCount")
    suspend fun run(): BatchReport {
        val jobExecution = repository.findOrCreateJobExecution(name, params)
        if (!repository.supportsLeaseRenewal) {
            return BatchReport.Failure(
                jobExecution.sanitizeForInfrastructureFailure(),
                emptyList(),
                BatchInfrastructureFailureException(
                    BatchInfrastructureFailureException.REPOSITORY_FAILURE,
                    UUID.randomUUID().toString(),
                ),
            )
        }
        val ownerId = "${name}-${Base58.randomString(8)}"
        val claimStartedNanos = SYSTEM_BATCH_MONOTONIC_CLOCK.nowNanos()
        val claimedJobExecution = repository.claimJobExecution(
            execution = jobExecution,
            ownerId = ownerId,
            leaseDuration = executionLease,
        ) ?: return BatchReport.Failure(
            jobExecution.sanitizeForInfrastructureFailure(),
            emptyList(),
            BatchExecutionAlreadyClaimedException("Job", jobExecution.id, jobExecution.ownerId),
        )
        val leaseGuard = BatchLeaseGuard(
            repository = repository,
            ownerId = ownerId,
            executionLease = executionLease,
            initialJobExecution = claimedJobExecution,
            initialStepExecution = null,
            initialClaimStartedNanos = claimStartedNanos,
        )
        leaseGuard.startHeartbeat(CoroutineScope(currentCoroutineContext()))
        val stepReports = mutableListOf<StepReport>()

        try {
            for (step in steps) {
                @Suppress("UNCHECKED_CAST")
                val runner = BatchStepRunner(
                    step = step as BatchStep<Any, Any>,
                    jobExecution = claimedJobExecution,
                    repository = repository,
                    leaseDuration = executionLease,
                    leaseGuard = leaseGuard,
                )
                val report = runner.run()
                stepReports += report

                if (report.status == BatchStatus.FAILED) {
                    throw report.error
                        ?: error("Step '${step.name}' FAILED without error")
                }
            }

            val hasSkips = stepReports.any { it.skipCount > 0 }
            val finalStatus = if (hasSkips) BatchStatus.COMPLETED_WITH_SKIPS else BatchStatus.COMPLETED
            val completionExecution = stopHeartbeatAndGetLatest(leaseGuard)
            repository.completeJobExecution(completionExecution, finalStatus)
            val reportExecution = completionExecution.copy(
                status = finalStatus,
                ownerId = null,
                leaseUntil = null,
            )

            return if (hasSkips) {
                BatchReport.PartiallyCompleted(
                    reportExecution,
                    stepReports,
                )
            } else {
                BatchReport.Success(
                    reportExecution,
                    stepReports,
                )
            }

        } catch (_: LeaseLostException) {
            return leaseLossFailure(leaseGuard, claimedJobExecution, stepReports)
        } catch (e: CancellationException) {
            compensateExternalCancellation(leaseGuard, claimedJobExecution, e)
            throw e

        } catch (e: BatchInfrastructureFailureException) {
            return executionFailure(leaseGuard, claimedJobExecution, stepReports, e, sanitize = true)
        } catch (e: BatchExecutionAlreadyClaimedException) {
            return executionFailure(leaseGuard, claimedJobExecution, stepReports, e, sanitize = true)
        } catch (e: Throwable) {
            return executionFailure(leaseGuard, claimedJobExecution, stepReports, e, sanitize = false)
        }
    }

    private suspend fun stopHeartbeatAndGetLatest(
        leaseGuard: BatchLeaseGuard,
    ): JobExecution {
        leaseGuard.stopHeartbeat()
        return leaseGuard.latestSnapshot().jobExecution
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun stopHeartbeatAndGetDiagnostic(
        leaseGuard: BatchLeaseGuard,
        fallback: JobExecution,
    ): JobExecution = withContext(NonCancellable) {
        leaseGuard.stopHeartbeat()
        try {
            leaseGuard.latestSnapshotForDiagnostics().jobExecution
        } catch (failure: Throwable) {
            failure.rethrowIfCancellation()
            fallback
        }
    }

    private suspend fun executionFailure(
        leaseGuard: BatchLeaseGuard,
        fallback: JobExecution,
        stepReports: List<StepReport>,
        failure: Throwable,
        sanitize: Boolean,
    ): BatchReport.Failure {
        val latest = try {
            stopHeartbeatAndGetLatest(leaseGuard)
        } catch (_: LeaseLostException) {
            return leaseLossFailure(leaseGuard, fallback, stepReports)
        }
        persistCompletionSafely(latest, BatchStatus.FAILED, failure)
        return BatchReport.Failure(
            latest.copy(
                params = if (sanitize) emptyMap() else latest.params,
                status = BatchStatus.FAILED,
                ownerId = null,
                leaseUntil = null,
            ),
            if (sanitize) {
                stepReports.map { it.sanitizeForInfrastructureFailure() }
            } else {
                stepReports
            },
            failure,
        )
    }

    private suspend fun leaseLossFailure(
        leaseGuard: BatchLeaseGuard,
        fallback: JobExecution,
        stepReports: List<StepReport>,
    ): BatchReport.Failure {
        val latest = stopHeartbeatAndGetDiagnostic(leaseGuard, fallback)
        val correlationId = UUID.randomUUID().toString()
        return BatchReport.Failure(
            latest.sanitizeForInfrastructureFailure(),
            stepReports.map { it.sanitizeForInfrastructureFailure(correlationId) },
            BatchInfrastructureFailureException(
                BatchInfrastructureFailureException.LEASE_LOST,
                correlationId,
            ),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun compensateExternalCancellation(
        leaseGuard: BatchLeaseGuard,
        fallback: JobExecution,
        primary: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeout(leaseGuard.repositoryTimeoutMillis) {
                    leaseGuard.stopHeartbeatInCurrentContext()
                    val latest = runCatching {
                        leaseGuard.latestSnapshotForDiagnostics().jobExecution
                    }.getOrElse { fallback }
                    repository.completeJobExecution(latest, BatchStatus.STOPPED)
                }
            }
        } catch (failure: Throwable) {
            if (failure !== primary) {
                log.error(failure) {
                    "STOPPED 상태 bounded compensation 실패 — job=$name, " +
                        "executionId=${fallback.id}"
                }
                primary.addSuppressed(
                    BatchInfrastructureFailureException(
                        BatchInfrastructureFailureException.REPOSITORY_FAILURE,
                        UUID.randomUUID().toString(),
                    ),
                )
            }
        }
    }

    private fun JobExecution.sanitizeForInfrastructureFailure(): JobExecution = copy(
        params = emptyMap(),
        ownerId = null,
        leaseUntil = null,
    )

    private fun StepReport.sanitizeForInfrastructureFailure(
        correlationId: String = UUID.randomUUID().toString(),
    ): StepReport = copy(
        checkpoint = null,
        error = error?.let {
            BatchInfrastructureFailureException(
                BatchInfrastructureFailureException.LEASE_LOST,
                correlationId,
            )
        },
    )

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistCompletionSafely(
        execution: JobExecution,
        status: BatchStatus,
        primary: Throwable,
    ) {
        try {
            repository.completeJobExecution(execution, status)
        } catch (persistenceCancellation: CancellationException) {
            log.error(persistenceCancellation) {
                "$status 상태 저장 취소됨 — job=$name, executionId=${execution.id}, " +
                    "실행 원인 예외와 함께 전파합니다"
            }
            propagateCompletionCancellation(primary, persistenceCancellation)
        } catch (persistenceFailure: Throwable) {
            preserveCompletionFailure(execution.id, primary, status, persistenceFailure)
        }
    }

    private fun preserveCompletionFailure(
        executionId: Long,
        primary: Throwable,
        status: BatchStatus,
        persistenceFailure: Throwable,
    ) {
        if (persistenceFailure !== primary) {
            primary.addSuppressed(persistenceFailure)
        }
        log.error(persistenceFailure) {
            "$status 상태 저장 실패 — job=$name, executionId=$executionId, " +
                "실행 원인 예외에 suppressed cause로 보존했습니다"
        }
    }

    private fun propagateCompletionCancellation(
        primary: Throwable,
        persistenceCancellation: CancellationException,
    ): Nothing {
        if (persistenceCancellation !== primary) {
            if (primary is CancellationException) {
                primary.addSuppressed(persistenceCancellation)
                throw primary
            }
            persistenceCancellation.addSuppressed(primary)
        }
        throw persistenceCancellation
    }

    /**
     * [SuspendWork] 구현 — Workflow DSL 안에 [BatchJob]을 임베딩합니다.
     *
     * 매핑 규칙:
     * - [BatchReport.Success]            → [WorkReport.success]
     * - [BatchReport.PartiallyCompleted] → [WorkReport.success] + `context["batch.{name}.skipCount"]`
     * - [BatchReport.Failure]            → [WorkReport.failure]
     * - 외부 취소([CancellationException]) → 재던짐 (Workflow 전체 취소 전파)
     *
     * @param context 워크플로 실행 컨텍스트
     * @return [WorkReport]
     */
    override suspend fun execute(context: WorkContext): WorkReport {
        context["batch.${name}.startTime"] = Instant.now()
        return try {
            when (val report = run()) {
                is BatchReport.Success -> {
                    context["batch.${name}.report"] = report
                    WorkReport.success(context)
                }
                is BatchReport.PartiallyCompleted -> {
                    context["batch.${name}.skipCount"] = report.stepReports.sumOf { it.skipCount }
                    context["batch.${name}.report"] = report
                    WorkReport.success(context)
                }
                is BatchReport.Failure -> {
                    WorkReport.failure(context, report.error)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }
}
