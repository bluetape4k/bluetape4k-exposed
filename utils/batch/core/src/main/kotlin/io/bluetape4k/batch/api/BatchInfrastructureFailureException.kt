package io.bluetape4k.batch.api

import java.util.UUID

/**
 * 외부에 내부 owner·SQL·driver 상세를 노출하지 않는 배치 인프라 실패.
 *
 * 운영 자동화는 [category]와 [correlationId]만 사용해 알림·재조정 여부를 판단한다.
 */
class BatchInfrastructureFailureException(
    val category: String,
    val correlationId: String,
) : RuntimeException("$category; correlationId=$correlationId") {

    init {
        require(category in ALLOWED_CATEGORIES) { "Unsupported batch infrastructure failure category" }
        require(
            correlationId == correlationId.lowercase() &&
                runCatching { UUID.fromString(correlationId) }.isSuccess,
        ) {
            "Batch infrastructure failure correlationId must be a canonical UUID"
        }
    }

    companion object {
        const val LEASE_LOST = "BATCH_LEASE_LOST"
        const val EXECUTION_ALREADY_CLAIMED = "BATCH_EXECUTION_ALREADY_CLAIMED"
        const val REPOSITORY_FAILURE = "BATCH_REPOSITORY_FAILURE"

        private val ALLOWED_CATEGORIES = setOf(
            LEASE_LOST,
            EXECUTION_ALREADY_CLAIMED,
            REPOSITORY_FAILURE,
        )
    }
}
