package io.bluetape4k.batch.core

import io.bluetape4k.codec.Base58
import io.bluetape4k.batch.api.BatchJobRepository
import io.bluetape4k.batch.api.BatchExecutionAlreadyClaimedException
import io.bluetape4k.batch.api.BatchInfrastructureFailureException
import io.bluetape4k.batch.api.BatchStatus
import io.bluetape4k.batch.api.JobExecution
import io.bluetape4k.batch.api.StepExecution
import io.bluetape4k.batch.api.StepReport
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [BatchStep]의 chunk 루프 실행 엔진.
 *
 * ## 책임
 * - [BatchJobRepository]와 협력하여 [io.bluetape4k.batch.api.StepExecution]을 조회/생성하고,
 *   이미 완료된 Step은 **즉시** 기존 리포트를 반환한다.
 * - `reader.open()` → (옵션) `restoreFrom(checkpoint)` → chunk 루프 → `writer.close()` /
 *   `reader.close()` 순으로 실행한다.
 * - chunk 단위로 [writeWithTimeout] 기반 write를 수행하고, [io.bluetape4k.workflow.api.RetryPolicy]에 따라
 *   재시도 및 지수 백오프를 적용한다.
 * - chunk-level retry 소진 시 [io.bluetape4k.batch.api.SkipPolicy]를 참조하여 skip 또는 Step FAILED를 결정한다.
 *
 * ## 불변 계약
 * 1. [BatchJobRepository.findOrCreateStepExecution] 결과가 `COMPLETED` 또는 `COMPLETED_WITH_SKIPS`면
 *    reader/writer를 **절대 open하지 않고**, checkpoint 복원도 수행하지 않는다.
 * 2. [CancellationException]은 **절대 삼키지 않는다** — STOPPED 상태 저장 후 항상 즉시 재던진다.
 *    일반적인 상태 저장 실패는 원래 취소 예외의 suppressed cause로 보존하고, 저장 중
 *    cancellation이 발생하면 원인 예외를 suppressed로 연결한 뒤 전파하며 error 로그를 남긴다.
 * 3. `reader.close()` / `writer.close()`는 `finally`의 [NonCancellable] 컨텍스트에서
 *    각각 독립적으로 실행하며, close 실패는 주 예외의 suppressed cause로 보존한다.
 * 4. EOF 판정은 `chunk.isEmpty()`가 아닌 `eofReached` 플래그로 판단한다 — 전부 필터링된 경우와 구분한다.
 * 5. [BatchJobRepository.loadCheckpoint] 결과가 null이면 [io.bluetape4k.batch.api.BatchReader.restoreFrom]을
 *    호출하지 않는다.
 * 6. 소유자 기반 checkpoint 저장은 caller cancellation에 협력한다. 저장 readback을 받은 뒤
 *    guard 기준 데이터를 교체하는 짧은 메모리 연산만 취소 불가 구간에서 수행한다.
 *
 * @param I Reader 출력 타입
 * @param O Writer 입력 타입
 * @property step 실행할 [BatchStep]
 * @property jobExecution 소속 [JobExecution]
 * @property repository 실행 이력을 저장할 [BatchJobRepository]
 */
internal class BatchStepRunner<I : Any, O : Any>(
    private val step: BatchStep<I, O>,
    private val jobExecution: JobExecution,
    private val repository: BatchJobRepository,
    private val leaseDuration: Duration = Duration.ofMinutes(DEFAULT_LEASE_MINUTES),
    private val leaseGuard: BatchLeaseGuard? = null,
    private val monotonicClock: BatchMonotonicClock = SYSTEM_BATCH_MONOTONIC_CLOCK,
) {
    companion object : KLoggingChannel() {
        private const val DEFAULT_LEASE_MINUTES = 15L
    }

    /**
     * [BatchStep]을 실행하고 결과 [StepReport]를 반환한다.
     *
     * @return 실행 결과 [StepReport]
     */
    suspend fun run(): StepReport {
        var primaryFailure: Throwable? = null
        val ownsLeaseGuard = leaseGuard == null
        val activeLeaseGuard = leaseGuard ?: run {
            check(repository.supportsLeaseRenewal) {
                "Batch step runner requires a repository with lease renewal support"
            }
            val claimStartedNanos = monotonicClock.nowNanos()
            val claimed = if (jobExecution.ownerId != null && jobExecution.leaseUntil != null) {
                jobExecution
            } else {
                val fallbackOwnerId = "${jobExecution.jobName}-${step.name}-${Base58.randomString(8)}"
                repository.claimJobExecution(
                    execution = jobExecution,
                    ownerId = fallbackOwnerId,
                    leaseDuration = leaseDuration,
                ) ?: return StepReport(
                    stepName = step.name,
                    status = BatchStatus.FAILED,
                    error = BatchExecutionAlreadyClaimedException(
                        executionType = "Job",
                        executionId = jobExecution.id,
                        ownerId = jobExecution.ownerId,
                    ),
                )
            }
            BatchLeaseGuard(
                repository = repository,
                ownerId = requireNotNull(claimed.ownerId),
                executionLease = leaseDuration,
                initialJobExecution = claimed,
                initialStepExecution = null,
                monotonicClock = monotonicClock,
                initialClaimStartedNanos = claimStartedNanos,
            )
        }
        val claimedJobExecution = activeLeaseGuard.latestSnapshot().jobExecution
        val stepExecution = repository.findOrCreateStepExecution(claimedJobExecution, step.name)

        // (1) 이미 완료된 Step은 즉시 기존 리포트 반환 — reader/writer open 및 checkpoint 복원 금지
        if (stepExecution.status == BatchStatus.COMPLETED ||
            stepExecution.status == BatchStatus.COMPLETED_WITH_SKIPS
        ) {
            log.debug { "Step 이미 완료됨 — 즉시 skip: status=${stepExecution.status}" }
            return StepReport(
                stepName = step.name,
                status = stepExecution.status,
                readCount = stepExecution.readCount,
                writeCount = stepExecution.writeCount,
                skipCount = stepExecution.skipCount,
                checkpoint = stepExecution.checkpoint,
            )
        }

        val ownerId = requireNotNull(claimedJobExecution.ownerId)
        val claimStartedNanos = monotonicClock.nowNanos()
        val claimedStepExecution = repository.claimStepExecution(stepExecution, ownerId, leaseDuration)
            ?: return StepReport(
                stepName = step.name,
                status = BatchStatus.FAILED,
                error = BatchExecutionAlreadyClaimedException(
                    executionType = "Step",
                    executionId = stepExecution.id,
                    ownerId = stepExecution.ownerId,
                ),
            )
        activeLeaseGuard.recordStepExecution(claimedStepExecution, claimStartedNanos)
        if (ownsLeaseGuard) {
            activeLeaseGuard.startHeartbeat(
                CoroutineScope(currentCoroutineContext()),
            )
        }

        var readCount = claimedStepExecution.readCount
        var writeCount = claimedStepExecution.writeCount
        var skipCount = claimedStepExecution.skipCount

        try {
            step.reader.open()
            step.writer.open()

            // (2) checkpoint 조회 — null이 아닐 때만 restoreFrom 호출
            val checkpoint = repository.loadCheckpoint(claimedStepExecution.id)
            if (checkpoint != null) {
                log.debug { "체크포인트 복원 완료" }
                step.reader.restoreFrom(checkpoint)
            }

            var eofReached = false

            mainLoop@ while (!eofReached) {
                val chunk = mutableListOf<O>()

                // chunk 수집 (reader → processor → chunk)
                repeat(step.chunkSize) {
                    if (eofReached) return@repeat
                    val item = step.reader.read()
                    if (item == null) {
                        eofReached = true
                        return@repeat
                    }
                    readCount++

                    val processed: O? = if (step.processor == null) {
                        @Suppress("UNCHECKED_CAST")
                        item as O
                    } else {
                        try {
                            step.processor.process(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (step.skipPolicy.shouldSkip(e, skipCount)) {
                                skipCount++
                                log.warn {
                                    "processor.process() 실패 — skip: skipCount=$skipCount, " +
                                        "errorType=${e::class.simpleName}"
                                }
                                null
                            } else {
                                throw e
                            }
                        }
                    }

                    if (processed != null) {
                        chunk.add(processed)
                    }
                }

                // EOF + 빈 chunk → 루프 종료
                if (chunk.isEmpty() && eofReached) break@mainLoop
                // 전부 필터링되었지만 EOF는 아님 → 다음 윈도우
                if (chunk.isEmpty()) continue@mainLoop

                // (3) writer + retry 루프
                var attempts = 0
                var currentDelay = step.retryPolicy.delay
                var writerSucceeded = false
                writerLoop@ while (true) {
                    attempts++
                    try {
                        activeLeaseGuard.withWritePermit {
                            writeWithTimeout(step.writer, chunk, step.commitTimeout)
                        }
                        writeCount += chunk.size
                        writerSucceeded = true
                        break@writerLoop
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (attempts < step.retryPolicy.maxAttempts) {
                            log.warn {
                                "writer.write() 실패 — 재시도 예정: " +
                                    "attempt=$attempts/${step.retryPolicy.maxAttempts}, delay=$currentDelay, " +
                                    "errorType=${e::class.simpleName}"
                            }
                            if (currentDelay.isPositive()) {
                                delay(currentDelay)
                            }
                            currentDelay = minOf(
                                currentDelay * step.retryPolicy.backoffMultiplier,
                                step.retryPolicy.maxDelay,
                            )
                            continue@writerLoop
                        }

                        // retry 소진 → chunk-level skipPolicy 평가
                        if (step.skipPolicy.shouldSkip(e, skipCount)) {
                            skipCount += chunk.size
                            log.warn {
                                "writer.write() retry 소진 — chunk skip: " +
                                    "chunkSize=${chunk.size}, skipCount=$skipCount, " +
                                    "errorType=${e::class.simpleName}"
                            }
                            break@writerLoop
                        }
                        throw e
                    }
                }

                // writer 실패를 skip한 chunk는 커밋된 것으로 간주하지 않는다.
                // reader 내부 fetch 포인터는 다음 읽기를 위해 진행될 수 있지만,
                // 재시작 기준인 lastCommittedKey/checkpoint는 마지막 성공 chunk에 남긴다.
                if (!writerSucceeded) continue@mainLoop

                // writer 성공 이후의 reader advancement와 checkpoint 저장은 writer retry 범위 밖이다.
                // 외부 side effect가 발생한 뒤 checkpoint가 실패해도 같은 chunk를 재전달하지 않는다.
                step.reader.onChunkCommitted()
                step.reader.checkpoint()?.let { cp ->
                    // repository I/O는 caller cancellation에 협력한다. 성공한 readback을
                    // guard 기준 데이터로 교체하는 짧은 메모리 연산만 비취소 구간이다.
                    activeLeaseGuard.saveCheckpoint(cp)
                }
            }

            val finalStatus = if (skipCount > 0) BatchStatus.COMPLETED_WITH_SKIPS
            else BatchStatus.COMPLETED

            val stepReport = StepReport(
                stepName = step.name,
                status = finalStatus,
                readCount = readCount,
                writeCount = writeCount,
                skipCount = skipCount,
                checkpoint = step.reader.checkpoint(),
            )
            activeLeaseGuard.completeStepExecution { completionExecution ->
                repository.completeStepExecution(completionExecution, stepReport)
            }
            return stepReport
        } catch (e: LeaseLostException) {
            primaryFailure = e
            throw e
        } catch (e: CancellationException) {
            // 취소 → STOPPED 저장 후 즉시 재던짐 — 절대 삼키지 않음
            primaryFailure = e
            val stoppedReport = StepReport(
                stepName = step.name,
                status = BatchStatus.STOPPED,
                readCount = readCount,
                writeCount = writeCount,
                skipCount = skipCount,
            )
            compensateExternalCancellation(activeLeaseGuard, stoppedReport, e)
            throw e
        } catch (e: Throwable) {
            primaryFailure = e
            val failedReport = try {
                StepReport(
                    stepName = step.name,
                    status = BatchStatus.FAILED,
                    readCount = readCount,
                    writeCount = writeCount,
                    skipCount = skipCount,
                    checkpoint = checkpointForFailure(e),
                    error = e,
                )
            } catch (cancellation: CancellationException) {
                primaryFailure = cancellation
                val stoppedReport = StepReport(
                    stepName = step.name,
                    status = BatchStatus.STOPPED,
                    readCount = readCount,
                    writeCount = writeCount,
                    skipCount = skipCount,
                )
                compensateExternalCancellation(activeLeaseGuard, stoppedReport, cancellation)
                propagateCompletionCancellation(e, cancellation)
            }
            activeLeaseGuard.completeStepExecution { completionExecution ->
                completeStepExecutionSafely(completionExecution, failedReport, e)
            }
            return failedReport
        } finally {
            withContext(NonCancellable) {
                if (ownsLeaseGuard) {
                    activeLeaseGuard.stopHeartbeat()
                }
                closeSafely("reader", primaryFailure) { step.reader.close() }
                closeSafely("writer", primaryFailure) { step.writer.close() }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun checkpointForFailure(primary: Throwable): Any? = try {
        step.reader.checkpoint()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        if (failure !== primary) {
            primary.addSuppressed(failure)
        }
        log.warn(failure) { "실패 상태 checkpoint 조회 실패 — suppressed cause로 보존" }
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun compensateExternalCancellation(
        guard: BatchLeaseGuard,
        report: StepReport,
        primary: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeout(guard.repositoryTimeoutMillis) {
                    guard.completeStepExecution { completionExecution ->
                        repository.completeStepExecution(completionExecution, report)
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure !== primary) {
                log.error(failure) {
                    "STOPPED 상태 bounded compensation 실패 — step=${step.name}"
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun closeSafely(
        resource: String,
        primary: Throwable?,
        close: suspend () -> Unit,
    ) {
        try {
            close()
        } catch (failure: Throwable) {
            if (primary != null && failure !== primary) {
                primary.addSuppressed(failure)
            }
            log.warn(failure) { "$resource close 실패" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun completeStepExecutionSafely(
        execution: StepExecution,
        report: StepReport,
        primary: Throwable,
    ) {
        try {
            repository.completeStepExecution(execution, report)
        } catch (persistenceCancellation: CancellationException) {
            log.error(persistenceCancellation) {
                "${report.status} 상태 저장 취소됨 — step=${step.name}, executionId=${execution.id}, " +
                    "실행 원인 예외와 함께 전파합니다"
            }
            propagateCompletionCancellation(primary, persistenceCancellation)
        } catch (persistenceFailure: Throwable) {
            preserveCompletionFailure(execution.id, primary, report.status, persistenceFailure)
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
            "$status 상태 저장 실패 — step=${step.name}, executionId=$executionId, " +
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
}
