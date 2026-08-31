package io.bluetape4k.batch.core

import io.bluetape4k.batch.api.requireValidBatchLeaseDuration
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * lease heartbeat와 repository timeout에 사용하는 millisecond 경계를 계산한다.
 *
 * 모든 결과는 millisecond 정밀도로 내림하여 heartbeat와 timeout이 서로 다른 단위를
 * 사용하지 않게 한다. public lease 범위가 제한되어 있어 계산 결과의 overflow도
 * 검증 단계에서 차단된다.
 */
internal data class BatchLeaseTiming(
    val leaseMillis: Long,
    val repositoryTimeoutMillis: Long,
    val safeMarginMillis: Long,
    val heartbeatIntervalMillis: Long,
    val latencyAlertThresholdMillis: Long,
)

internal object BatchLeaseCalculator {
    private const val MAX_REPOSITORY_TIMEOUT_MILLIS = 30_000L

    fun calculate(executionLease: Duration): BatchLeaseTiming {
        executionLease.requireValidBatchLeaseDuration("executionLease")
        val leaseMillis = try {
            executionLease.toMillis()
        } catch (cause: ArithmeticException) {
            throw IllegalArgumentException(
                "Invalid batch execution lease duration: overflow",
                cause,
            )
        }
        val repositoryTimeoutMillis = min(leaseMillis / 6L, MAX_REPOSITORY_TIMEOUT_MILLIS)
        val safeMarginMillis = max(
            leaseMillis / 3L,
            Math.multiplyExact(repositoryTimeoutMillis, 2L),
        )
        val latencyAlertThresholdMillis = min(
            leaseMillis / 12L,
            Math.multiplyExact(repositoryTimeoutMillis, 4L) / 5L,
        )
        return BatchLeaseTiming(
            leaseMillis = leaseMillis,
            repositoryTimeoutMillis = repositoryTimeoutMillis,
            safeMarginMillis = safeMarginMillis,
            heartbeatIntervalMillis = leaseMillis / 3L,
            latencyAlertThresholdMillis = latencyAlertThresholdMillis,
        )
    }
}
