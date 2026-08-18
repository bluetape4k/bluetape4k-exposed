package io.bluetape4k.exposed.jdbc.caffeine.repository

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exposed JDBC + Caffeine 로컬 캐시를 결합한 suspend 추상 레포지토리.
 *
 * Caffeine [Cache]를 사용하여 인프로세스 캐싱을 제공합니다.
 * JDBC `suspendedTransactionAsync`를 통해 모든 DB 접근이 suspend 함수로 이루어집니다.
 *
 * 서브클래스는 4개 추상 멤버를 구현합니다:
 * - [table]: Exposed [IdTable]
 * - [ResultRow.toEntity]: ResultRow -> E 변환
 * - [UpdateStatement.updateEntity]: UPDATE 컬럼 매핑
 * - [BatchInsertStatement.insertEntity]: INSERT 컬럼 매핑
 *
 * @param ID PK 타입
 * @param E 엔티티(DTO) 타입. 캐시 저장을 위해 [Serializable] 구현 필수.
 * @param config [LocalCacheConfig] 설정
 */
abstract class AbstractSuspendedJdbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.READ_ONLY,
): SuspendedJdbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private const val WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS = 30L
    }

    abstract override val table: IdTable<ID>

    /** [ResultRow]를 엔티티 [E]로 변환합니다 */
    abstract override fun ResultRow.toEntity(): E

    /** 기존 엔티티 UPDATE 시 컬럼 매핑 */
    abstract fun UpdateStatement.updateEntity(entity: E)

    /** 신규 엔티티 INSERT 시 컬럼 매핑 */
    abstract fun BatchInsertStatement.insertEntity(entity: E)

    /** 엔티티 ID를 캐시 키 문자열로 직렬화합니다 (기본: toString()) */
    open fun serializeKey(id: ID): String = id.toString()

    // -------------------------------------------------------------------------
    // SuspendedJdbcCacheRepository 필수 프로퍼티 구현
    // -------------------------------------------------------------------------

    /** 캐시 이름 (키 접두사로 사용) */
    override val cacheName: String
        get() = config.keyPrefix

    /** 캐시 저장 방식 — Caffeine은 항상 LOCAL */
    override val cacheMode: CacheMode
        get() = CacheMode.LOCAL

    /** 캐시 쓰기 전략 */
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

    /** 캐시 miss 조정 entry. users 변경은 [loadMutexes]의 key별 compute 안에서만 수행합니다. */
    private class LoadMutexEntry {
        val mutex = Mutex()
        var users: Int = 0
    }

    private val loadMutexes = ConcurrentHashMap<String, LoadMutexEntry>()

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
                        if (flushBatch(batch)) {
                            batch.clear()
                        }
                    }
                }
            } finally {
                // 채널 닫힌 후 남은 항목 처리
                if (batch.isNotEmpty()) {
                    withContext(NonCancellable) {
                        if (flushBatch(batch)) {
                            batch.clear()
                        }
                    }
                }
            }
        }
    }

    /**
     * Write-Behind 배치를 DB에 flush합니다.
     * AutoIncrement 테이블의 경우 신규 엔티티는 DB에 삽입하지 않습니다.
     *
     * CancellationException은 코루틴 취소 신호이므로 반드시 재전파해야 합니다.
     * 일반 DB 오류만 잡아서 로깅하고, 코루틴 취소는 상위로 전파합니다.
     */
    @Suppress("DEPRECATION")
    private suspend fun flushBatch(batch: List<Pair<ID, E>>): Boolean {
        try {
            suspendedTransactionAsync(Dispatchers.IO) {
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
            }.await()
            log.debug { "Write-Behind: ${batch.size}건 DB flush 완료" }
            return true
        } catch (e: CancellationException) {
            // 코루틴 취소는 삼키지 않고 반드시 재전파한다
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Write-Behind: ${batch.size}건 DB flush 실패" }
            return false
        }
    }

    // -------------------------------------------------------------------------
    // DB 직접 조회 (캐시 우회)
    // -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override suspend fun findByIdFromDb(id: ID): E? =
        suspendedTransactionAsync(Dispatchers.IO) {
            table
                .selectAll()
                .where { table.id eq id }
                .singleOrNull()
                ?.let { with(this@AbstractSuspendedJdbcCaffeineRepository) { it.toEntity() } }
        }.await()

    @Suppress("DEPRECATION")
    override suspend fun findAllFromDb(ids: Collection<ID>): List<E> =
        suspendedTransactionAsync(Dispatchers.IO) {
            if (ids.isEmpty()) return@suspendedTransactionAsync emptyList()
            table
                .selectAll()
                .where { table.id inList ids }
                .map { with(this@AbstractSuspendedJdbcCaffeineRepository) { it.toEntity() } }
        }.await()

    @Suppress("DEPRECATION")
    override suspend fun countFromDb(): Long =
        suspendedTransactionAsync(Dispatchers.IO) {
            table.selectAll().count()
        }.await()

    // -------------------------------------------------------------------------
    // 캐시 기반 조회 (Read-through)
    // -------------------------------------------------------------------------

    override suspend fun containsKey(id: ID): Boolean = get(id) != null

    /**
     * 캐시 miss를 같은 직렬화 키별로 suspend-safe하게 조정합니다.
     *
     * 성공한 값은 겹친 호출이 하나의 DB loader 결과를 관찰하도록 Caffeine에 저장합니다.
     * loader의 예외, [CancellationException], `null` 결과는 deferred outcome으로 공유하지
     * 않으며, 대기 중인 호출은 앞선 시도가 끝난 뒤 순차적으로 재시도할 수 있습니다.
     * 마지막 holder 또는 waiter가 끝나면 private 조정 entry를 회수하고, 호출자 취소는
     * 원래 [CancellationException]을 유지합니다.
     */
    override suspend fun get(id: ID): E? {
        val key = serializeKey(id)
        val cached = cache.getIfPresent(key)
        if (cached != null) return cached

        val entry = acquireLoadMutex(key)
        return try {
            entry.mutex.withLock {
                cache.getIfPresent(key)
                    ?: findByIdFromDb(id)?.let { fromDb ->
                        cache.asMap().putIfAbsent(key, fromDb) ?: fromDb
                    }
            }
        } finally {
            releaseLoadMutex(key, entry)
        }
    }

    override suspend fun getAll(ids: Collection<ID>): Map<ID, E> {
        if (ids.isEmpty()) return emptyMap()

        return ids.mapNotNull { id ->
            get(id)?.let { id to it }
        }.toMap()
    }

    private fun acquireLoadMutex(key: String): LoadMutexEntry =
        loadMutexes.compute(key) { _, current ->
            (current ?: LoadMutexEntry()).also { it.users += 1 }
        } ?: error("load mutex entry was not created")

    private fun releaseLoadMutex(key: String, entry: LoadMutexEntry) {
        loadMutexes.computeIfPresent(key) { _, current ->
            when {
                current !== entry -> current
                current.users <= 1 -> null
                else -> current.also { it.users -= 1 }
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun findAll(
        limit: Int?,
        offset: Long?,
        sortBy: Expression<*>,
        sortOrder: SortOrder,
        where: () -> Op<Boolean>,
    ): List<E> {
        val entities =
            suspendedTransactionAsync(Dispatchers.IO) {
                table
                    .selectAll()
                    .where(where)
                    .apply {
                        orderBy(sortBy, sortOrder)
                        limit?.let { limit(it) }
                        offset?.let { offset(it) }
                    }.map { with(this@AbstractSuspendedJdbcCaffeineRepository) { it.toEntity() } }
            }.await()
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
     * 엔티티에서 ID를 추출합니다.
     * [findAll] (where 조건 버전) 사용 시 서브클래스에서 override 필요.
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
                cache.put(key, entity)
                writeToDb(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                // Write-Behind Job 초기화 보장
                writeBehindJob
                try {
                    writeBehindQueue.send(id to entity)
                    cache.put(key, entity)
                } catch (e: Exception) {
                    cache.invalidate(key)
                    throw e
                }
            }

            else -> cache.put(key, entity)  // READ_ONLY: 캐시만 갱신
        }
    }

    override suspend fun putAll(entities: Map<ID, E>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        entities.forEach { (id, entity) -> put(id, entity) }
    }

    /**
     * Write-Through 시 단일 엔티티를 DB에 저장합니다.
     * AutoIncrement 테이블의 경우 신규 엔티티는 DB에 삽입하지 않습니다.
     */
    @Suppress("DEPRECATION")
    private suspend fun writeToDb(id: ID, entity: E) {
        suspendedTransactionAsync(Dispatchers.IO) {
            val updated = table.update({ table.id eq id }) {
                it.updateEntity(entity)
            }
            // AutoInc 테이블은 DB가 ID를 할당하므로 클라이언트 생성 ID로 INSERT하지 않는다
            if (updated == 0 && table.id.autoIncColumnType == null) {
                table.batchInsert(listOf(entity)) {
                    insertEntity(it)
                }
            }
        }.await()
    }

    // -------------------------------------------------------------------------
    // 삭제
    // -------------------------------------------------------------------------

    override suspend fun invalidate(id: ID) {
        cache.invalidate(serializeKey(id))
    }

    override suspend fun invalidateAll(ids: Collection<ID>) {
        val keys = ids.map { serializeKey(it) }
        cache.invalidateAll(keys)
    }

    // -------------------------------------------------------------------------
    // 캐시 관리
    // -------------------------------------------------------------------------

    override suspend fun clear() {
        cache.invalidateAll()
    }

    /**
     * 레포지토리를 닫고 리소스를 해제합니다.
     *
     * Write-Behind 모드에서는 채널을 닫아 더 이상 새 항목을 받지 않고,
     * 이미 큐에 있는 항목이 모두 DB에 flush될 때까지 대기합니다.
     *
     * bounded synchronous latch로 대기하여 DB/드라이버 hang이 종료를 무한정 막지 않게 합니다.
     */
    override fun close() {
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            writeBehindQueue.close()
            awaitWriteBehindJobCompletion()
        }
        invalidateCacheOnCloseSafely()
        cancelScopeOnClose()
    }

    protected open fun invalidateCacheOnClose() {
        cache.invalidateAll()
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

    private fun awaitWriteBehindJobCompletion() {
        val completed = CountDownLatch(1)
        writeBehindJob.invokeOnCompletion { completed.countDown() }

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
                    "Remaining queued items may be discarded when the repository scope is cancelled. " +
                    "timeoutSeconds=$WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS"
            }
        }
    }
}
