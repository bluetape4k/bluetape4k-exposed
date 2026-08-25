package io.bluetape4k.exposed.r2dbc.redisson.map

import org.redisson.api.AsyncIterator

/**
 * Redisson [AsyncIterator]의 소비자 취소와 자원 해제를 명시적으로 연결하는 iterator 계약입니다.
 */
interface R2dbcCloseableAsyncIterator<T>: AsyncIterator<T>, AutoCloseable {
    /** 취소된 producer의 transaction cleanup이 끝날 때까지 기다립니다. */
    suspend fun closeAndJoin() {
        close()
    }
}
