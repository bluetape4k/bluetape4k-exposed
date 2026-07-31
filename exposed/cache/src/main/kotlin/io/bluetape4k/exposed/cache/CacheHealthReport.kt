package io.bluetape4k.exposed.cache

import java.io.Serializable

/** Cache background worker의 lifecycle 상태입니다. */
enum class CacheWorkerState {

    /** 현재 cache write mode가 background worker를 사용하지 않습니다. */
    NOT_APPLICABLE,

    /** Worker가 준비되었지만 진행 중인 작업은 없습니다. */
    IDLE,

    /** Worker가 시작되었고 write를 받은 뒤에도 계속 처리할 수 있습니다. */
    RUNNING,

    /** Worker가 중지하기 전에 queue에 남은 write를 마무리하고 있습니다. */
    DRAINING,

    /** Worker가 복구 불가능한 failure를 만났거나 cancellation에 진입했거나 정상적으로 중지되지 못했습니다. */
    FAILED,

    /** Worker가 shutdown을 완료했으며 더 이상 작업을 처리하지 않습니다. */
    STOPPED,
}

/**
 * Cache worker의 health와 consistency 상태 snapshot입니다.
 *
 * @property mode 설정된 cache write mode입니다.
 * @property queueDepth 접수되었지만 아직 flush 완료가 관찰되지 않은 write-behind entry 개수입니다.
 * @property workerState cache background worker의 현재 lifecycle 상태입니다.
 * @property lastFlushError 마지막 non-cancellation flush failure입니다. 마지막 flush가 성공했으면 `null`입니다.
 */
data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val lastFlushError: Throwable?,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -1428853048381429257L
    }
}
