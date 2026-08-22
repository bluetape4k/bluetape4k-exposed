package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.redisson.api.AsyncIterator
import org.redisson.api.map.MapLoaderAsync
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * Exposed를 사용하여 DB에서 데이터를 비동기적으로 로드하는 Redisson [MapLoaderAsync] 구현입니다.
 *
 * ## 동작/계약
 * - [load]는 `suspendTransaction`으로 [loadByIdFromDB]를 실행해 단건 엔티티를 읽고 [CompletionStage]로 반환합니다.
 * - [loadAllKeys]는 [Channel]을 통해 [loadAllIdsFromDB]가 생산하는 ID를 [AsyncIterator]로 스트리밍합니다.
 * - 채널 내부에서 `maxAttempts = 1`, statement `queryTimeout = 30초`,
 *   `withTimeout(DEFAULT_LOAD_ALL_IDS_TIMEOUT)` 보호막을 사용합니다. ID를 channel에
 *   방출한 뒤 transaction retry를 수행하면 이미 관찰된 ID가 중복될 수 있으므로,
 *   재시도가 필요하면 호출자가 전체 열거를 다시 시작해야 합니다.
 * - [AsyncIterator]는 rendezvous channel back-pressure를 사용하며, caller cancellation과
 *   producer 오류/timeout을 정상적인 끝(`hasNext() == false`)과 구분해 전달합니다.
 * - Exposed [org.jetbrains.exposed.v1.core.dao.id.IdTable]의 custom ID는 concrete loader에서
 *   offset fallback으로 처리하고, 표준 scalar ID는 keyset page를 사용합니다.
 * - 일반 DB 오류나 채널 실패는 channel cause로 consumer에 전달하고, fatal [Error]는
 *   coroutine exception handler까지 재전파합니다.
 * - 운영 로그에는 caller-owned ID, 엔티티 payload, 예외 message를 기록하지 않습니다.
 *
 * ```kotlin
 * val loader = SuspendedEntityMapLoader<Long, UserRecord>(
 *     loadByIdFromDB = { id -> repo.findByIdFromDb(id) },
 *     loadAllIdsFromDB = { channel ->
 *         repo.findAllIds().forEach { channel.send(it) }
 *     }
 * )
 * ```
 *
 * @param ID ID 타입
 * @param E 엔티티 타입
 * @param loadByIdFromDB ID로 엔티티를 로드하는 suspend 함수
 * @param loadAllIdsFromDB 모든 ID를 [Channel]에 전송하는 suspend 함수
 * @param scope DB 조회 및 채널 처리에 사용할 [CoroutineScope]. 기본값은 `Dispatchers.IO` 기반 스코프입니다.
 */
// WHY: @Suppress("DEPRECATION") — loadAllKeys()의 Channel<ID>(Channel.RENDEZVOUS) API가
//      kotlinx.coroutines 1.8+ 에서 experimental → stable로 이동 중이며, 아직 일부 IDE/컴파일러가
//      deprecated 경고를 내기 때문에 일시적으로 억제합니다. API 자체는 안정적으로 유지됩니다.
open class SuspendedEntityMapLoader<ID: Any, E: Any>(
    private val loadByIdFromDB: suspend (ID) -> E?,
    private val loadAllIdsFromDB: suspend (channel: Channel<ID>) -> Unit,
    private val scope: CoroutineScope = defaultMapLoaderCoroutineScope,
): MapLoaderAsync<ID, E> {
    companion object: KLoggingChannel() {
        private const val DEFAULT_QUERY_TIMEOUT_SECONDS = 30
        private const val DEFAULT_LOAD_ALL_IDS_TIMEOUT = 60_000L // 60 seconds

        protected val defaultMapLoaderCoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO) + CoroutineName("DB-Loader")
    }

    /**
     * 단건 엔티티를 DB에서 비동기적으로 로드합니다.
     *
     * - [suspendTransaction]으로 [loadByIdFromDB]를 실행합니다.
     * - [kotlinx.coroutines.CancellationException]은 반드시 재전파해 코루틴 취소가 정상 동작하도록 합니다.
     *
     * @param id 로드할 엔티티의 ID
     * @return 엔티티를 담은 [CompletionStage]. 존재하지 않으면 null.
     */
    override fun load(id: ID): CompletionStage<E?> =
        scope
            .async {
                log.debug { "DB에서 단건 엔티티 로드를 시작합니다." }
                withContext(scope.coroutineContext) {
                    suspendTransaction {
                        try {
                            loadByIdFromDB(id)
                                .apply {
                                    log.debug { "DB에서 단건 엔티티 로드를 완료했습니다. found=${this != null}" }
                                }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // CancellationException 은 코루틴 취소 신호이므로 반드시 재전파해야 합니다.
                            throw e
                        } catch (e: Throwable) {
                            log.error { "DB에서 단건 엔티티 로드 중 오류가 발생했습니다." }
                            throw e
                        }
                    }
                }
            }.asCompletableFuture()

    /**
     * DB의 모든 키를 [AsyncIterator]로 스트리밍합니다.
     *
     * - RENDEZVOUS Channel을 생산자(DB 조회 coroutine)와 소비자([AsyncIterator]) 사이의 back-pressure 파이프로 사용합니다.
     * - [kotlinx.coroutines.CancellationException]은 `cause`에 저장 후 재전파하여 `channel.close(cause)`로
     *   소비자가 정상 종료 대신 오류를 감지하도록 합니다.
     * - 정상 종료 시에는 `channel.close(null)`이 호출되어 [AsyncIterator.hasNext]가 `false`를 반환합니다.
     */
    override fun loadAllKeys(): AsyncIterator<ID> {
        val channel =
            Channel<ID>(Channel.RENDEZVOUS).also {
                it.invokeOnClose { cause ->
                    log.debug { "단건 ID 채널이 닫혔습니다. failed=${cause != null}" }
                }
            }

        scope.launch {
            log.debug { "DB에서 모든 ID를 로딩합니다 ..." }
            // WHY: cause를 별도 변수로 보존하는 이유 — finally 블록에서 channel.close(cause)를 호출할 때
            //      예외 인스턴스가 필요하기 때문입니다. 단순 channel.close()는 정상 종료와 구분할 수 없습니다.
            var cause: Throwable? = null
            try {
                withContext(scope.coroutineContext) {
                    suspendTransaction {
                        // channel 방출은 외부 부작용이므로 transaction retry로 안전하게 재생할 수 없다.
                        this.maxAttempts = 1
                        this.queryTimeout = DEFAULT_QUERY_TIMEOUT_SECONDS
                        withTimeout(DEFAULT_LOAD_ALL_IDS_TIMEOUT) {
                            loadAllIdsFromDB(channel)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is TimeoutCancellationException) {
                    log.warn { "DB에서 모든 ID를 읽는 작업 중 Timeout 이 발생했습니다. timeout=$DEFAULT_LOAD_ALL_IDS_TIMEOUT msec" }
                }
                // CancellationException 은 코루틴 취소 신호이므로 반드시 재전파합니다.
                cause = e
                throw e
            } catch (e: Throwable) {
                log.error { "DB에서 모든 ID 로딩 중 오류가 발생했습니다." }
                cause = e
                // DB 오류는 channel cause로 consumer에 전달한다. 일반 예외를 이 child 밖으로
                // 재전파하면 caller-owned 일반 Job까지 취소되어 다음 enumeration을 막는다.
                if (e is Error) throw e
            } finally {
                // 예외 발생 시 cause 를 전달해 채널 소비자가 오류를 감지하도록 합니다.
                channel.close(cause)
            }
        }

        return object: AsyncIterator<ID> {
            private var pendingReceive: CompletableFuture<ChannelResult<ID>>? = null

            private fun ensurePending(): CompletableFuture<ChannelResult<ID>> =
                pendingReceive ?: scope
                    .async {
                        channel.receiveCatching()
                    }.asCompletableFuture()
                    .also { pendingReceive = it }

            override fun hasNext(): CompletionStage<Boolean?> =
                ensurePending().thenApply { result ->
                    result.exceptionOrNull()?.let { cause ->
                        throw CompletionException(cause)
                    }
                    result.isSuccess
                }

            override fun next(): CompletionStage<ID> =
                ensurePending().thenApply { result ->
                    pendingReceive = null
                    result.exceptionOrNull()?.let { cause ->
                        throw CompletionException(cause)
                    }
                    result.getOrNull() ?: throw NoSuchElementException("No more elements")
                }
        }
    }
}
