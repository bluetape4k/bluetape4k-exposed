package io.bluetape4k.batch.api

import io.bluetape4k.logging.KLogging

/**
 * Step 실행이 FAILED 상태로 종료될 때 던지는 예외.
 *
 * [StepReport]를 함께 담아 실패 원인과 통계를 상위로 전달한다.
 *
 * ```kotlin
 * throw BatchStepFailedException(
 *     stepReport = StepReport(
 *         stepName = "importUsers",
 *         status = BatchStatus.FAILED,
 *         readCount = 1000L,
 *         writeCount = 900L,
 *         skipCount = 0L,
 *         error = cause,
 *     )
 * )
 * ```
 *
 * @property stepReport 실패한 Step의 실행 결과 보고서
 */
class BatchStepFailedException(
    val stepReport: StepReport,
    cause: Throwable? = stepReport.error,
) : RuntimeException(
    "Step '${stepReport.stepName}' failed: status=${stepReport.status}",
    cause,
) {
    companion object : KLogging()
}

/**
 * Job/Step 실행 row가 다른 runner에 의해 이미 claim된 경우.
 */
@Suppress("UnusedPrivateProperty")
class BatchExecutionAlreadyClaimedException(
    executionType: String,
    executionId: Long,
    ownerId: String?,
) : RuntimeException(
    "$executionType execution is already claimed: id=$executionId",
) {
    companion object : KLogging()
}
