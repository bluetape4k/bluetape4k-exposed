package io.bluetape4k.exposed.r2dbc.caffeine.repository

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Exposed R2DBC와 Caffeine 프로세스 내 로컬 캐시를 결합하는 추상 저장소입니다.
 *
 * JDBC 의존성 없이 Caffeine [AsyncCache]로 프로세스 내 캐싱을 수행합니다.
 * 모든 데이터베이스 접근은 R2DBC `suspendTransaction` 호출을 통해 실행됩니다.
 *
 * 하위 클래스는 다음 네 추상 멤버를 구현해야 합니다.
 * - [table]: Exposed [IdTable]
 * - [ResultRow.toEntity]: [ResultRow]를 [E] 엔티티로 변환
 * - [UpdateStatement.updateEntity]: UPDATE용 엔티티 필드 매핑
 * - [BatchInsertStatement.insertEntity]: INSERT용 엔티티 필드 매핑
 *
 * @param ID 기본 키 타입
 * @param E 엔티티(DTO) 타입. 캐시에 저장하려면 [Serializable]을 구현해야 합니다.
 * @param config [LocalCacheConfig] 설정
 */
abstract class AbstractR2dbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.WRITE_THROUGH,
): R2dbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private val DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION: Duration = Duration.ofSeconds(30)
    }

    internal open val writeBehindCloseWaitDuration: Duration
        get() = DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION

    abstract override val table: IdTable<ID>

/** [ResultRow]를 [E] 엔티티로 변환합니다. */
    abstract override suspend fun ResultRow.toEntity(): E

/** 기존 행을 갱신할 때 엔티티 필드를 매핑합니다. */
    abstract fun UpdateStatement.updateEntity(entity: E)

/** 새 행을 삽입할 때 엔티티 필드를 매핑합니다. */
    abstract fun BatchInsertStatement.insertEntity(entity: E)

/** 엔티티 ID를 캐시 키 문자열로 직렬화합니다. 기본 구현은 [toString]을 사용합니다. */
    open fun serializeKey(id: ID): String = id.toString()

    // -------------------------------------------------------------------------
    // R2dbcCacheRepository 필수 프로퍼티 구현
    // -------------------------------------------------------------------------

/** 키 접두사로 사용할 캐시 이름입니다. */
    override val cacheName: String
        get() = config.keyPrefix

/** 캐시 저장 모드입니다. Caffeine 저장소는 항상 로컬 모드를 사용합니다. */
    override val cacheMode: CacheMode
        get() = CacheMode.LOCAL

/** 이 저장소에 구성된 캐시 쓰기 전략입니다. */
    override val cacheWriteMode: CacheWriteMode
        get() = config.writeMode

    // -------------------------------------------------------------------------
    // Caffeine AsyncCache
    // -------------------------------------------------------------------------

    override val cache: AsyncCache<String, E> by lazy {
        Caffeine.newBuilder()
            .maximumSize(config.maximumSize)
            .expireAfterWrite(config.expireAfterWrite)
            .apply { config.expireAfterAccess?.let { expireAfterAccess(it) } }
            .buildAsync()
    }

    // -------------------------------------------------------------------------
    // Write-Behind 지원
    // -------------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val writeBehindQueue: Channel<WriteBehindEntry<ID, E>> by lazy {
        Channel(capacity = config.writeBehindQueueCapacity)
    }

    private val writeBehindQueueDepth = AtomicInteger(0)
    private val writeBehindLifecycleLock = ReentrantLock()
    private val writeBehindLifecycleChanged = writeBehindLifecycleLock.newCondition()
    private val writeBehindAdmissions = AtomicReference(WriteBehindAdmissions())
    private val writeBehindJobCompletion = AtomicReference<WriteBehindJobCompletion?>(null)
    private val writeBehindCompletionPublished = AtomicBoolean(false)
    private val writeBehindCachePublicationsInProgress = ConcurrentHashMap<String, AtomicInteger>()
    private var writeBehindCloseStarted = false
    private var writeBehindCloseStartedAtNanos = 0L
    private var writeBehindCloseWaitBudgetNanos = 0L
    @Volatile
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

    /**
     * write-behind 백그라운드 작업입니다.
     *
     * 채널에서 항목을 받아 [LocalCacheConfig.writeBehindBatchSize]까지 묶고 [flushBatch]로 각 배치를 씁니다.
     * 채널이 닫히면 루프를 종료하고 `finally` 블록에서 남은 항목을 플러시합니다.
     * 이 작업은 지연 시작되므로 write-behind 모드의 첫 `put()` 호출이 백그라운드 소비자를 시작합니다.
     */
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
                        if (next.accepted.await()) {
                            batch.add(next.id to next.entity)
                        }
                    }
                    if (batch.isNotEmpty()) {
                        val flushedCount = batch.size
                        if (flushBatch(batch)) {
                            decrementWriteBehindDepth(flushedCount)
                            batch.clear()
                        }
                    }
                }
            } catch (e: CancellationException) {
                markWriteBehindFailed(e)
                writeBehindQueue.close(e)
                throw e
            } catch (e: Throwable) {
                markWriteBehindFailed(e)
                writeBehindQueue.close(e)
                throw e
            } finally {
                // 채널 닫힌 후에도 루프에서 빠져나온 시점의 미처리 항목을 DB에 기록해야
                // 데이터 유실을 방지할 수 있다.
                if (batch.isNotEmpty()) {
                    val flushedCount = batch.size
                    withContext(NonCancellable) {
                        if (flushBatch(batch)) {
                            decrementWriteBehindDepth(flushedCount)
                            batch.clear()
                        }
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
                writeBehindLifecycleLock.withLock {
                    if (!writeBehindCloseStarted) {
                        publishWriteBehindCompletionLocked()
                    }
                    writeBehindLifecycleChanged.signalAll()
                }
            }
        }
    }

    private fun startWriteBehindJob(): Job = writeBehindJob

    private fun decrementWriteBehindDepth(count: Int) {
        val remaining = writeBehindQueueDepth.addAndGet(-count)
        check(remaining >= 0) { "Write-Behind queue depth accounting underflow" }
    }

    private fun markWriteBehindFailed(error: Throwable) {
        writeBehindLifecycleLock.withLock {
            if (writeBehindWorkerState.get() == CacheWorkerState.STOPPED) return
            if (writeBehindWorkerState.get() != CacheWorkerState.FAILED) {
                writeBehindTerminalError.compareAndSet(null, error)
                writeBehindWorkerState.set(CacheWorkerState.FAILED)
            }
            writeBehindLifecycleChanged.signalAll()
        }
    }

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
        } else if (writeBehindWorkerState.get() != CacheWorkerState.FAILED) {
            writeBehindWorkerState.set(CacheWorkerState.STOPPED)
        }
        if (writeBehindCloseOutcome == null) {
            writeBehindCloseOutcome = WriteBehindCloseOutcome.COMPLETED
        }
        writeBehindLifecycleChanged.signalAll()
    }

    /**
     * write-behind 배치를 데이터베이스에 플러시합니다.
     *
     * 자동 증가 테이블은 데이터베이스가 ID를 할당하므로 새 엔티티를 삽입하지 않습니다.
     * 코루틴 취소 중 [CancellationException]은 다시 던져야 하므로 넓은 [Exception] 처리보다 먼저 분리해 처리합니다.
     */
    private suspend fun flushBatch(batch: List<Pair<ID, E>>): Boolean {
        try {
            suspendTransaction {
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
            log.debug { "Write-Behind: ${batch.size}건 DB flush 완료" }
            lastFlushError.set(null)
            return true
        } catch (e: CancellationException) {
            // 코루틴 취소는 반드시 재던져야 한다 — 삼키면 구조적 동시성이 깨진다
            throw e
        } catch (e: Exception) {
            lastFlushError.set(e)
            log.warn(e) { "Write-Behind: ${batch.size}건 DB flush 실패" }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // DB 직접 조회 (캐시 우회)
    // -------------------------------------------------------------------------

    override suspend fun findByIdFromDb(id: ID): E? =
        suspendTransaction {
            table
                .selectAll()
                .where { table.id eq id }
                .singleOrNull()
                ?.toEntity()
        }

    override suspend fun findAllFromDb(ids: Collection<ID>): List<E> =
        suspendTransaction {
            if (ids.isEmpty()) return@suspendTransaction emptyList()
            table
                .selectAll()
                .where { table.id inList ids }
                .map { it.toEntity() }
                .toList()
        }

    override suspend fun countFromDb(): Long =
        suspendTransaction {
            table.selectAll().count()
        }

    // -------------------------------------------------------------------------
    // 캐시 기반 조회 (Read-through)
    // -------------------------------------------------------------------------

    override suspend fun containsKey(id: ID): Boolean = get(id) != null

    override suspend fun get(id: ID): E? {
        val key = serializeKey(id)
        if (
            writeBehindWorkerState.get() == CacheWorkerState.FAILED &&
            writeBehindCachePublicationsInProgress.containsKey(key)
        ) {
            return findByIdFromDb(id)
        }
        @Suppress("UNCHECKED_CAST")
        return cache.get(key) { _, _ ->
            scope.future {
                findByIdFromDb(id)
            } as CompletableFuture<E>
        }.await()
    }

    override suspend fun getAll(ids: Collection<ID>): Map<ID, E> {
        if (ids.isEmpty()) return emptyMap()

        return ids.mapNotNull { id ->
            get(id)?.let { id to it }
        }.toMap()
    }

    override suspend fun findAll(
        limit: Int?,
        offset: Long?,
        sortBy: Expression<*>,
        sortOrder: SortOrder,
        where: () -> Op<Boolean>,
    ): List<E> {
        val entities =
            suspendTransaction {
                table
                    .selectAll()
                    .where(where)
                    .apply {
                        orderBy(sortBy, sortOrder)
                        limit?.let { limit(it) }
                        offset?.let { offset(it) }
                    }.map { with(this@AbstractR2dbcCaffeineRepository) { it.toEntity() } }
                    .toList()
            }
        // 조회 결과를 캐시에 적재.
        // extractId()가 UnsupportedOperationException을 던질 수 있으므로 Exception만 캐치하되,
        // CancellationException은 재던져 코루틴 취소 신호가 유실되지 않도록 한다.
        if (entities.isNotEmpty()) {
            entities.forEach { entity ->
                try {
                    val id = extractId(entity)
                    cache.put(serializeKey(id), CompletableFuture.completedFuture(entity))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "findAll: 캐시 적재 실패 — extractId()가 구현되지 않았을 수 있습니다" }
                }
            }
        }
        return entities
    }

    /**
     * 엔티티에서 ID를 추출합니다.
     *
     * `findAll(where)` 변형을 사용하려면 하위 클래스에서 재정의해야 합니다.
     */
    override fun extractId(entity: E): ID =
        error(
            "findAll(where) 사용 시 extractId(entity)를 오버라이드하거나 " +
                "엔티티에서 ID를 추출하는 방법을 제공해야 합니다."
        )

    // -------------------------------------------------------------------------
    // 쓰기 (캐시 + DB)
    // -------------------------------------------------------------------------

    override suspend fun put(id: ID, entity: E) {
        val key = serializeKey(id)

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> {
                cache.put(key, CompletableFuture.completedFuture(entity))
                writeToDb(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                startWriteBehindJob()
                val entry = WriteBehindEntry(id, entity)
                reserveWriteBehindAdmission(key)
                var accepted = false
                try {
                    writeBehindQueue.send(entry)
                    accepted = entry.accepted.complete(true)
                    check(accepted) { "Write-Behind admission was already settled" }
                    cache.put(key, CompletableFuture.completedFuture(entity))
                } catch (e: Throwable) {
                    if (!accepted && entry.accepted.complete(false)) {
                        rollbackWriteBehindAdmission()
                    }
                    cache.synchronous().invalidate(key)
                    throw e
                } finally {
                    try {
                        if (accepted) {
                            completeWriteBehindAdmission()
                            val terminalFailure = writeBehindLifecycleLock.withLock {
                                if (writeBehindWorkerState.get() == CacheWorkerState.FAILED) {
                                    writeBehindNotAcceptingException(CacheWorkerState.FAILED)
                                } else {
                                    null
                                }
                            }
                            if (terminalFailure != null) {
                                cache.synchronous().invalidate(key)
                                throw terminalFailure
                            }
                        }
                    } finally {
                        markWriteBehindCachePublicationCompleted(key)
                    }
                }
            }

            else -> cache.put(key, CompletableFuture.completedFuture(entity))  // READ_ONLY: 캐시만 갱신
        }
    }

    override suspend fun putAll(entities: Map<ID, E>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        entities.forEach { (id, entity) -> put(id, entity) }
    }

    override suspend fun validateConsistency(): CacheHealthReport =
        CacheHealthReport(
            mode = cacheWriteMode,
            queueDepth = writeBehindQueueDepth.get(),
            workerState = writeBehindWorkerState.get(),
            lastFlushError = lastFlushError.get(),
        )

    private fun reserveWriteBehindAdmission(key: String) {
        writeBehindLifecycleLock.withLock {
            val state = writeBehindWorkerState.get()
            if (state.isWriteBehindTerminalOrDraining() || !writeBehindJob.isActive) {
                throw writeBehindNotAcceptingException(state)
            }
            writeBehindQueueDepth.incrementAndGet()
            writeBehindAdmissions.updateAndGet { admissions ->
                WriteBehindAdmissions(inProgress = admissions.inProgress + 1)
            }
            markWriteBehindCachePublicationStarted(key)
            if (state == CacheWorkerState.IDLE) {
                writeBehindWorkerState.set(CacheWorkerState.RUNNING)
            }
        }
    }

    private fun rollbackWriteBehindAdmission() {
        decrementWriteBehindDepth(1)
        settleWriteBehindAdmission()
    }

    private fun completeWriteBehindAdmission() {
        settleWriteBehindAdmission()
    }

    private fun settleWriteBehindAdmission() {
        val completedAtNanos = System.nanoTime()
        writeBehindAdmissions.updateAndGet { admissions ->
            val remaining = admissions.inProgress - 1
            check(remaining >= 0) { "Write-Behind admission accounting underflow" }
            WriteBehindAdmissions(
                inProgress = remaining,
                drainedAtNanos = completedAtNanos.takeIf { remaining == 0 } ?: 0L,
            )
        }
        writeBehindLifecycleLock.withLock {
            if (!writeBehindCloseStarted) {
                publishWriteBehindCompletionLocked()
            }
            writeBehindLifecycleChanged.signalAll()
        }
    }

    private fun CacheWorkerState.isWriteBehindTerminalOrDraining(): Boolean =
        this == CacheWorkerState.DRAINING ||
            this == CacheWorkerState.FAILED ||
            this == CacheWorkerState.STOPPED

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

    /**
     * write-through 모드에서 단일 엔티티를 데이터베이스에 저장합니다.
     *
     * 먼저 UPDATE를 시도하고 영향받은 행이 없을 때만 삽입합니다.
     * 자동 증가 테이블은 데이터베이스가 ID를 할당하고 클라이언트 ID가 충돌할 수 있으므로
     * 새 엔티티를 삽입하지 않습니다.
     */
    private suspend fun writeToDb(id: ID, entity: E) {
        suspendTransaction {
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

    // -------------------------------------------------------------------------
    // 삭제
    // -------------------------------------------------------------------------

    override suspend fun invalidate(id: ID) {
        cache.synchronous().invalidate(serializeKey(id))
    }

    override suspend fun invalidateAll(ids: Collection<ID>) {
        val keys = ids.map { serializeKey(it) }
        cache.synchronous().invalidateAll(keys)
    }

    // -------------------------------------------------------------------------
    // 캐시 관리
    // -------------------------------------------------------------------------

    override suspend fun clear() {
        cache.synchronous().invalidateAll()
    }

    /**
     * 저장소를 닫습니다.
     *
     * write-behind 모드에서 채널을 닫으면 새 항목을 더 이상 받지 않습니다.
     * write-behind 작업은 큐에 남은 항목을 처리한 뒤 종료합니다.
     * 동기 [close] 경계에서는 제한된 시간만 대기하여 데이터베이스나 드라이버 중단이
     * 종료를 무기한 막지 않도록 합니다.
     */
    override fun close() {
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            startWriteBehindJob()
            awaitWriteBehindShutdown()
        }
        invalidateCacheOnCloseSafely()
        cancelScopeOnClose()
    }

    protected open fun invalidateCacheOnClose() {
        cache.synchronous().invalidateAll()
    }

    protected open fun cancelScopeOnClose() {
        scope.cancel()
    }

    private fun invalidateCacheOnCloseSafely() {
        try {
            invalidateCacheOnClose()
        } catch (e: Exception) {
            log.warn(e) { "cache invalidateAll 중 오류 발생" }
        }
    }

    private fun awaitWriteBehindShutdown() {
        var restoreInterrupt = false
        val closeOutcome = writeBehindLifecycleLock.withLock {
            if (writeBehindCloseOutcome != null) return@withLock writeBehindCloseOutcome
            val ownsCloseOutcomeArbitration = !writeBehindCloseStarted
            if (ownsCloseOutcomeArbitration) {
                writeBehindCloseStarted = true
                writeBehindCloseStartedAtNanos = System.nanoTime()
                writeBehindCloseWaitBudgetNanos = writeBehindCloseWaitNanos()
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
                    // InterruptedException is the only observable ordering fact available here.
                    // The JVM does not expose the Thread.interrupt() invocation timestamp, so a
                    // completion callback that acquires this lock later cannot retroactively win.
                    publishWriteBehindCloseFailureLocked(WriteBehindCloseFailureReason.INTERRUPTED)
                    restoreInterrupt = true
                    break
                }
            }
            writeBehindCloseOutcome
        }

        if (restoreInterrupt) Thread.currentThread().interrupt()
        when (closeOutcome) {
            WriteBehindCloseOutcome.TIMEOUT -> log.warn {
                "Write-Behind: close timed out waiting for final flush and accepted cache writes. " +
                    "timeout=$writeBehindCloseWaitDuration"
            }
            WriteBehindCloseOutcome.INTERRUPTED -> log.warn {
                "Write-Behind: close was interrupted while waiting for final flush and accepted cache writes."
            }
            else -> Unit
        }
    }

    private fun publishWriteBehindCloseFailureLocked(reason: WriteBehindCloseFailureReason) {
        if (writeBehindCloseOutcome != null) return
        val failure = WriteBehindCloseFailure(reason)
        writeBehindTerminalError.compareAndSet(null, failure)
        writeBehindWorkerState.set(CacheWorkerState.FAILED)
        writeBehindCloseOutcome = when (reason) {
            WriteBehindCloseFailureReason.TIMEOUT -> WriteBehindCloseOutcome.TIMEOUT
            WriteBehindCloseFailureReason.INTERRUPTED -> WriteBehindCloseOutcome.INTERRUPTED
        }
        writeBehindLifecycleChanged.signalAll()
    }

    private fun writeBehindReadinessWasWithinCloseBudgetLocked(): Boolean {
        val completion = writeBehindJobCompletion.get() ?: return false
        val admissions = writeBehindAdmissions.get()
        if (admissions.inProgress != 0 || admissions.drainedAtNanos == 0L) return false
        val readinessAtNanos = maxOf(completion.completedAtNanos, admissions.drainedAtNanos)
        return readinessAtNanos - writeBehindCloseStartedAtNanos <= writeBehindCloseWaitBudgetNanos
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

    private enum class WriteBehindCloseOutcome {
        COMPLETED,
        TIMEOUT,
        INTERRUPTED;

        val failureReason: WriteBehindCloseFailureReason?
            get() = when (this) {
                COMPLETED -> null
                TIMEOUT -> WriteBehindCloseFailureReason.TIMEOUT
                INTERRUPTED -> WriteBehindCloseFailureReason.INTERRUPTED
            }
    }

    private enum class WriteBehindCloseFailureReason { TIMEOUT, INTERRUPTED }

    private class WriteBehindCloseFailure(
        val reason: WriteBehindCloseFailureReason,
    ): IllegalStateException("Write-Behind close failed: $reason")

    private data class WriteBehindJobCompletion(val cause: Throwable?, val completedAtNanos: Long)

    private data class WriteBehindAdmissions(val inProgress: Int = 0, val drainedAtNanos: Long = 0L)

    private data class WriteBehindEntry<ID, E>(
        val id: ID,
        val entity: E,
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
}
