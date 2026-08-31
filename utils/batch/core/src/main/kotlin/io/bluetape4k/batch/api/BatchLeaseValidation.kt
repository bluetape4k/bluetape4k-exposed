package io.bluetape4k.batch.api

import java.time.Duration

/** 배치 실행 lease에 허용되는 최소 기간. */
val MIN_BATCH_LEASE_DURATION: Duration = Duration.ofSeconds(30)

/** 배치 실행 lease에 허용되는 최대 기간. */
val MAX_BATCH_LEASE_DURATION: Duration = Duration.ofHours(24)

/** duration 오류를 식별하기 위한 민감 정보 없는 고정 접두사. */
const val INVALID_BATCH_LEASE_DURATION_PREFIX: String =
    "Invalid batch execution lease duration"

/**
 * repository와 runner가 공유하는 실행 lease duration 검증기.
 *
 * 범위 검증과 millisecond 변환 overflow 검증을 한 곳에서 수행해 direct constructor,
 * DSL, repository 호출이 서로 다른 정책을 사용하지 않게 한다.
 */
fun Duration.requireValidBatchLeaseDuration(parameterName: String = "leaseDuration"): Duration {
    try {
        require(this >= MIN_BATCH_LEASE_DURATION && this <= MAX_BATCH_LEASE_DURATION) {
            "$INVALID_BATCH_LEASE_DURATION_PREFIX: $parameterName must be between 30 seconds and 24 hours"
        }
        toMillis()
    } catch (cause: ArithmeticException) {
        throw IllegalArgumentException(
            "$INVALID_BATCH_LEASE_DURATION_PREFIX: $parameterName overflow",
            cause,
        )
    }
    return this
}
