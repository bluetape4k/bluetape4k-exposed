package io.bluetape4k.exposed.r2dbc.caffeine.repository

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract repository combining Exposed R2DBC with a Caffeine in-process local cache.
 *
 * Uses a Caffeine [AsyncCache] for in-process caching without a JDBC dependency.
 * All database access is performed through R2DBC `suspendTransaction` calls.
 *
 * Subclasses must implement four abstract members:
 * - [table]: the Exposed [IdTable]
 * - [ResultRow.toEntity]: converts a [ResultRow] to entity [E]
 * - [UpdateStatement.updateEntity]: maps entity fields for UPDATE
 * - [BatchInsertStatement.insertEntity]: maps entity fields for INSERT
 *
 * @param ID Primary key type
 * @param E Entity (DTO) type. Must implement [Serializable] for cache storage.
 * @param config [LocalCacheConfig] settings
 */
abstract class AbstractR2dbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.WRITE_THROUGH,
): R2dbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private const val WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS = 30L
    }

    abstract override val table: IdTable<ID>

    /** Converts a [ResultRow] into entity [E]. */
    abstract override suspend fun ResultRow.toEntity(): E

    /** Maps entity fields when updating an existing row. */
    abstract fun UpdateStatement.updateEntity(entity: E)

    /** Maps entity fields when inserting a new row. */
    abstract fun BatchInsertStatement.insertEntity(entity: E)

    /** Serializes an entity id to a cache key string. The default uses [toString]. */
    open fun serializeKey(id: ID): String = id.toString()

    // -------------------------------------------------------------------------
    // R2dbcCacheRepository 필수 프로퍼티 구현
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

    private val writeBehindQueue: Channel<Pair<ID, E>> by lazy {
        Channel(capacity = config.writeBehindQueueCapacity)
    }

    private val writeBehindQueueDepth = AtomicInteger(0)
    private val writeBehindJobStarted = AtomicBoolean(false)
    private val lastFlushError = AtomicReference<Throwable?>(null)

    /**
     * Write-behind background job.
     *
     * Receives items from the channel, groups them up to [LocalCacheConfig.writeBehindBatchSize],
     * and writes each batch through [flushBatch]. When the channel closes, the loop exits and the
     * `finally` block flushes any remaining items. The job is lazy, so the first `put()` call in
     * write-behind mode starts the background consumer.
     */
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
                        val flushedCount = batch.size
                        flushBatch(batch)
                        writeBehindQueueDepth.addAndGet(-flushedCount)
                        batch.clear()
                    }
                }
            } finally {
                // 채널 닫힌 후에도 루프에서 빠져나온 시점의 미처리 항목을 DB에 기록해야
                // 데이터 유실을 방지할 수 있다.
                if (batch.isNotEmpty()) {
                    val flushedCount = batch.size
                    withContext(NonCancellable) {
                        flushBatch(batch)
                    }
                    writeBehindQueueDepth.addAndGet(-flushedCount)
                    batch.clear()
                }
            }
        }
    }

    private fun startWriteBehindJob(): Job {
        writeBehindJobStarted.set(true)
        return writeBehindJob
    }

    /**
     * Flushes a write-behind batch to the database.
     *
     * New entities are not inserted for auto-increment tables because the database owns
     * id allocation. [CancellationException] must be rethrown during coroutine cancellation,
     * so it is handled separately before the broad [Exception] catch.
     */
    private suspend fun flushBatch(batch: List<Pair<ID, E>>) {
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
        } catch (e: CancellationException) {
            // 코루틴 취소는 반드시 재던져야 한다 — 삼키면 구조적 동시성이 깨진다
            throw e
        } catch (e: Exception) {
            lastFlushError.set(e)
            log.warn(e) { "Write-Behind: ${batch.size}건 DB flush 실패" }
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

    override suspend fun put(id: ID, entity: E) {
        val key = serializeKey(id)
        cache.put(key, CompletableFuture.completedFuture(entity))

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> writeToDb(id, entity)
            CacheWriteMode.WRITE_BEHIND -> {
                // writeBehindJob은 lazy이므로 첫 send() 전에 명시적으로 접근하여
                // 백그라운드 소비 루프가 시작되도록 보장한다.
                startWriteBehindJob()
                writeBehindQueueDepth.incrementAndGet()
                try {
                    writeBehindQueue.send(id to entity)
                } catch (e: Exception) {
                    writeBehindQueueDepth.decrementAndGet()
                    throw e
                }
            }

            else -> { /* READ_ONLY: 캐시만 갱신 */
            }
        }
    }

    override suspend fun putAll(entities: Map<ID, E>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        entities.forEach { (id, entity) -> put(id, entity) }
    }

    override suspend fun validateConsistency(): CacheHealthReport =
        CacheHealthReport(
            mode = cacheWriteMode,
            queueDepth = writeBehindQueueDepth.get().coerceAtLeast(0),
            isFlushJobRunning = isWriteBehindJobRunning(),
            lastFlushError = lastFlushError.get(),
        )

    private fun isWriteBehindJobRunning(): Boolean =
        config.writeMode == CacheWriteMode.WRITE_BEHIND &&
            writeBehindJobStarted.get() &&
            writeBehindJob.isActive

    /**
     * Stores a single entity in the database for write-through mode.
     *
     * Attempts UPDATE first and inserts only when no rows were affected. New entities are not
     * inserted for auto-increment tables because the database owns id allocation and a client-side
     * id could conflict.
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
     * Closes the repository.
     *
     * In write-behind mode, closing the channel stops new items from being accepted.
     * The write-behind job processes remaining queued items and then exits. Waiting happens
     * at the synchronous [close] boundary with a bounded timeout so database or driver hangs
     * cannot block shutdown forever.
     */
    override fun close() {
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            writeBehindQueue.close()
            awaitWriteBehindJobCompletion()
        }
        cache.synchronous().invalidateAll()
        scope.cancel()
    }

    private fun awaitWriteBehindJobCompletion() {
        val completed = CountDownLatch(1)
        startWriteBehindJob().invokeOnCompletion { completed.countDown() }

        val completedInTime =
            try {
                completed.await(WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        if (!completedInTime) {
            log.warn {
                "Write-Behind: close timed out waiting for final flush. " +
                    "timeoutSeconds=$WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS"
            }
        }
    }
}
