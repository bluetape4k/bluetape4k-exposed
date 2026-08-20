package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.redisson.api.AsyncIterator
import org.redisson.api.map.MapLoaderAsync
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeoutException

/**
 * R2DBC 트랜잭션 안에서 DB 조회 함수를 실행하는 Redisson 비동기 [MapLoaderAsync] 구현입니다.
 *
 * ## 동작/계약
 * - [load]는 `suspendTransaction`에서 [loadByIdFromDB]를 실행해 단건 엔티티를 조회합니다.
 * - [loadAllKeys]는 채널 기반 [AsyncIterator]를 반환하고, 백그라운드 코루틴에서 [loadAllIdsFromDB]를 실행합니다.
 * - 전체 키 로딩은 60초 타임아웃과 top-level `maxAttempts = 1`을 적용합니다. 전자는
 *   무한 대기를 막고 후자는 transaction retry가 이미 전송한 ID를 재방출하는 것을 막습니다.
 * - rendezvous channel은 한 번에 하나의 ID만 전달해 producer back-pressure를 보장하며,
 *   caller가 loader에 주입한 scope를 취소하면 producer transaction까지 취소가 전파됩니다.
 *   기본 shared scope는 caller coroutine과 독립적입니다. producer 오류와 timeout은
 *   정상적인 `hasNext() == false`가 아니라 iterator 예외로 전달됩니다.
 * - caller가 현재 transaction context를 보존한 scope를 전달하면 ambient transaction의
 *   retry 정책을 caller가 소유합니다. outer retry 뒤 partial ID가 다시 관찰될 수 있습니다.
 *   정확히 한 번의 관찰이 필요하면 중복 제거·멱등 처리를 적용하거나 성공 전 외부
 *   side effect를 buffer해야 하며, 전체 열거 재시도는 completeness만 복구합니다.
 * - producer 예외와 timeout 원인은 채널 close cause로 보존하며, iterator의 [AsyncIterator.hasNext]
 *   및 [AsyncIterator.next]가 이를 정상 종료(false)와 구분해 비동기 예외로 전달합니다.
 * - 운영 로그에는 caller-owned ID, 엔티티 payload, 예외 message를 기록하지 않습니다.
 * - 기본 scope는 [SupervisorJob]을 사용해 한 번의 `load` 실패가 다른 loader 호출을 취소하지 않도록 합니다.
 *
 * ```kotlin
 * val loader = R2dbcEntityMapLoader<Long, LoaderEntity>(
 *     loadByIdFromDB = { id -> repo.findByIdFromDb(id) },
 *     loadAllIdsFromDB = { ch -> ids.forEach { ch.send(it) } }
 * )
 * // loader.loadAllKeys().toList().isNotEmpty() == true
 * ```
 *
 * @param loadByIdFromDB ID로 엔티티를 로드하는 함수
 * @param loadAllIdsFromDB 모든 ID를 로드하는 함수
 * @param scope CoroutineScope
 */
open class R2dbcEntityMapLoader<ID: Any, E: Any>(
    private val loadByIdFromDB: suspend (ID) -> E?,
    private val loadAllIdsFromDB: suspend (channel: Channel<ID>) -> Unit,
    private val scope: CoroutineScope = defaultMapLoaderCoroutineScope,
): MapLoaderAsync<ID, E> {
    companion object: KLoggingChannel() {
        private const val DEFAULT_QUERY_TIMEOUT = 30_000 // Exposed queryTimeout 단위: seconds; #699에서 정리
        private const val DEFAULT_LOAD_ALL_IDS_TIMEOUT = 60_000L // 60 seconds

        protected val defaultMapLoaderCoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("R2dbc-Loader"))
    }

    override fun load(id: ID): CompletionStage<E?> =
        scope
            .async {
                log.debug { "DB에서 단건 엔티티 로드를 시작합니다." }
                suspendTransaction {
                    try {
                        loadByIdFromDB(id)
                            .apply {
                                log.debug { "DB에서 단건 엔티티 로드를 완료했습니다. found=${this != null}" }
                            }
                    } catch (e: CancellationException) {
                        // 코루틴 취소는 반드시 재전파해야 한다 — 삼키면 구조적 동시성이 깨진다
                        throw e
                    } catch (e: Throwable) {
                        log.error { "DB에서 단건 엔티티 로드 중 오류가 발생했습니다." }
                        throw e
                    }
                }
            }.asCompletableFuture()

    @Suppress("TooGenericExceptionCaught")
    override fun loadAllKeys(): AsyncIterator<ID> {
        // WHY: RENDEZVOUS(버퍼 없음) — 소비자(AsyncIterator)가 준비될 때만 생산자가 진행한다.
        //      버퍼를 두면 DB에서 읽은 ID가 메모리에 쌓여 OOM 위험이 있으므로 백프레셔를 강제한다.
        val channel =
            Channel<ID>(Channel.RENDEZVOUS).also {
                it.invokeOnClose { cause ->
                    log.debug { "단건 ID 채널이 닫혔습니다. failed=${cause != null}" }
                }
            }

        scope.launch {
            log.debug { "DB에서 모든 ID를 로딩합니다 ..." }
            try {
                // 호출자가 transaction context를 보존하는 scope를 제공한 경우에는 outer transaction의
                // retry 정책을 caller가 소유한다. 기본 scope는 context를 공유하지 않으므로 top-level이다.
                val hasOuterTransaction = TransactionManager.currentOrNull() != null
                suspendTransaction {
                    this.queryTimeout = DEFAULT_QUERY_TIMEOUT // 단위/30초 정책은 후속 Issue #699에서 정리
                    // WHY: streaming은 transaction retry가 이미 전송한 ID를 재방출할 수 있으므로
                    //      top-level transaction에서만 retry를 끈다. outer transaction에서는
                    //      caller가 소유한 retry 정책을 그대로 둔다.
                    if (!hasOuterTransaction) {
                        this.maxAttempts = 1
                    }
                    // WHY: withTimeoutOrNull — 전체 키 로딩이 60초를 초과하면 원인을 채널에 보존해
                    //      소비자가 무한 대기하거나 partial enumeration을 성공으로 오인하지 않게 한다.
                    withTimeoutOrNull(DEFAULT_LOAD_ALL_IDS_TIMEOUT) {
                        loadAllIdsFromDB(channel)
                    } ?: run {
                        val timeout = TimeoutException(
                            "Loading all IDs exceeded $DEFAULT_LOAD_ALL_IDS_TIMEOUT ms",
                        )
                        log.warn { "DB에서 모든 ID를 읽는 작업 중 Timeout 이 발생했습니다. timeout=$DEFAULT_LOAD_ALL_IDS_TIMEOUT msec" }
                        channel.close(timeout)
                    }
                }
            } catch (e: CancellationException) {
                // 코루틴 취소는 반드시 재전파해야 한다 — 삼키면 구조적 동시성이 깨진다
                channel.close(e)
                throw e
            } catch (e: Error) {
                // fatal Error는 iterator에도 전달하되 CoroutineExceptionHandler까지 재전파한다.
                channel.close(e)
                throw e
            } catch (e: Exception) {
                log.error { "DB에서 모든 ID 로딩 중 오류가 발생했습니다." }
                channel.close(e)
            } finally {
                // 정상 완료(오류 없음) 시 채널을 닫는다; 오류 경로에서는 이미 위에서 닫혔다
                channel.close()
            }
        }

        return object: AsyncIterator<ID> {
            // 채널에서 미리 받아 둔 결과를 저장. hasNext() 이후 next() 에서 재사용한다
            private var pendingReceive: CompletableFuture<ChannelResult<ID>>? = null

            private fun ensurePending(): CompletableFuture<ChannelResult<ID>> =
                pendingReceive ?: scope
                    .async {
                        channel.receiveCatching()
                    }.asCompletableFuture()
                    .also { pendingReceive = it }

            override fun hasNext(): CompletionStage<Boolean?> =
                ensurePending()
                    .thenApply { result ->
                        // 더 이상 원소가 없으면 pending 을 초기화해 메모리 누수를 방지한다
                        if (!result.isSuccess) {
                            pendingReceive = null
                        }
                        result.exceptionOrNull()?.let { throw it }
                        result.isSuccess
                    }

            override fun next(): CompletionStage<ID> =
                ensurePending()
                    .thenApply { result ->
                        pendingReceive = null
                        result.exceptionOrNull()?.let { throw it }
                        result.getOrNull() ?: throw NoSuchElementException("No more elements")
                    }
        }
    }
}
