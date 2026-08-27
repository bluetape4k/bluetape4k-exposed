package io.bluetape4k.exposed.jdbc.caffeine.repository

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.cache.internal.CloseCompletion
import io.bluetape4k.exposed.cache.internal.CloseCompletionKind
import io.bluetape4k.exposed.cache.internal.CloseLease
import io.bluetape4k.exposed.cache.internal.MAX_FLUSH_RETRY_ATTEMPTS
import io.bluetape4k.exposed.cache.internal.WriteBehindCoordinator
import io.bluetape4k.exposed.cache.internal.WriteBehindFailureKind
import io.bluetape4k.exposed.cache.internal.WriteBehindWorkerCompletion
import io.bluetape4k.exposed.cache.internal.flushRetryBackoffMillis
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.autoIncColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Exposed JDBC와 Caffeine in-process local cache를 결합하는 추상 repository입니다.
 *
 * 캐시 저장소로 Caffeine [Cache]를 사용하며, 모든 데이터베이스 접근은 JDBC `transaction {}` 안에서
 * 동기적으로 실행됩니다. repository operation에는 lifecycle guard가 적용되지만, shutdown 이후나
 * write-behind worker가 terminal failure 상태가 된 이후 [cache]를 직접 접근하는 사용 방식은 지원하지 않습니다.
 *
 * 하위 클래스는 다음 네 가지 추상 멤버를 구현해야 합니다.
 * - [table]: 대상 Exposed [IdTable]
 * - [ResultRow.toEntity]: [ResultRow]를 entity [E]로 변환
 * - [UpdateStatement.updateEntity]: UPDATE statement에 entity field 매핑
 * - [BatchInsertStatement.insertEntity]: INSERT statement에 entity field 매핑
 *
 * @param ID primary key 타입입니다.
 * @param E cache에 저장할 entity/DTO 타입입니다. cache serialization을 위해 [Serializable]을 구현해야 합니다.
 * @param config local cache 용량, TTL, write mode 등을 담은 [LocalCacheConfig] 설정입니다.
 */
@Suppress("LargeClass") // JDBC cache와 write-behind 수명주기는 하나의 원자적 호환 경계다.
abstract class AbstractJdbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.READ_ONLY,
): JdbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private val DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION: Duration = Duration.ofSeconds(30)
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val WRITE_BEHIND_CLOSE_JOIN_GRACE_MILLIS = 100L
    }

    internal open val writeBehindCloseWaitDuration: Duration
        get() = DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION

    abstract override val table: IdTable<ID>

    /** [ResultRow]를 repository가 반환할 entity [E]로 변환합니다. */
    abstract override fun ResultRow.toEntity(): E

    /** 기존 row를 갱신할 때 entity field를 [UpdateStatement]에 매핑합니다. */
    abstract fun UpdateStatement.updateEntity(entity: E)

    /** 새 row를 삽입할 때 entity field를 [BatchInsertStatement]에 매핑합니다. */
    abstract fun BatchInsertStatement.insertEntity(entity: E)

    /** entity id를 cache key 문자열로 직렬화합니다. 기본 구현은 [toString]을 사용합니다. */
    open fun serializeKey(id: ID): String = id.toString()

    // -------------------------------------------------------------------------
    // JdbcCacheRepository 필수 프로퍼티 구현
    // -------------------------------------------------------------------------

    /** cache key prefix로 사용하는 cache 이름입니다. */
    override val cacheName: String
        get() = config.keyPrefix

    /** cache 저장소 모드입니다. Caffeine repository는 항상 local cache입니다. */
    override val cacheMode: CacheMode
        get() = CacheMode.LOCAL

    /** 이 repository에 설정된 cache write 전략입니다. */
    override val cacheWriteMode: CacheWriteMode
        get() = config.writeMode

    // -------------------------------------------------------------------------
    // Caffeine Cache
    // -------------------------------------------------------------------------

    override val cache: Cache<String, E> by lazy {
        Caffeine.newBuilder()
            .maximumSize(config.maximumSize)
            .expireAfterWrite(config.expireAfterWrite)
            .apply { config.expireAfterAccess?.let { expireAfterAccess(it) } }
            .build()
    }

    // -------------------------------------------------------------------------
    // Write-Behind 지원
    // -------------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val writeBehindQueue: Channel<WriteBehindEntry<ID, E>> by lazy {
        Channel(capacity = config.writeBehindQueueCapacity)
    }

    private val writeBehindCoordinator = WriteBehindCoordinator(config.writeMode)
    private val writeBehindPendingAdmissions = AtomicInteger(0)
    private val writeBehindPendingCapacity = config.writeBehindQueueCapacity

    private val writeBehindQueueDepth = AtomicInteger(0)
    private val writeBehindFailedBatch = AtomicReference<List<Pair<ID, E>>>(emptyList())
    private val writeBehindLifecycleLock = ReentrantLock()
    private val writeBehindCloseLock = ReentrantLock()
    private val writeBehindCachePublicationLocks = ConcurrentHashMap<String, CachePublicationLock>()
    private val writeBehindLifecycleChanged = writeBehindLifecycleLock.newCondition()
    private val writeBehindAdmissions = AtomicReference(WriteBehindAdmissions())
    private val writeBehindJobCompletion = AtomicReference<WriteBehindJobCompletion?>(null)
    private val writeBehindCompletionPublished = AtomicBoolean(false)
    private val writeBehindCoordinatorCloseLease = AtomicReference<CloseLease.Owner?>(null)
    private val writeBehindCoordinatorCompletionPublished = AtomicBoolean(false)
    private val writeBehindCachePublicationsInProgress = ConcurrentHashMap<String, AtomicInteger>()
    private val writeBehindCloseCleanupPending = AtomicBoolean(false)
    private val writeBehindLateSideEffectGuard = AtomicBoolean(false)
    private var writeBehindCloseStarted = false
    private var writeBehindCloseStartedAtNanos = 0L
    private var writeBehindCloseWaitBudgetNanos = 0L
    private var writeBehindCloseDeadlineNanos = 0L
    private var writeBehindCloseOutcome: WriteBehindCloseOutcome? = null
    private val writeBehindWorkerState = AtomicReference(
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            CacheWorkerState.IDLE
        } else {
            CacheWorkerState.NOT_APPLICABLE
        }
    )
    private val writeBehindTerminalError = AtomicReference<Throwable?>(null)
    private val lastFlushError = AtomicReference<Throwable?>(null)

    @Suppress("LoopWithTooManyJumpStatements") // queue drain과 batch 경계를 의도적으로 명시한다.
    private val writeBehindJob by lazy {
        scope.launch {
            val batch = mutableListOf<Pair<ID, E>>()
            try {
                for (entry in writeBehindQueue) {
                    if (!entry.accepted.await()) continue
                    batch.add(entry.id to entry.entity)
                    // 큐에 남아있는 항목을 배치 크기까지 추가로 수집
                    while (batch.size < config.writeBehindBatchSize) {
                        val next = writeBehindQueue.tryReceive().getOrNull() ?: break
                        if (next.accepted.await()) batch.add(next.id to next.entity)
                    }
                    if (batch.isNotEmpty()) {
                        val persistedWrites = batch.toPersistedWrites()
                        val flushedCount = persistedWrites.size
                        if (flushBatchWithRetry(batch, writeBehindCloseDeadlineNanos.takeIf { it > 0L }) &&
                            !writeBehindLateSideEffectGuard.get()
                        ) {
                            recordCoordinatorFlushSuccess(flushedCount)
                            releaseWriteBehindPending(flushedCount)
                            decrementWriteBehindDepth(flushedCount)
                            batch.clear()
                            notifyPersisted(persistedWrites)
                        } else {
                            // A permanent failure leaves the coordinator depth/error intact
                            // and terminates the worker without accepting a new batch.
                            if (!writeBehindLateSideEffectGuard.get()) {
                                retainWriteBehindFailedBatch(batch)
                            }
                            batch.clear()
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Let the NonCancellable final flush settle the accepted batch before
                // the completion callback terminalizes the shared coordinator.
                markWriteBehindFailed(e, terminalizeCoordinator = false)
                writeBehindQueue.close(e)
                throw e
            } catch (e: Throwable) {
                markWriteBehindFailed(e)
                writeBehindQueue.close(e)
                throw e
            } finally {
                // 채널 닫힌 후 남은 항목 처리
                if (batch.isNotEmpty()) {
                    val persistedWrites = batch.toPersistedWrites()
                    val flushedCount = persistedWrites.size
                    if (flushFinalBatch(batch) && !writeBehindLateSideEffectGuard.get()) {
                        recordCoordinatorFlushSuccess(flushedCount)
                        releaseWriteBehindPending(flushedCount)
                        decrementWriteBehindDepth(flushedCount)
                        batch.clear()
                        notifyPersisted(persistedWrites)
                    } else {
                        if (writeBehindLateSideEffectGuard.get()) {
                            batch.clear()
                            return@launch
                        }
                        retainWriteBehindFailedBatch(batch)
                        batch.clear()
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                cause?.let(::markWriteBehindFailed)
                writeBehindJobCompletion.compareAndSet(
                    null,
                    WriteBehindJobCompletion(cause, System.nanoTime()),
                )
                if (writeBehindLateSideEffectGuard.get()) {
                    writeBehindLifecycleLock.withLock {
                        writeBehindLifecycleChanged.signalAll()
                    }
                    return@invokeOnCompletion
                }
                writeBehindCoordinator.onWorkerCompleted(
                    when {
                        cause == null -> WriteBehindWorkerCompletion.DRAINED
                        cause is CancellationException -> WriteBehindWorkerCompletion.CANCELLED
                        else -> WriteBehindWorkerCompletion.FAILED
                    }
                )
                writeBehindLifecycleLock.withLock {
                    publishWriteBehindCompletionLocked()
                    writeBehindLifecycleChanged.signalAll()
                }
            }
        }
    }

    private fun startWriteBehindJob(): Job = writeBehindJob

    private suspend fun flushFinalBatch(batch: List<Pair<ID, E>>): Boolean = withContext(NonCancellable) {
        val deadlineNanos = writeBehindCloseDeadlineNanos.takeIf { it > 0L }
        if (deadlineNanos == null || deadlineNanos == Long.MAX_VALUE) {
            return@withContext flushBatchWithRetry(batch, deadlineNanos)
        }
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return@withContext false
        val remainingMillis = remainingNanos / 1_000_000L
        if (remainingMillis <= 0L) return@withContext false
        withTimeoutOrNull(remainingMillis) {
            flushBatchWithRetry(batch, deadlineNanos)
        } ?: false
    }

    private fun markWriteBehindFailed(error: Throwable, terminalizeCoordinator: Boolean = true) {
        if (terminalizeCoordinator) {
            writeBehindCoordinator.onCloseFailed(WriteBehindFailureKind.WORKER)
        }
        writeBehindLifecycleLock.withLock {
            if (writeBehindWorkerState.get() == CacheWorkerState.STOPPED) return
            if (writeBehindWorkerState.get() != CacheWorkerState.FAILED) {
                writeBehindTerminalError.compareAndSet(null, error)
                writeBehindWorkerState.set(CacheWorkerState.FAILED)
            }
            writeBehindLifecycleChanged.signalAll()
        }
    }

    /** Legacy reflection fixtures may inject queue entries without coordinator tokens. */
    private fun recordCoordinatorFlushSuccess(count: Int) {
        val queueDepth = writeBehindCoordinator.snapshot().queueDepth
        check(queueDepth >= count) {
            "Write-Behind coordinator queue depth[$queueDepth] is below flushed count[$count]"
        }
        writeBehindCoordinator.onFlushSucceeded(count)
    }

    private fun tryReserveWriteBehindPending(): Boolean {
        while (true) {
            val current = writeBehindPendingAdmissions.get()
            if (current >= writeBehindPendingCapacity) return false
            if (writeBehindPendingAdmissions.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseWriteBehindPending(count: Int) {
        if (count <= 0) return
        writeBehindPendingAdmissions.updateAndGet { current ->
            check(current >= count) {
                "Write-Behind pending admission accounting underflow: current=$current, release=$count"
            }
            current - count
        }
    }

    private fun decrementWriteBehindDepth(count: Int) {
        if (count <= 0) return
        writeBehindQueueDepth.updateAndGet { current ->
            check(current >= count) {
                "Write-Behind queue depth accounting underflow: current=$current, decrement=$count"
            }
            current - count
        }
    }

    private fun retainWriteBehindFailedBatch(batch: List<Pair<ID, E>>) {
        if (batch.isEmpty()) return
        writeBehindFailedBatch.updateAndGet { retained ->
            if (retained.isEmpty()) batch.toList() else retained + batch
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // completion/failure ordering is one lifecycle boundary.
    private fun publishWriteBehindCompletionLocked() {
        val completion = writeBehindJobCompletion.get() ?: return
        if (writeBehindAdmissions.get().inProgress != 0) return
        if (!writeBehindCompletionPublished.compareAndSet(false, true)) return

        if (
            writeBehindCloseOutcome == null &&
            writeBehindCloseStarted &&
            !writeBehindReadinessWasWithinCloseBudgetLocked()
        ) {
            publishWriteBehindCloseFailureLocked(WriteBehindCloseFailureReason.TIMEOUT)
            return
        }

        val cause = completion.cause
        if (cause != null) {
            writeBehindTerminalError.compareAndSet(null, cause)
        }

        if (cause != null || lastFlushError.get() != null || writeBehindQueueDepth.get() != 0) {
            if (writeBehindTerminalError.get() == null) {
                writeBehindTerminalError.compareAndSet(null, lastFlushError.get())
            }
            writeBehindWorkerState.set(CacheWorkerState.FAILED)
            if (writeBehindCloseOutcome == null) {
                writeBehindCloseOutcome = WriteBehindCloseOutcome.FAILED
            }
        } else if (writeBehindWorkerState.get() != CacheWorkerState.FAILED) {
            writeBehindWorkerState.set(CacheWorkerState.STOPPED)
        }

        if (writeBehindCloseOutcome == null) {
            writeBehindCloseOutcome = if (writeBehindWorkerState.get() == CacheWorkerState.FAILED) {
                WriteBehindCloseOutcome.FAILED
            } else {
                WriteBehindCloseOutcome.COMPLETED
            }
        }
        writeBehindLifecycleChanged.signalAll()
    }

    /**
     * Flushes a write-behind batch to the database.
     *
     * New entities are not inserted for auto-increment tables because the database owns
     * id allocation. [CancellationException] is a coroutine cancellation signal and must
     * be rethrown; only ordinary database errors are logged and suppressed.
     */
    private suspend fun flushBatch(batch: List<Pair<ID, E>>): Boolean {
        try {
            runInterruptible(Dispatchers.IO) {
                transaction {
                    for ((id, entity) in batch) {
                        val updated = table.update({ table.id eq id }) {
                            it.updateEntity(entity)
                        }
                        // AutoInc 테이블은 DB가 ID를 할당하므로 클라이언트 생성 ID로 INSERT하지 않는다
                        if (updated == 0 && table.id.autoIncColumnType == null) {
                            table.batchInsert(listOf(entity)) {
                                insertEntity(it)
                            }
                        }
                    }
                }
            }
            log.debug { "Write-Behind: ${batch.size}건 DB flush 완료" }
            lastFlushError.set(null)
            return true
        } catch (e: CancellationException) {
            // 코루틴 취소는 삼키지 않고 반드시 재전파한다
            throw e
        } catch (e: Exception) {
            lastFlushError.set(e)
            log.warn {
                "Write-Behind event: component=jdbc operation=flush " +
                    "failureKind=flush queueDepth=${writeBehindQueueDepth.get()}"
            }
            return false
        }
    }

    @Suppress("ReturnCount") // retry terminal state를 worker 경계에서 구분해야 한다.
    private suspend fun flushBatchWithRetry(batch: List<Pair<ID, E>>, deadlineNanos: Long? = null): Boolean {
        for (attempt in 1..MAX_FLUSH_RETRY_ATTEMPTS) {
            if (writeBehindLateSideEffectGuard.get()) return false
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                val failure = IllegalStateException("Write-Behind flush close deadline exhausted")
                lastFlushError.compareAndSet(null, failure)
                markWriteBehindFailed(failure)
                writeBehindQueue.close(failure)
                return false
            }
            if (flushBatch(batch) && !writeBehindLateSideEffectGuard.get()) return true
            if (writeBehindLateSideEffectGuard.get()) return false
            writeBehindCoordinator.onFlushFailed()
            if (attempt == MAX_FLUSH_RETRY_ATTEMPTS) {
                val failure = IllegalStateException("Write-Behind flush retry limit exhausted")
                markWriteBehindFailed(failure)
                writeBehindQueue.close(failure)
                return false
            }
            val backoffMillis = flushRetryBackoffMillis(attempt)
            if (deadlineNanos != null) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) continue
                    delay(minOf(backoffMillis, remainingNanos / NANOS_PER_MILLISECOND))
            } else {
                delay(backoffMillis)
            }
        }
        return false
    }

    // -------------------------------------------------------------------------
    // DB 직접 조회 (캐시 우회)
    // -------------------------------------------------------------------------

    override fun findByIdFromDb(id: ID): E? =
        transaction {
            table
                .selectAll()
                .where { table.id eq id }
                .singleOrNull()
                ?.let { with(this@AbstractJdbcCaffeineRepository) { it.toEntity() } }
        }

    override fun findAllFromDb(ids: Collection<ID>): List<E> =
        transaction {
            if (ids.isEmpty()) return@transaction emptyList()
            table
                .selectAll()
                .where { table.id inList ids }
                .map { with(this@AbstractJdbcCaffeineRepository) { it.toEntity() } }
        }

    override fun countFromDb(): Long =
        transaction {
            table.selectAll().count()
        }

    // -------------------------------------------------------------------------
    // 캐시 기반 조회 (Read-through)
    // -------------------------------------------------------------------------

    override fun containsKey(id: ID): Boolean = get(id) != null

    override fun get(id: ID): E? {
        val key = serializeKey(id)
        if (
            writeBehindWorkerState.get() == CacheWorkerState.FAILED &&
            writeBehindCachePublicationsInProgress.containsKey(key)
        ) {
            return findByIdFromDb(id)
        }
        return cache.getNullable(key) {
            findByIdFromDb(id)
        }
    }

    override fun getAll(ids: Collection<ID>): Map<ID, E> {
        if (ids.isEmpty()) return emptyMap()

        return ids.mapNotNull { id ->
            get(id)?.let { id to it }
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun Cache<String, E>.getNullable(key: String, loader: (String) -> E?): E? =
        (this as Cache<String, E?>).get(key) { loader(it) }

    override fun findAll(
        limit: Int?,
        offset: Long?,
        sortBy: Expression<*>,
        sortOrder: SortOrder,
        where: () -> Op<Boolean>,
    ): List<E> {
        val entities =
            transaction {
                table
                    .selectAll()
                    .where(where)
                    .apply {
                        orderBy(sortBy, sortOrder)
                        limit?.let { limit(it) }
                        offset?.let { offset(it) }
                    }.map { with(this@AbstractJdbcCaffeineRepository) { it.toEntity() } }
            }
        // 조회 결과를 캐시에 적재
        if (entities.isNotEmpty()) {
            entities.forEach { entity ->
                try {
                    val id = extractId(entity)
                    cache.put(serializeKey(id), entity)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    log.warn {
                        "Cache event: component=jdbc operation=cache_warming failureKind=error " +
                            "queueDepth=${writeBehindQueueDepth.get()}"
                    }
                }
            }
        }
        return entities
    }

    /**
     * Extracts the id from an entity.
     *
     * Subclasses must override this when using the `findAll(where)` variant.
     */
    override fun extractId(entity: E): ID =
        error(
            "findAll(where) 사용 시 extractId(entity)를 오버라이드하거나 " +
                "엔티티에서 ID를 추출하는 방법을 제공해야 합니다."
        )

    // -------------------------------------------------------------------------
    // 쓰기 (캐시 + DB)
    // -------------------------------------------------------------------------

    @Suppress(
        "TooGenericExceptionCaught",
        "CyclomaticComplexMethod",
        "LongMethod",
        "ThrowsCount",
        "NestedBlockDepth",
    ) // publication rollback에서 임의의 cache failure type을 보존해야 한다.
    override fun put(id: ID, entity: E) {
        val key = serializeKey(id)

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> {
                cache.put(key, entity)
                writeToDb(id, entity)
                notifyPersisted(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                val admissionToken = try {
                    writeBehindCoordinator.reserveAdmission()
                } catch (_: IllegalStateException) {
                    throw writeBehindNotAcceptingException(writeBehindWorkerState.get())
                }
                val queuedEntry = WriteBehindEntry(id, entity)
                if (!tryReserveWriteBehindPending()) {
                    writeBehindCoordinator.settleEnqueue(admissionToken, accepted = false)
                    throw IllegalStateException(
                        "Write-Behind queue is full (capacity=${config.writeBehindQueueCapacity})."
                    )
                }
                // Write-Behind Job 초기화 보장
                startWriteBehindJob()
                // runBlocking is avoided to prevent Virtual Thread pinning.
                // trySend() fails immediately when the queue is full — throw to prevent silent data loss.
                // cache.put() is intentionally deferred until after trySend() succeeds to prevent
                // a cache entry existing without a corresponding queued DB write.
                var depthReserved = false
                var admissionSettled = false
                var pendingReserved = true
                try {
                    writeBehindLifecycleLock.withLock {
                        checkWriteBehindAcceptingWritesLocked()
                        writeBehindQueueDepth.incrementAndGet()
                        depthReserved = true
                        val result = writeBehindQueue.trySend(queuedEntry)
                        if (result.isFailure) {
                            val state = writeBehindWorkerState.get()
                            if (result.isClosed || state.isWriteBehindTerminalOrDraining()) {
                                throw writeBehindNotAcceptingException(state)
                            }
                            throw IllegalStateException(
                                "Write-Behind queue is full (capacity=${config.writeBehindQueueCapacity}). " +
                                    "Entity id=$id was NOT persisted to the database. " +
                                    "Increase LocalCacheConfig.writeBehindQueueCapacity or reduce the " +
                                    "write rate."
                            )
                        }
                        writeBehindCoordinator.markEnqueued(admissionToken)
                        val acceptedByCoordinator = writeBehindCoordinator.settleEnqueue(
                            admissionToken,
                            accepted = true,
                        )
                        admissionSettled = true
                        if (!acceptedByCoordinator) {
                            queuedEntry.accepted.complete(false)
                            throw writeBehindNotAcceptingException(writeBehindWorkerState.get())
                        }
                        queuedEntry.accepted.complete(true)
                        writeBehindAdmissions.updateAndGet { admissions ->
                            WriteBehindAdmissions(inProgress = admissions.inProgress + 1)
                        }
                        markWriteBehindCachePublicationStarted(key)
                        if (writeBehindWorkerState.get() == CacheWorkerState.IDLE) {
                            writeBehindWorkerState.set(CacheWorkerState.RUNNING)
                        }
                    }
                } catch (failure: Throwable) {
                    if (!admissionSettled) {
                        writeBehindCoordinator.settleEnqueue(admissionToken, accepted = false)
                    }
                    queuedEntry.accepted.complete(false)
                    if (pendingReserved) {
                        releaseWriteBehindPending(1)
                        pendingReserved = false
                    }
                    if (depthReserved) {
                        decrementWriteBehindDepth(1)
                    }
                    throw failure
                }

                var terminalFailure: IllegalStateException? = null
                try {
                    publishWriteBehindCache(key, entity)
                } catch (failure: Throwable) {
                    // The queue entry is already accepted at this point. A cache
                    // publication failure must not leave a stale value behind.
                    try {
                        cache.invalidate(key)
                    } catch (invalidateFailure: Throwable) {
                        failure.addSuppressed(invalidateFailure)
                    }
                    throw failure
                } finally {
                    writeBehindLifecycleLock.withLock {
                        val completedAtNanos = System.nanoTime()
                        writeBehindAdmissions.updateAndGet { admissions ->
                            val remaining = admissions.inProgress - 1
                            check(remaining >= 0) { "Write-Behind cache admission accounting underflow" }
                            WriteBehindAdmissions(
                                inProgress = remaining,
                                drainedAtNanos = completedAtNanos.takeIf { remaining == 0 } ?: 0L,
                            )
                        }
                        if (writeBehindWorkerState.get() == CacheWorkerState.FAILED) {
                            terminalFailure = writeBehindNotAcceptingException(CacheWorkerState.FAILED)
                        }
                        publishWriteBehindCompletionLocked()
                        writeBehindLifecycleChanged.signalAll()
                    }
                    try {
                        terminalFailure?.let { failure ->
                            try {
                                cache.invalidate(key)
                            } catch (invalidateFailure: Throwable) {
                                failure.addSuppressed(invalidateFailure)
                            }
                            throw failure
                        }
                    } finally {
                        markWriteBehindCachePublicationCompleted(key)
                        finishDeferredCloseCleanupIfReady()
                    }
                }
            }

            else -> cache.put(key, entity)  // READ_ONLY: 캐시만 갱신
        }
    }

    override fun putAll(entities: Map<ID, E>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        entities.forEach { (id, entity) -> put(id, entity) }
    }

    /**
     * Called after one accepted cache write has been committed to the database.
     *
     * This is a post-commit notification hook. Ordinary hook failures are logged
     * by the repository and do not turn a successful database write into a failed
     * cache write. [CancellationException] remains a cancellation signal and is
     * rethrown.
     *
     * Subclasses should publish minimal and stable domain events from this hook.
     * Do not expose credentials, tokens, raw secrets, or full cached records.
     * `READ_ONLY`, invalidation, and cache clear operations do not invoke this
     * hook.
     */
    protected open fun afterPersisted(id: ID, entity: E) {
        // Default no-op.
    }

    /**
     * Called after a write-behind batch has been committed and removed from the
     * retained in-memory batch.
     *
     * [writes] preserves accepted write order, including duplicate identifiers.
     * The default implementation delegates to [afterPersisted] once per write.
     */
    protected open fun afterPersisted(writes: List<CachePersistedWrite<ID, E>>) {
        writes.forEach { write -> afterPersisted(write.id, write.entity) }
    }

    /**
     * Stores a single entity in the database for write-through mode.
     *
     * New entities are not inserted for auto-increment tables because the database owns
     * id allocation.
     */
    private fun writeToDb(id: ID, entity: E) {
        transaction {
            val updated = table.update({ table.id eq id }) {
                it.updateEntity(entity)
            }
            // AutoInc 테이블은 DB가 ID를 할당하므로 클라이언트 생성 ID로 INSERT하지 않는다
            if (updated == 0 && table.id.autoIncColumnType == null) {
                table.batchInsert(listOf(entity)) {
                    insertEntity(it)
                }
            }
        }
    }

    private fun List<Pair<ID, E>>.toPersistedWrites(): List<CachePersistedWrite<ID, E>> =
        map { (id, entity) -> CachePersistedWrite(id, entity) }

    private fun notifyPersisted(id: ID, entity: E) {
        if (writeBehindLateSideEffectGuard.get()) return
        try {
            afterPersisted(id, entity)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log.warn {
                "Cache post-persistence hook failed. " +
                    "component=jdbc operation=after_persisted failureKind=error " +
                    "writeCount=1 queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    private fun notifyPersisted(writes: List<CachePersistedWrite<ID, E>>) {
        if (writes.isEmpty() || writeBehindLateSideEffectGuard.get()) return
        try {
            afterPersisted(writes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log.warn {
                "Cache post-persistence hook failed. " +
                    "component=jdbc operation=after_persisted failureKind=error " +
                    "writeCount=${writes.size} queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    private fun checkWriteBehindAcceptingWritesLocked() {
        val state = writeBehindWorkerState.get()
        if (state.isWriteBehindTerminalOrDraining()) {
            throw writeBehindNotAcceptingException(state)
        }
        val terminalError = writeBehindTerminalError.get()
        if (terminalError != null) {
            throw writeBehindNotAcceptingException(state)
        }
        if (!writeBehindJob.isActive) {
            throw writeBehindNotAcceptingException(writeBehindWorkerState.get())
        }
    }

    private fun CacheWorkerState.isWriteBehindTerminalOrDraining(): Boolean =
        this == CacheWorkerState.DRAINING ||
            isWriteBehindTerminal()

    private fun CacheWorkerState.isWriteBehindTerminal(): Boolean =
        this == CacheWorkerState.FAILED || this == CacheWorkerState.STOPPED

    private fun markWriteBehindCachePublicationStarted(key: String) {
        writeBehindCachePublicationsInProgress.compute(key) { _, count ->
            (count ?: AtomicInteger()).apply { incrementAndGet() }
        }
    }

    private fun markWriteBehindCachePublicationCompleted(key: String) {
        writeBehindCachePublicationsInProgress.computeIfPresent(key) { _, count ->
            count.takeIf { it.decrementAndGet() > 0 }
        }
    }

    private fun publishWriteBehindCache(key: String, entity: E) {
        val publicationLock = acquireCachePublicationLock(key)
        try {
            publicationLock.lock.withLock {
                if (writeBehindLateSideEffectGuard.get()) {
                    throw writeBehindNotAcceptingException(writeBehindWorkerState.get())
                }
                cache.put(key, entity)
            }
        } finally {
            if (publicationLock.users.decrementAndGet() == 0) {
                writeBehindCachePublicationLocks.remove(key, publicationLock)
            }
        }
    }

    private fun acquireCachePublicationLock(key: String): CachePublicationLock {
        while (true) {
            val publicationLock = writeBehindCachePublicationLocks.computeIfAbsent(key) {
                CachePublicationLock()
            }
            publicationLock.users.incrementAndGet()
            if (writeBehindCachePublicationLocks[key] === publicationLock) return publicationLock
            if (publicationLock.users.decrementAndGet() == 0) {
                writeBehindCachePublicationLocks.remove(key, publicationLock)
            }
        }
    }

    private class CachePublicationLock {
        val lock = ReentrantLock()
        val users = AtomicInteger(0)
    }

    private fun writeBehindNotAcceptingException(state: CacheWorkerState): IllegalStateException {
        val terminalError = writeBehindTerminalError.get()
        val terminalReason =
            writeBehindCloseOutcome?.failureReason?.name
                ?: (terminalError as? WriteBehindCloseFailure)?.reason?.name
                ?: terminalError?.let { it::class.qualifiedName }
                ?: state.name
        return IllegalStateException(
            "Write-Behind worker is not accepting writes because the repository is closing, closed, or terminal. " +
                "cacheName=$cacheName, workerState=$state, terminalReason=$terminalReason"
        )
    }

    // -------------------------------------------------------------------------
    // 삭제
    // -------------------------------------------------------------------------

    override fun invalidate(id: ID) {
        cache.invalidate(serializeKey(id))
    }

    override fun invalidateAll(ids: Collection<ID>) {
        val keys = ids.map { serializeKey(it) }
        cache.invalidateAll(keys)
    }

    // -------------------------------------------------------------------------
    // 캐시 관리
    // -------------------------------------------------------------------------

    override fun clear() {
        cache.invalidateAll()
    }

    override fun validateConsistency(): CacheHealthReport =
        writeBehindCoordinator.snapshot().let { snapshot ->
            CacheHealthReport(
                mode = snapshot.mode,
                queueDepth = snapshot.queueDepth,
                workerState = snapshot.workerState,
                lastFlushError = lastFlushError.get(),
            )
        }

    /**
     * Closes the repository and releases its resources.
     *
     * In write-behind mode, closing the channel stops new items from being accepted and
     * waits until queued items have been flushed to the database.
     *
     * Waiting uses a bounded lifecycle condition so database or driver hangs cannot block
     * shutdown forever, while accepted cache admissions participate in the same deadline.
     */
    override fun close() {
        writeBehindCloseLock.withLock {
            if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
                startWriteBehindJob()
                val outcome = awaitWriteBehindShutdown()
                if (outcome != null) cancelWriteBehindAfterClose(outcome)
            }
            val publicationsPending = markCloseCleanupPendingIfNeeded()
            cancelScopeOnCloseSafely()
            if (!publicationsPending) {
                invalidateCacheOnCloseSafely()
                publishCoordinatorCloseCompletion()
            } else {
                finishDeferredCloseCleanupIfReady()
            }
        }
    }

    private fun cancelScopeOnCloseSafely() {
        try {
            scope.cancel()
        } catch (_: Exception) {
            log.warn {
                "Write-Behind event: component=jdbc operation=close_cleanup " +
                    "failureKind=scope_cancel queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    private fun cancelWriteBehindAfterClose(outcome: WriteBehindCloseOutcome) {
        if (outcome == WriteBehindCloseOutcome.COMPLETED) return
        if (outcome == WriteBehindCloseOutcome.TIMEOUT || outcome == WriteBehindCloseOutcome.INTERRUPTED) {
            // Once the bounded close contract expires, no worker completion may publish
            // cache, hook, coordinator, or lifecycle state changes after close returns.
            writeBehindLateSideEffectGuard.set(true)
            val completed = CountDownLatch(1)
            writeBehindJob.invokeOnCompletion { completed.countDown() }
            writeBehindJob.cancel()
            var restoreInterrupt = false
            try {
                completed.await(WRITE_BEHIND_CLOSE_JOIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                restoreInterrupt = true
            }
            if (restoreInterrupt) Thread.currentThread().interrupt()
            if (!writeBehindJob.isCompleted) {
                log.warn {
                    "Write-Behind event: component=jdbc operation=close " +
                        "failureKind=close_join_timeout queueDepth=${writeBehindQueueDepth.get()}"
                }
            }
        } else {
            writeBehindJob.cancel()
        }
    }

    private fun invalidateCacheOnCloseSafely() {
        try {
            cache.invalidateAll()
        } catch (_: Exception) {
            log.warn {
                "Write-Behind event: component=jdbc operation=close_cleanup " +
                    "failureKind=cache_invalidate queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // owner/follower close arbitration is intentionally linearized.
    private fun awaitWriteBehindShutdown(): WriteBehindCloseOutcome? {
        var restoreInterrupt = false
        val closeOutcome = writeBehindLifecycleLock.withLock {
            if (writeBehindCloseOutcome != null) {
                return@withLock writeBehindCloseOutcome
            }

            val ownsCloseOutcomeArbitration = !writeBehindCloseStarted
            if (ownsCloseOutcomeArbitration) {
                (writeBehindCoordinator.beginClose() as? CloseLease.Owner)?.let {
                    writeBehindCoordinatorCloseLease.compareAndSet(null, it)
                }
                writeBehindCloseStarted = true
                writeBehindCloseStartedAtNanos = System.nanoTime()
                writeBehindCloseWaitBudgetNanos = writeBehindCloseWaitNanos()
                writeBehindCloseDeadlineNanos = if (writeBehindCloseWaitBudgetNanos == Long.MAX_VALUE) {
                    Long.MAX_VALUE
                } else {
                    writeBehindCloseStartedAtNanos + writeBehindCloseWaitBudgetNanos
                }
                writeBehindAdmissions.updateAndGet { admissions ->
                    if (admissions.inProgress == 0 && admissions.drainedAtNanos == 0L) {
                        admissions.copy(drainedAtNanos = writeBehindCloseStartedAtNanos)
                    } else {
                        admissions
                    }
                }
                if (!writeBehindWorkerState.get().isWriteBehindTerminalOrDraining()) {
                    writeBehindWorkerState.set(CacheWorkerState.DRAINING)
                }
                writeBehindQueue.close()
            }

            if (!ownsCloseOutcomeArbitration) {
                while (writeBehindCloseOutcome == null) {
                    try {
                        writeBehindLifecycleChanged.await()
                    } catch (_: InterruptedException) {
                        // A follower never arbitrates the shared outcome. Preserve its interrupt
                        // after the owner publishes the one immutable result.
                        restoreInterrupt = true
                    }
                }
                return@withLock writeBehindCloseOutcome
            }

            publishWriteBehindCompletionLocked()
            while (writeBehindCloseOutcome == null) {
                publishWriteBehindCompletionLocked()
                if (writeBehindCloseOutcome != null) break
                val elapsed = System.nanoTime() - writeBehindCloseStartedAtNanos
                val remaining = writeBehindCloseWaitBudgetNanos - elapsed.coerceAtLeast(0L)
                if (remaining <= 0L) {
                    publishWriteBehindCloseFailureLocked(WriteBehindCloseFailureReason.TIMEOUT)
                    break
                }
                try {
                    val remainingAfterWait = writeBehindLifecycleChanged.awaitNanos(remaining)
                    publishWriteBehindCompletionLocked()
                    if (writeBehindCloseOutcome == null && remainingAfterWait <= 0L) {
                        publishWriteBehindCloseFailureLocked(WriteBehindCloseFailureReason.TIMEOUT)
                    }
                } catch (_: InterruptedException) {
                    publishWriteBehindCloseFailureLocked(WriteBehindCloseFailureReason.INTERRUPTED)
                    restoreInterrupt = true
                    break
                }
            }
            writeBehindCloseOutcome
        }

        if (restoreInterrupt) {
            Thread.currentThread().interrupt()
        }

        when (closeOutcome) {
            WriteBehindCloseOutcome.TIMEOUT -> log.warn {
                "Write-Behind event: component=jdbc operation=close " +
                    "failureKind=close_timeout queueDepth=${writeBehindQueueDepth.get()}"
            }

            WriteBehindCloseOutcome.INTERRUPTED -> log.warn {
                "Write-Behind event: component=jdbc operation=close " +
                    "failureKind=close_interrupted queueDepth=${writeBehindQueueDepth.get()}"
            }

            else -> Unit
        }
        return closeOutcome
    }

    private fun publishCoordinatorCloseCompletion() {
        val owner = writeBehindCoordinatorCloseLease.get() ?: return
        if (!writeBehindCoordinatorCompletionPublished.compareAndSet(false, true)) return
        val snapshot = writeBehindCoordinator.snapshot()
        val outcome = writeBehindCloseOutcome
        val completed = outcome == null || outcome == WriteBehindCloseOutcome.COMPLETED
        val drained = completed && snapshot.queueDepth == 0 &&
            writeBehindWorkerState.get() == CacheWorkerState.STOPPED
        val kind = if (drained) {
            CloseCompletionKind.COMPLETED
        } else {
            when (outcome) {
                WriteBehindCloseOutcome.TIMEOUT -> CloseCompletionKind.TIMEOUT
                WriteBehindCloseOutcome.INTERRUPTED -> CloseCompletionKind.INTERRUPTED
                else -> CloseCompletionKind.FAILED
            }
        }
        writeBehindCoordinator.publishCloseCompletion(
            owner,
            CloseCompletion(
                kind = kind,
                workerState = if (drained) CacheWorkerState.STOPPED else CacheWorkerState.FAILED,
                queueDepth = snapshot.queueDepth,
            ),
        )
    }

    private fun markCloseCleanupPendingIfNeeded(): Boolean = writeBehindLifecycleLock.withLock {
        val pending = writeBehindAdmissions.get().inProgress > 0
        if (pending) writeBehindCloseCleanupPending.set(true)
        pending
    }

    @Suppress("ReturnCount") // deferred cleanup은 빠른 비활성/락/회계 검사를 유지한다.
    private fun finishDeferredCloseCleanupIfReady() {
        if (!writeBehindCloseCleanupPending.get()) return
        writeBehindCloseLock.lock()
        try {
            val ready = writeBehindLifecycleLock.withLock {
                writeBehindAdmissions.get().inProgress == 0
            }
            if (!ready || !writeBehindCloseCleanupPending.compareAndSet(true, false)) return
            invalidateCacheOnCloseSafely()
            publishCoordinatorCloseCompletion()
        } finally {
            writeBehindCloseLock.unlock()
        }
    }

    private fun publishWriteBehindCloseFailureLocked(reason: WriteBehindCloseFailureReason) {
        if (writeBehindCloseOutcome != null) return
        if (reason == WriteBehindCloseFailureReason.TIMEOUT ||
            reason == WriteBehindCloseFailureReason.INTERRUPTED
        ) {
            writeBehindLateSideEffectGuard.set(true)
        }
        val failure = WriteBehindCloseFailure(reason)
        writeBehindTerminalError.compareAndSet(null, failure)
        writeBehindWorkerState.set(CacheWorkerState.FAILED)
        writeBehindCoordinator.onCloseFailed(
            when (reason) {
                WriteBehindCloseFailureReason.TIMEOUT -> WriteBehindFailureKind.CLOSE_TIMEOUT
                WriteBehindCloseFailureReason.INTERRUPTED -> WriteBehindFailureKind.CLOSE_INTERRUPTED
                WriteBehindCloseFailureReason.WORKER -> WriteBehindFailureKind.WORKER
            }
        )
        writeBehindCloseOutcome = when (reason) {
            WriteBehindCloseFailureReason.TIMEOUT -> WriteBehindCloseOutcome.TIMEOUT
            WriteBehindCloseFailureReason.INTERRUPTED -> WriteBehindCloseOutcome.INTERRUPTED
            WriteBehindCloseFailureReason.WORKER -> WriteBehindCloseOutcome.FAILED
        }
        writeBehindLifecycleChanged.signalAll()
    }

    private fun writeBehindReadinessWasWithinCloseBudgetLocked(): Boolean {
        val completion = writeBehindJobCompletion.get() ?: return false
        val admissions = writeBehindAdmissions.get()
        if (admissions.inProgress != 0 || admissions.drainedAtNanos == 0L) return false
        val readinessAtNanos = maxOf(
            completion.completedAtNanos,
            admissions.drainedAtNanos,
        )
        val elapsed = readinessAtNanos - writeBehindCloseStartedAtNanos
        return elapsed <= writeBehindCloseWaitBudgetNanos
    }

    private enum class WriteBehindCloseOutcome {
        COMPLETED,
        TIMEOUT,
        INTERRUPTED,
        FAILED,
        ;

        val failureReason: WriteBehindCloseFailureReason?
            get() = when (this) {
                TIMEOUT -> WriteBehindCloseFailureReason.TIMEOUT
                INTERRUPTED -> WriteBehindCloseFailureReason.INTERRUPTED
                COMPLETED -> null
                FAILED -> WriteBehindCloseFailureReason.WORKER
            }
    }

    private fun writeBehindCloseWaitNanos(): Long {
        val duration = writeBehindCloseWaitDuration
        if (duration.isNegative || duration.isZero) return 0L
        return try {
            duration.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private enum class WriteBehindCloseFailureReason {
        TIMEOUT,
        INTERRUPTED,
        WORKER,
    }

    private class WriteBehindCloseFailure(
        val reason: WriteBehindCloseFailureReason,
    ): IllegalStateException("Write-Behind close failed: $reason")

    private data class WriteBehindJobCompletion(
        val cause: Throwable?,
        val completedAtNanos: Long,
    )

    private data class WriteBehindAdmissions(
        val inProgress: Int = 0,
        val drainedAtNanos: Long = 0L,
    )

    private data class WriteBehindEntry<ID, E>(
        val id: ID,
        val entity: E,
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
}
