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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/**
 * Abstract repository combining Exposed JDBC with a Caffeine in-process local cache.
 *
 * Uses a Caffeine [Cache] for in-process caching. All database access is synchronous
 * via JDBC `transaction {}`.
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

    companion object: KLogging()

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
    // JdbcCacheRepository 필수 프로퍼티 구현
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
                        flushBatch(batch)
                        batch.clear()
                    }
                }
            } finally {
                // 채널 닫힌 후 남은 항목 처리
                if (batch.isNotEmpty()) {
                    flushBatch(batch)
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
    private fun flushBatch(batch: List<Pair<ID, E>>) {
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
        } catch (e: CancellationException) {
            // 코루틴 취소는 삼키지 않고 반드시 재전파한다
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Write-Behind: ${batch.size}건 DB flush 실패" }
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
        val cached = cache.getIfPresent(key)
        if (cached != null) return cached

        val fromDb = findByIdFromDb(id) ?: return null
        // putIfAbsent is atomic: if another thread wrote a value concurrently, we keep theirs.
        return cache.asMap().putIfAbsent(key, fromDb) ?: fromDb
    }

    override fun getAll(ids: Collection<ID>): Map<ID, E> {
        if (ids.isEmpty()) return emptyMap()

        val result = mutableMapOf<ID, E>()
        val missedIds = mutableListOf<ID>()

        for (id in ids) {
            val key = serializeKey(id)
            val cached = cache.getIfPresent(key)
            if (cached != null) {
                result[id] = cached
            } else {
                missedIds.add(id)
            }
        }

        if (missedIds.isNotEmpty()) {
            val fromDb = findAllFromDb(missedIds)
            for (entity in fromDb) {
                val id = extractId(entity)
                result[id] = entity
                cache.asMap().putIfAbsent(serializeKey(id), entity)
            }
        }

        return result
    }

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

    override fun put(id: ID, entity: E) {
        val key = serializeKey(id)

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> {
                cache.put(key, entity)
                writeToDb(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                // Write-Behind Job 초기화 보장
                writeBehindJob
                // runBlocking is avoided to prevent Virtual Thread pinning.
                // trySend() fails immediately when the queue is full — throw to prevent silent data loss.
                // cache.put() is intentionally deferred until after trySend() succeeds to prevent
                // a cache entry existing without a corresponding queued DB write.
                val result = writeBehindQueue.trySend(id to entity)
                if (result.isFailure) {
                    throw IllegalStateException(
                        "Write-Behind queue is full (capacity=${config.writeBehindQueueCapacity}). " +
                            "Entity id=$id was NOT persisted to the database. " +
                            "Increase LocalCacheConfig.writeBehindQueueCapacity or reduce the write rate."
                    )
                }
                cache.put(key, entity)
            }

            else -> cache.put(key, entity)  // READ_ONLY: 캐시만 갱신
        }
    }

    override fun putAll(entities: Map<ID, E>, batchSize: Int) {
        batchSize.requirePositiveNumber("batchSize")
        entities.forEach { (id, entity) -> put(id, entity) }
    }

    /**
     * Write-Through 시 단일 엔티티를 DB에 저장합니다.
     * AutoIncrement 테이블의 경우 신규 엔티티는 DB에 삽입하지 않습니다.
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

    /**
     * 레포지토리를 닫고 리소스를 해제합니다.
     *
     * Write-Behind 모드에서는 채널을 닫아 더 이상 새 항목을 받지 않고,
     * 이미 큐에 있는 항목이 모두 DB에 flush될 때까지 대기합니다.
     *
     * [runBlocking]은 [Closeable] 계약을 이행하기 위해 불가피하게 사용합니다.
     * 정상 종료 시 호출되는 맥락이므로 Virtual Thread pinning 위험은 허용 범위입니다.
     */
    override fun close() {
        if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
            try {
                writeBehindQueue.close()
            } catch (e: Exception) {
                log.warn(e) { "Failed to close writeBehindQueue on close" }
            }
            try {
                runBlocking { writeBehindJob.join() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Failed to join writeBehindJob on close" }
            }
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
}
