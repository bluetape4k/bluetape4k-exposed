package io.bluetape4k.exposed.cache

import java.io.Serializable

/** cache background worker의 lifecycle 상태입니다. */
enum class CacheWorkerState {

    /** 현재 cache write mode가 background worker를 사용하지 않습니다. */
    NOT_APPLICABLE,

    /** worker가 준비됐지만 진행 중인 작업은 없습니다. */
    IDLE,

    /** worker가 시작됐으며 write를 수락한 뒤에도 계속 사용할 수 있습니다. */
    RUNNING,

    /** worker가 중지되기 전에 queue의 write를 마무리하고 있습니다. */
    DRAINING,

    /** worker에 terminal failure가 발생했거나 cancellation에 진입했거나 정상적으로 중지되지 못했습니다. */
    FAILED,

    /** worker가 shutdown을 완료했으며 더는 작업을 처리하지 않습니다. */
    STOPPED,
}

/**
 * cache worker의 health 및 consistency 상태 snapshot입니다.
 *
 * @property mode 설정된 cache write mode
 * @property queueDepth 수락했지만 아직 flush 완료가 관찰되지 않은 write-behind entry 개수
 * @property workerState cache background worker의 현재 lifecycle 상태
 * @property lastFlushError 마지막 non-cancellation flush 실패. 마지막 flush가 성공했으면 `null`
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
