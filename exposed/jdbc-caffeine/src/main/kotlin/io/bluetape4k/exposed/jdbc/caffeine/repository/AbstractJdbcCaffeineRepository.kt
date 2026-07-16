package io.bluetape4k.exposed.jdbc.caffeine.repository

import com.github.benmanes.caffeine.cache.Cache
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Abstract repository combining Exposed JDBC with a Caffeine in-process local cache.
 *
 * Uses a Caffeine [Cache] for in-process caching. All database access is synchronous
 * via JDBC `transaction {}`.
 * Lifecycle guards apply through repository operations; direct [cache] access after
 * shutdown or terminal worker failure is unsupported.
 *
 * Subclasses must implement four abstract members:
 * - [table]: the Exposed [IdTable]
 * - [ResultRow.toEntity]: converts a [ResultRow] to entity [E]
 * - [UpdateStatement.updateEntity]: maps entity fields for UPDATE
 * - [BatchInsertStatement.insertEntity]: maps entity fields for INSERT
 *
 * @param ID Primary key type
 * @param E Entity (DTO) type — must implement [Serializable] for cache storage
 * @param config [LocalCacheConfig] settings
 */
abstract class AbstractJdbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.READ_ONLY,
): JdbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private val DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION: Duration = Duration.ofSeconds(30)
    }

    internal open val writeBehindCloseWaitDuration: Duration
        get() = DEFAULT_WRITE_BEHIND_CLOSE_WAIT_DURATION

    abstract override val table: IdTable<ID>

    /** Converts a [ResultRow] into entity [E]. */
    abstract override fun ResultRow.toEntity(): E

    /** Maps entity fields when updating an existing row. */
    abstract fun UpdateStatement.updateEntity(entity: E)

    /** Maps entity fields when inserting a new row. */
    abstract fun BatchInsertStatement.insertEntity(entity: E)

    /** Serializes an entity id to a cache key string. The default uses [toString]. */
    open fun serializeKey(id: ID): String = id.toString()

    // -------------------------------------------------------------------------
    // JdbcCacheRepository 필수 프로퍼티 구현
    // -------------------------------------------------------------------------

    /** Cache name used as the key prefix. */
    override val cacheName: String
        get() = config.keyPrefix

    /** Cache storage mode. Caffeine repositories are always local. */
    override val cacheMode: CacheMode
        get() = CacheMode.LOCAL

    /** Cache write strategy configured for this repository. */
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

    private val writeBehindQueue: Channel<Pair<ID, E>> by lazy {
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

    private val writeBehindJob by lazy {
        scope.launch {
            val batch = mutableListOf<Pair<ID, E>>()
            try {
                for (entry in writeBehindQueue) {
                    batch.add(entry)
                    // 큐에 남아있는 항목을 배치 크기까지 추가로 수집
                    while (batch.size < config.writeBehindBatchSize) {
                        val next = writeBehindQueue.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }
                    if (batch.isNotEmpty()) {
                        val persistedWrites = batch.toPersistedWrites()
                        val flushedCount = persistedWrites.size
                        if (flushBatch(batch)) {
                            writeBehindQueueDepth.addAndGet(-flushedCount)
                            batch.clear()
                            notifyPersisted(persistedWrites)
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
                // 채널 닫힌 후 남은 항목 처리
                if (batch.isNotEmpty()) {
                    val persistedWrites = batch.toPersistedWrites()
                    val flushedCount = persistedWrites.size
                    if (flushBatch(batch)) {
                        writeBehindQueueDepth.addAndGet(-flushedCount)
                        batch.clear()
                        notifyPersisted(persistedWrites)
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
                    publishWriteBehindCompletionLocked()
                    writeBehindLifecycleChanged.signalAll()
                }
            }
        }
    }

    private fun startWriteBehindJob(): Job = writeBehindJob

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
     * Flushes a write-behind batch to the database.
     *
     * New entities are not inserted for auto-increment tables because the database owns
     * id allocation. [CancellationException] is a coroutine cancellation signal and must
     * be rethrown; only ordinary database errors are logged and suppressed.
     */
    private fun flushBatch(batch: List<Pair<ID, E>>): Boolean {
        try {
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
            log.debug { "Write-Behind: ${batch.size}건 DB flush 완료" }
            lastFlushError.set(null)
            return true
        } catch (e: CancellationException) {
            // 코루틴 취소는 삼키지 않고 반드시 재전파한다
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
            return null
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
                } catch (e: Exception) {
                    log.warn(e) { "Cache warming failed for entity - skipping. cacheName=$cacheName" }
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

    override fun put(id: ID, entity: E) {
        val key = serializeKey(id)

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> {
                cache.put(key, entity)
                writeToDb(id, entity)
                notifyPersisted(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                // Write-Behind Job 초기화 보장
                startWriteBehindJob()
                // runBlocking is avoided to prevent Virtual Thread pinning.
                // trySend() fails immediately when the queue is full — throw to prevent silent data loss.
                // cache.put() is intentionally deferred until after trySend() succeeds to prevent
                // a cache entry existing without a corresponding queued DB write.
                writeBehindLifecycleLock.withLock {
                    checkWriteBehindAcceptingWritesLocked()
                    writeBehindQueueDepth.incrementAndGet()
                    val result = writeBehindQueue.trySend(id to entity)
                    if (result.isFailure) {
                        writeBehindQueueDepth.decrementAndGet()
                        val state = writeBehindWorkerState.get()
                        if (result.isClosed || state.isWriteBehindTerminalOrDraining()) {
                            throw writeBehindNotAcceptingException(state)
                        }
                        throw IllegalStateException(
                            "Write-Behind queue is full (capacity=${config.writeBehindQueueCapacity}). " +
                                "Entity id=$id was NOT persisted to the database. " +
                                "Increase LocalCacheConfig.writeBehindQueueCapacity or reduce the write rate."
                        )
                    }
                    writeBehindAdmissions.updateAndGet { admissions ->
                        WriteBehindAdmissions(inProgress = admissions.inProgress + 1)
                    }
                    markWriteBehindCachePublicationStarted(key)
                    if (writeBehindWorkerState.get() == CacheWorkerState.IDLE) {
                        writeBehindWorkerState.set(CacheWorkerState.RUNNING)
                    }
                }

                var terminalFailure: IllegalStateException? = null
                try {
                    cache.put(key, entity)
                } finally {
                    val completedAtNanos = System.nanoTime()
                    writeBehindAdmissions.updateAndGet { admissions ->
                        val remaining = admissions.inProgress - 1
                        check(remaining >= 0) { "Write-Behind cache admission accounting underflow" }
                        WriteBehindAdmissions(
                            inProgress = remaining,
                            drainedAtNanos = completedAtNanos.takeIf { remaining == 0 } ?: 0L,
                        )
                    }
                    writeBehindLifecycleLock.withLock {
                        if (writeBehindWorkerState.get() == CacheWorkerState.FAILED) {
                            terminalFailure = writeBehindNotAcceptingException(CacheWorkerState.FAILED)
                        }
                        publishWriteBehindCompletionLocked()
                        writeBehindLifecycleChanged.signalAll()
                    }
                    try {
                        terminalFailure?.let { failure ->
                            cache.invalidate(key)
                            throw failure
                        }
                    } finally {
                        markWriteBehindCachePublicationCompleted(key)
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
        try {
            afterPersisted(id, entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "Cache post-persistence hook failed. " +
                    "cacheName=$cacheName, mode=$cacheWriteMode, writeCount=1, exceptionType=${e::class.qualifiedName}"
            }
        }
    }

    private fun notifyPersisted(writes: List<CachePersistedWrite<ID, E>>) {
        if (writes.isEmpty()) return
        try {
            afterPersisted(writes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "Cache post-persistence hook failed. " +
                    "cacheName=$cacheName, mode=$cacheWriteMode, writeCount=${writes.size}, " +
                    "exceptionType=${e::class.qualifiedName}"
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
        CacheHealthReport(
            mode = cacheWriteMode,
            queueDepth = writeBehindQueueDepth.get().coerceAtLeast(0),
            workerState = writeBehindWorkerState.get(),
            lastFlushError = lastFlushError.get(),
        )

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
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            startWriteBehindJob()
            awaitWriteBehindShutdown()
        }
        try {
            cache.invalidateAll()
        } catch (e: Exception) {
            log.warn(e) { "Failed to invalidate cache on close" }
        }
        try {
            scope.cancel()
        } catch (e: Exception) {
            log.warn(e) { "Failed to cancel scope on close" }
        }
    }

    private fun awaitWriteBehindShutdown() {
        var restoreInterrupt = false
        val closeOutcome = writeBehindLifecycleLock.withLock {
            if (writeBehindCloseOutcome != null) {
                return@withLock writeBehindCloseOutcome
            }

            if (!writeBehindCloseStarted) {
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
                "Write-Behind: close timed out waiting for final flush and accepted cache writes. " +
                    "Remaining queued items may be discarded when the repository scope is cancelled. " +
                    "timeout=$writeBehindCloseWaitDuration"
            }

            WriteBehindCloseOutcome.INTERRUPTED -> log.warn {
                "Write-Behind: close was interrupted while waiting for final flush and accepted cache writes. " +
                    "Remaining queued items may be discarded when the repository scope is cancelled."
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
        ;

        val failureReason: WriteBehindCloseFailureReason?
            get() = when (this) {
                TIMEOUT -> WriteBehindCloseFailureReason.TIMEOUT
                INTERRUPTED -> WriteBehindCloseFailureReason.INTERRUPTED
                COMPLETED -> null
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
}
