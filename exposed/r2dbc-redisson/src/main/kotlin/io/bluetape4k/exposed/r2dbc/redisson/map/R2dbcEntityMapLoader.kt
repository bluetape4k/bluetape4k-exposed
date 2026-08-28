package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.redisson.api.AsyncIterator
import org.redisson.api.map.MapLoaderAsync
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R2DBC 트랜잭션 안에서 DB 조회 함수를 실행하는 Redisson 비동기 [MapLoaderAsync] 구현입니다.
 *
 * ## 동작/계약
 * - [load]는 `suspendTransaction`에서 [loadByIdFromDB]를 실행해 단건 엔티티를 조회합니다.
 * - [loadAllKeys]는 채널 기반 [AsyncIterator]를 반환하고, 백그라운드 코루틴에서 [loadAllIdsFromDB]를 실행합니다.
 * - [loadAllKeys]가 실행하는 각 DB statement에는 Exposed transaction의 `queryTimeout` 30초(단위: 초)를 적용하고,
 *   전체 키 로딩에는 별도의 60초 타임아웃과 top-level `maxAttempts = 1`을 적용합니다.
 *   전체 열거 timeout은 statement timeout과 독립적으로 무한 대기를 막고, `maxAttempts = 1`은
 *   transaction retry가 이미 전송한 ID를 재방출하는 것을 막습니다.
 * - rendezvous channel은 한 번에 하나의 ID만 전달해 producer back-pressure를 보장하며,
 *   caller가 loader에 주입한 scope를 취소하면 producer transaction까지 취소가 전파됩니다.
 *   loader는 caller scope의 context를 유지하는 linked `SupervisorJob`을 소유하며 [close]에서
 *   모든 producer와 pending receive를 취소합니다. [closeAndJoin]은 transaction cleanup 완료까지 기다립니다.
 *   producer 오류와 timeout은
 *   정상적인 `hasNext() == false`가 아니라 iterator 예외로 전달됩니다.
 * - caller가 현재 transaction context를 보존한 scope를 전달하면 ambient transaction의
 *   retry 정책을 caller가 소유합니다. outer retry 뒤 partial ID가 다시 관찰될 수 있습니다.
 *   정확히 한 번의 관찰이 필요하면 중복 제거·멱등 처리를 적용하거나 성공 전 외부
 *   side effect를 buffer해야 하며, 전체 열거 재시도는 completeness만 복구합니다.
 * - producer 예외와 timeout 원인은 채널 close cause로 보존하며, iterator의 [AsyncIterator.hasNext]
 *   및 [AsyncIterator.next]가 이를 정상 종료(false)와 구분해 비동기 예외로 전달합니다.
 * - 운영 로그에는 caller-owned ID, 엔티티 payload, 예외 객체/메시지/stack trace를 기록하지 않으며,
 *   오류 진단에는 작업명과 안전한 예외 타입만 남깁니다.
 * - 기본 scope와 loader lifecycle scope는 [SupervisorJob]을 사용해 한 번의 `load` 실패가 다른 loader 호출을 취소하지 않도록 합니다.
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
    private val scope: CoroutineScope = newR2dbcMapCoroutineScope("R2dbc-Loader"),
): MapLoaderAsync<ID, E>, AutoCloseable {
    private val lifecycleScope = scope.linkedSupervisorScope("R2dbc-Loader")
    private val lifecycleJob = requireNotNull(lifecycleScope.coroutineContext[Job])
    private val closed = AtomicBoolean(false)

    companion object: KLoggingChannel() {
        private const val DEFAULT_QUERY_TIMEOUT_SECONDS = 30
        private const val DEFAULT_LOAD_ALL_IDS_TIMEOUT = 60_000L // 60 seconds

    }

    /**
     * 테스트와 세부 구현에서 전체 키 timeout을 짧게 재정의할 수 있는 hook입니다.
     * 운영 기본값은 60초이며, timeout은 [suspendTransaction] 밖에서 성공으로 바뀌지 않도록 예외로 재전파됩니다.
     */
    protected open fun loadAllIdsTimeoutMillis(): Long = DEFAULT_LOAD_ALL_IDS_TIMEOUT

    override fun load(id: ID): CompletionStage<E?> =
        if (closed.get()) {
            CompletableFuture.failedFuture(IllegalStateException("R2dbcEntityMapLoader is closed"))
        } else {
            lifecycleScope.async {
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
                        log.error { "DB에서 단건 엔티티 로드 중 오류가 발생했습니다. errorType=${e::class.simpleName}" }
                        throw e
                    }
                }
            }.asCompletableFuture()
        }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    override fun loadAllKeys(): R2dbcCloseableAsyncIterator<ID> {
        check(!closed.get()) { "R2dbcEntityMapLoader is closed" }

        // WHY: RENDEZVOUS(버퍼 없음) — 소비자(AsyncIterator)가 준비될 때만 생산자가 진행한다.
        //      버퍼를 두면 DB에서 읽은 ID가 메모리에 쌓여 OOM 위험이 있으므로 백프레셔를 강제한다.
        val channel =
            Channel<ID>(Channel.RENDEZVOUS).also {
                it.invokeOnClose { cause ->
                    log.debug { "단건 ID 채널이 닫혔습니다. failed=${cause != null}" }
                }
            }

        val producerJob = lifecycleScope.launch(CoroutineName("R2dbc-Loader-Iterator")) {
            log.debug { "DB에서 모든 ID를 로딩합니다 ..." }
            try {
                // 호출자가 transaction context를 보존하는 scope를 제공한 경우에는 outer transaction의
                // retry 정책을 caller가 소유한다. 기본 scope는 context를 공유하지 않으므로 top-level이다.
                val hasOuterTransaction = TransactionManager.currentOrNull() != null
                suspendTransaction {
                    this.queryTimeout = DEFAULT_QUERY_TIMEOUT_SECONDS
                    // WHY: streaming은 transaction retry가 이미 전송한 ID를 재방출할 수 있으므로
                    //      top-level transaction에서만 retry를 끈다. outer transaction에서는
                    //      caller가 소유한 retry 정책을 그대로 둔다.
                    if (!hasOuterTransaction) {
                        this.maxAttempts = 1
                    }
                    // WHY: timeout은 transaction block에서 예외로 재전파해야 Exposed가 rollback한다.
                    //      채널만 닫고 transaction을 정상 종료하면 marker write가 commit될 수 있다.
                    val timeoutMillis = loadAllIdsTimeoutMillis()
                    withTimeoutOrNull(timeoutMillis) {
                        loadAllIdsFromDB(channel)
                    } ?: run {
                        val timeout = TimeoutException(
                            "Loading all IDs exceeded $timeoutMillis ms",
                        )
                        log.warn {
                            "DB에서 모든 ID를 읽는 작업 중 Timeout 이 발생했습니다. " +
                                "timeout=$timeoutMillis msec, errorType=${timeout::class.simpleName}"
                        }
                        throw timeout
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
                log.error { "DB에서 모든 ID 로딩 중 오류가 발생했습니다. errorType=${e::class.simpleName}" }
                channel.close(e)
            } finally {
                // 정상 완료(오류 없음) 시 채널을 닫는다; 오류 경로에서는 이미 위에서 닫혔다
                channel.close()
            }
        }

        return object: R2dbcCloseableAsyncIterator<ID> {
            // 채널에서 미리 받아 둔 결과를 저장. hasNext() 이후 next() 에서 재사용한다
            private val iteratorClosed = AtomicBoolean(false)
            private val exhausted = AtomicBoolean(false)
            private var pendingReceive: CompletableFuture<ChannelResult<ID>>? = null

            private fun closedFuture(): CompletableFuture<ChannelResult<ID>> =
                CompletableFuture.failedFuture(IllegalStateException("R2dbcEntityMapLoader iterator is closed"))

            private fun ensurePending(): CompletableFuture<ChannelResult<ID>> =
                if (iteratorClosed.get()) {
                    closedFuture()
                } else {
                    pendingReceive ?: lifecycleScope
                        .async(CoroutineName("R2dbc-Loader-Iterator-Receive")) {
                            channel.receiveCatching()
                        }.asCompletableFuture()
                        .also { future ->
                            pendingReceive = future
                            future.whenComplete { _, _ ->
                                if (future.isCancelled) {
                                    close()
                                }
                            }
                        }
                }

            private fun <T> closeOnCancellation(stage: CompletableFuture<T>): CompletableFuture<T> =
                stage.also {
                    it.whenComplete { _, _ ->
                        if (it.isCancelled) close()
                    }
                }

            override fun close() {
                if (iteratorClosed.compareAndSet(false, true)) {
                    pendingReceive?.cancel(true)
                    pendingReceive = null
                    val cancellation = CancellationException("R2dbcEntityMapLoader iterator is closed")
                    channel.cancel(cancellation)
                    producerJob.cancel(cancellation)
                }
            }

            override suspend fun closeAndJoin() {
                withContext(NonCancellable) {
                    close()
                    producerJob.join()
                }
            }

            override fun hasNext(): CompletionStage<Boolean?> {
                if (exhausted.get()) return CompletableFuture.completedFuture(false)

                return closeOnCancellation(
                    ensurePending()
                        .thenApply { result ->
                            // 더 이상 원소가 없으면 pending 을 초기화해 메모리 누수를 방지한다
                            if (!result.isSuccess) {
                                pendingReceive = null
                            }
                            result.exceptionOrNull()?.let {
                                close()
                                throw it
                            }
                            if (!result.isSuccess) {
                                // producer가 이미 channel을 닫고 transaction cleanup을 끝낸 뒤의 정상 소진입니다.
                                // 이 시점에 ambient transaction context를 가진 iterator scope를 다시 취소하면
                                // caller-owned transaction cleanup과 경합할 수 있으므로 scope cancellation은 생략합니다.
                                exhausted.set(true)
                            }
                            result.isSuccess
                        },
                )
            }

            override fun next(): CompletionStage<ID> {
                if (exhausted.get()) {
                    return CompletableFuture.failedFuture(NoSuchElementException("No more elements"))
                }

                return closeOnCancellation(
                    ensurePending()
                        .thenApply { result ->
                            pendingReceive = null
                            result.exceptionOrNull()?.let {
                                close()
                                throw it
                            }
                            result.getOrNull() ?: run {
                                exhausted.set(true)
                                throw NoSuchElementException("No more elements")
                            }
                        },
                )
            }
        }
    }

    /**
     * loader가 생성한 producer와 pending receive를 취소합니다. caller가 주입한 부모 scope 자체는 취소하지 않습니다.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lifecycleJob.cancel()
        }
    }

    /** loader가 생성한 모든 producer/receive 작업의 cleanup 완료까지 기다립니다. */
    suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            close()
            lifecycleJob.cancelAndJoin()
        }
    }
}
