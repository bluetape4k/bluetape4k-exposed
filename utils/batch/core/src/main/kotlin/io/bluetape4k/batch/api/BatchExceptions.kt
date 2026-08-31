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

/**
 * 제한된 repository 복구 횟수 안에서 active winner를 확인하지 못한 경우.
 *
 * [correlationId]는 로그와 운영 진단을 연결하기 위한 16자 Base58 문자열이다.
 * 예외에는 backend 원인이나 실행 입력을 포함하지 않는다.
 */
class BatchRepositoryRecoveryExhaustedException(
    val correlationId: String,
) : IllegalStateException(
    "Batch repository recovery budget exhausted; correlationId=$correlationId",
) {
    init {
        require(correlationId.length == CORRELATION_ID_LENGTH && correlationId.all { it in BASE58_ALPHABET }) {
            "correlationId must be a 16-character Base58 string"
        }
    }

    companion object {
        private const val CORRELATION_ID_LENGTH = 16
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }
}
