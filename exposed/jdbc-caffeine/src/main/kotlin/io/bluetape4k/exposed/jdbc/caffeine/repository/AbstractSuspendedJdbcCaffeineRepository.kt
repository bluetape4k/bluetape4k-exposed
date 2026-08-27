package io.bluetape4k.exposed.jdbc.caffeine.repository

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
@Suppress("LargeClass") // suspended JDBC cache와 write-behind 수명주기는 하나의 원자적 호환 경계다.
abstract class AbstractSuspendedJdbcCaffeineRepository<ID: Any, E: Serializable>(
    override val config: LocalCacheConfig = LocalCacheConfig.READ_ONLY,
): SuspendedJdbcCaffeineRepository<ID, E> {

    companion object: KLogging() {
        private const val WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS = 30L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val WRITE_BEHIND_CLOSE_JOIN_GRACE_MILLIS = 100L
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

    private val writeBehindQueue: Channel<WriteBehindEntry<ID, E>> by lazy {
        Channel(capacity = config.writeBehindQueueCapacity)
    }

    private val writeBehindCoordinator = WriteBehindCoordinator(config.writeMode)
    private val writeBehindPendingAdmissions = AtomicInteger(0)
    private val writeBehindQueueDepth = AtomicInteger(0)
    private val writeBehindPendingCapacity = config.writeBehindQueueCapacity
    private val writeBehindFailedBatch = AtomicReference<List<Pair<ID, E>>>(emptyList())
    private val lastFlushError = AtomicReference<Throwable?>(null)
    private val writeBehindCoordinatorCloseLease = AtomicReference<CloseLease.Owner?>(null)
    private val writeBehindCoordinatorCompletionPublished = AtomicBoolean(false)
    private val writeBehindPublicationLock = ReentrantLock()
    private val writeBehindPublicationChanged = writeBehindPublicationLock.newCondition()
    private val writeBehindCloseLock = ReentrantLock()
    private val writeBehindCachePublicationLocks = ConcurrentHashMap<String, CachePublicationLock>()
    private val writeBehindCloseCleanupPending = AtomicBoolean(false)
    private val writeBehindLateSideEffectGuard = AtomicBoolean(false)
    private var writeBehindCloseStarted = false
    private var writeBehindCloseDeadlineNanos = 0L
    private val writeBehindCloseFailureReason = AtomicReference<WriteBehindCloseFailureReason?>(null)
    private var writeBehindPublicationsInProgress = 0

    /** 캐시 miss 조정 entry. users 변경은 [loadMutexes]의 key별 compute 안에서만 수행합니다. */
    private class LoadMutexEntry {
        val mutex = Mutex()
        var users: Int = 0
    }

    private val loadMutexes = ConcurrentHashMap<String, LoadMutexEntry>()

    @Suppress(
        "LoopWithTooManyJumpStatements",
        "TooGenericExceptionCaught",
    ) // queue drain에서 cancellation과 failed-worker 전환을 명시적으로 유지한다.
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
                        val flushedCount = batch.size
                        if (flushBatchWithRetry(batch, writeBehindCloseDeadlineNanos.takeIf { it > 0L }) &&
                            settleSuccessfulWriteBehindBatch(batch, flushedCount)
                        ) {
                            // publication fence가 회계를
                            // late-side-effect guard와 함께 정산한다.
                        } else {
                            if (writeBehindLateSideEffectGuard.get()) {
                                batch.clear()
                                break
                            }
                            retainWriteBehindFailedBatch(batch)
                            batch.clear()
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                // The NonCancellable final flush must settle accepted work before the
                // completion callback marks the coordinator terminal.
                writeBehindQueue.close(e)
                throw e
            } catch (e: Throwable) {
                writeBehindCoordinator.onCloseFailed(WriteBehindFailureKind.WORKER)
                writeBehindQueue.close(e)
                throw e
            } finally {
                // 채널 닫힌 후 남은 항목 처리
                if (batch.isNotEmpty()) {
                    val flushedCount = batch.size
                    if (flushFinalBatch(batch) && settleSuccessfulWriteBehindBatch(batch, flushedCount)) {
                        // publication fence가 회계를
                        // late-side-effect guard와 함께 정산한다.
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
                if (writeBehindLateSideEffectGuard.get()) return@invokeOnCompletion
                writeBehindCoordinator.onWorkerCompleted(
                    when {
                        cause == null -> WriteBehindWorkerCompletion.DRAINED
                        cause is CancellationException -> WriteBehindWorkerCompletion.CANCELLED
                        else -> WriteBehindWorkerCompletion.FAILED
                    }
                )
            }
        }
    }

    /** Coordinator admission is the only production source of successful flush tokens. */
    private fun recordCoordinatorFlushSuccess(count: Int) {
        val queueDepth = writeBehindCoordinator.snapshot().queueDepth
        check(queueDepth >= count) {
            "Write-Behind coordinator queue depth[$queueDepth] is below flushed count[$count]"
        }
        writeBehindCoordinator.onFlushSucceeded(count)
    }

    /**
     * close timeout/interruption과 worker 성공 회계를 선형화합니다.
     * 이 adapter에서는 publication lock이 terminal guard 경계이기도 합니다.
     */
    private fun settleSuccessfulWriteBehindBatch(
        batch: MutableList<Pair<ID, E>>,
        flushedCount: Int,
    ): Boolean = writeBehindPublicationLock.withLock {
        if (writeBehindLateSideEffectGuard.get()) return@withLock false
        recordCoordinatorFlushSuccess(flushedCount)
        releaseWriteBehindQueueDepth(flushedCount)
        releaseWriteBehindPending(flushedCount)
        batch.clear()
        true
    }

    private suspend fun flushFinalBatch(batch: List<Pair<ID, E>>): Boolean = withContext(NonCancellable) {
        val deadlineNanos = writeBehindCloseDeadlineNanos.takeIf { it > 0L }
        if (deadlineNanos == null || deadlineNanos == Long.MAX_VALUE) {
            return@withContext flushBatchWithRetry(batch, deadlineNanos)
        }
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return@withContext false
        val remainingMillis = remainingNanos / NANOS_PER_MILLISECOND
        if (remainingMillis <= 0L) return@withContext false
        withTimeoutOrNull(remainingMillis) {
            flushBatchWithRetry(batch, deadlineNanos)
        } ?: false
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

    private fun releaseWriteBehindQueueDepth(count: Int) {
        if (count <= 0) return
        writeBehindQueueDepth.updateAndGet { current ->
            check(current >= count) {
                "Write-Behind queue depth accounting underflow: current=$current, release=$count"
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

    private fun writeBehindNotAcceptingException(): IllegalStateException {
        val snapshot = writeBehindCoordinator.snapshot()
        return IllegalStateException(
            "Write-Behind worker is not accepting writes because the repository is closing, closed, or terminal. " +
                "cacheName=$cacheName, workerState=${snapshot.workerState}, " +
                "terminalReason=${snapshot.failureKind?.name ?: snapshot.workerState.name}"
        )
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
            lastFlushError.set(null)
            return true
        } catch (e: CancellationException) {
            // 코루틴 취소는 삼키지 않고 반드시 재전파한다
            throw e
        } catch (e: Exception) {
            lastFlushError.set(e)
            log.warn {
                "Write-Behind event: component=suspended-jdbc operation=flush " +
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
                writeBehindCoordinator.onCloseFailed(WriteBehindFailureKind.WORKER)
                writeBehindQueue.close(failure)
                return false
            }
            if (flushBatch(batch) && !writeBehindLateSideEffectGuard.get()) return true
            if (writeBehindLateSideEffectGuard.get()) return false
            writeBehindCoordinator.onFlushFailed()
            if (attempt == MAX_FLUSH_RETRY_ATTEMPTS) {
                val failure = IllegalStateException("Write-Behind flush retry limit exhausted")
                writeBehindCoordinator.onCloseFailed(WriteBehindFailureKind.WORKER)
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
                } catch (_: Exception) {
                    log.warn {
                        "Cache event: component=suspended-jdbc operation=cache_warming failureKind=error " +
                            "queueDepth=${writeBehindQueueDepth.get()}"
                    }
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

    @Suppress(
        "ThrowsCount",
        "LongMethod",
        "CyclomaticComplexMethod",
        "TooGenericExceptionCaught",
        "NestedBlockDepth",
    ) // admission/publication/terminal failure는 하나의 원자적 suspend 계약이다.
    override suspend fun put(id: ID, entity: E) {
        val key = serializeKey(id)

        when (config.writeMode) {
            CacheWriteMode.WRITE_THROUGH -> {
                cache.put(key, entity)
                writeToDb(id, entity)
            }
            CacheWriteMode.WRITE_BEHIND -> {
                val admissionToken = try {
                    writeBehindCoordinator.reserveAdmission()
                } catch (_: IllegalStateException) {
                    throw writeBehindNotAcceptingException()
                }
                val queuedEntry = WriteBehindEntry(id, entity)
                if (!tryReserveWriteBehindPending()) {
                    writeBehindCoordinator.settleEnqueue(admissionToken, accepted = false)
                    throw IllegalStateException(
                        "Write-Behind queue is full (capacity=${config.writeBehindQueueCapacity})."
                    )
                }
                // Write-Behind Job 초기화 보장
                writeBehindJob
                var publicationLeaseAcquired = false
                var admissionSettled = false
                var accepted = false
                var pendingReserved = true
                var terminalFailure: IllegalStateException? = null
                try {
                    writeBehindQueue.send(queuedEntry)
                    publicationLeaseAcquired = tryAcquireWriteBehindPublicationLease()
                    if (!publicationLeaseAcquired) {
                        throw writeBehindNotAcceptingException()
                    }
                    writeBehindCoordinator.markEnqueued(admissionToken)
                    val acceptedByCoordinator = writeBehindCoordinator.settleEnqueue(admissionToken, accepted = true)
                    admissionSettled = true
                    if (!acceptedByCoordinator) {
                        queuedEntry.accepted.complete(false)
                        throw IllegalStateException("Write-Behind worker is not accepting writes after queue handoff")
                    }
                    // deferred gate를 열기 전에 legacy depth를 먼저 기록한다. worker가
                    // accepted=true를 관찰하자마자 flush할 수 있으므로 이 항목이 depth에
                    // 포함된 뒤 gate를 열어야 한다.
                    writeBehindQueueDepth.incrementAndGet()
                    accepted = queuedEntry.accepted.complete(true)
                    check(accepted) { "Write-Behind admission was already settled" }
                    publishWriteBehindCache(key, entity)
                    if (writeBehindCloseStarted()) {
                        // close may have timed out while a synchronous cache publication
                        // was blocked; invalidate again after the late publication.
                        invalidateCacheEntrySafely(key)
                    }
                } catch (e: Exception) {
                    if (!admissionSettled) {
                        writeBehindCoordinator.settleEnqueue(admissionToken, accepted = false)
                    }
                    if (!accepted) {
                        if (!queuedEntry.accepted.isCompleted) {
                            queuedEntry.accepted.complete(false)
                        }
                        if (pendingReserved) {
                            releaseWriteBehindPending(1)
                            pendingReserved = false
                        }
                    }
                    try {
                        cache.invalidate(key)
                    } catch (invalidateFailure: Throwable) {
                        e.addSuppressed(invalidateFailure)
                    }
                    throw e
                } finally {
                    try {
                        if (accepted &&
                            writeBehindCoordinator.snapshot().workerState == CacheWorkerState.FAILED
                        ) {
                            terminalFailure = writeBehindNotAcceptingException()
                            try {
                                cache.invalidate(key)
                            } catch (invalidateFailure: Throwable) {
                                terminalFailure.addSuppressed(invalidateFailure)
                            }
                        }
                    } finally {
                        if (publicationLeaseAcquired) {
                            releaseWriteBehindPublicationLease()
                        }
                    }
                }
                terminalFailure?.let { throw it }
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
        writeBehindCloseLock.withLock {
            if (config.writeMode == CacheWriteMode.WRITE_BEHIND) {
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WRITE_BEHIND_CLOSE_TIMEOUT_SECONDS)
                beginWriteBehindClose(deadlineNanos)
                val drained = awaitWriteBehindJobCompletion(deadlineNanos)
                val publicationsDrained = awaitWriteBehindPublicationDrain(deadlineNanos)
                val closeFailure = writeBehindCloseFailureReason.get()
                if (closeFailure != null) {
                    writeBehindCoordinator.onCloseFailed(closeFailure.toCoordinatorFailureKind())
                    cancelWriteBehindAfterClose(closeFailure)
                }
                cancelScopeOnClose()
                val publicationsPending = markCloseCleanupPendingIfNeeded(publicationsDrained)
                if (!publicationsPending) {
                    invalidateCacheOnCloseSafely()
                    publishCoordinatorCloseCompletion(drained && publicationsDrained)
                } else {
                    finishDeferredCloseCleanupIfReady()
                }
                return
            }
            invalidateCacheOnCloseSafely()
            cancelScopeOnClose()
        }
    }

    protected open fun invalidateCacheOnClose() {
        cache.invalidateAll()
    }

    protected open fun cancelScopeOnClose() {
        scope.cancel()
    }

    private fun cancelWriteBehindAfterClose(reason: WriteBehindCloseFailureReason) {
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
                "Write-Behind event: component=suspended-jdbc operation=close " +
                    "failureKind=close_join_timeout queueDepth=${writeBehindQueueDepth.get()} " +
                    "closeFailure=${reason.logName}"
            }
        }
    }

    private fun invalidateCacheOnCloseSafely() {
        try {
            invalidateCacheOnClose()
        } catch (_: Exception) {
            log.warn {
                "Write-Behind event: component=suspended-jdbc operation=close_cleanup " +
                    "failureKind=cache_invalidate queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    private fun beginWriteBehindClose(deadlineNanos: Long) {
        writeBehindPublicationLock.withLock {
            writeBehindCloseStarted = true
            writeBehindCloseDeadlineNanos = deadlineNanos
            writeBehindQueue.close()
        }
    }

    private fun tryAcquireWriteBehindPublicationLease(): Boolean = writeBehindPublicationLock.withLock {
        if (writeBehindCloseStarted) return@withLock false
        writeBehindPublicationsInProgress += 1
        true
    }

    private fun releaseWriteBehindPublicationLease() {
        val shouldFinish = writeBehindPublicationLock.withLock {
            check(writeBehindPublicationsInProgress > 0) {
                "Write-Behind publication lease accounting underflow"
            }
            writeBehindPublicationsInProgress -= 1
            writeBehindPublicationChanged.signalAll()
            writeBehindCloseCleanupPending.get() && writeBehindPublicationsInProgress == 0
        }
        if (shouldFinish) finishDeferredCloseCleanupIfReady()
    }

    private fun writeBehindCloseStarted(): Boolean = writeBehindPublicationLock.withLock {
        writeBehindCloseStarted
    }

    private fun publishWriteBehindCache(key: String, entity: E) {
        val publicationLock = acquireCachePublicationLock(key)
        try {
            publicationLock.lock.withLock {
                if (writeBehindLateSideEffectGuard.get()) {
                    throw writeBehindNotAcceptingException()
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun invalidateCacheEntrySafely(key: String) {
        try {
            cache.invalidate(key)
        } catch (failure: Throwable) {
            log.warn {
                "Write-Behind event: component=suspended-jdbc operation=late_publication_invalidate " +
                    "failureKind=cache_invalidate queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
    }

    @Suppress("ReturnCount") // deadline과 interruption 결과를 의도적으로 구분한다.
    private fun awaitWriteBehindPublicationDrain(deadlineNanos: Long): Boolean {
        writeBehindPublicationLock.withLock {
            while (writeBehindPublicationsInProgress > 0) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0L) {
                    writeBehindCloseFailureReason.compareAndSet(null, WriteBehindCloseFailureReason.TIMEOUT)
                    writeBehindLateSideEffectGuard.set(true)
                    return false
                }
                try {
                    if (writeBehindPublicationChanged.awaitNanos(remaining) <= 0L &&
                        writeBehindPublicationsInProgress > 0
                    ) {
                        writeBehindCloseFailureReason.compareAndSet(null, WriteBehindCloseFailureReason.TIMEOUT)
                        writeBehindLateSideEffectGuard.set(true)
                        return false
                    }
                } catch (_: InterruptedException) {
                    writeBehindCloseFailureReason.compareAndSet(null, WriteBehindCloseFailureReason.INTERRUPTED)
                    writeBehindLateSideEffectGuard.set(true)
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    private fun markCloseCleanupPendingIfNeeded(publicationsDrained: Boolean): Boolean =
        writeBehindPublicationLock.withLock {
            if (publicationsDrained || writeBehindPublicationsInProgress == 0) {
                false
            } else {
                writeBehindCloseCleanupPending.set(true)
                true
            }
        }

    @Suppress("ReturnCount") // deferred cleanup은 빠른 비활성/락/회계 검사를 유지한다.
    private fun finishDeferredCloseCleanupIfReady() {
        if (!writeBehindCloseCleanupPending.get()) return
        writeBehindCloseLock.lock()
        try {
            val ready = writeBehindPublicationLock.withLock {
                writeBehindPublicationsInProgress == 0
            }
            if (!ready || !writeBehindCloseCleanupPending.compareAndSet(true, false)) return
            invalidateCacheOnCloseSafely()
            publishCoordinatorCloseCompletion(drained = false)
        } finally {
            writeBehindCloseLock.unlock()
        }
    }

    private fun awaitWriteBehindJobCompletion(deadlineNanos: Long): Boolean {
        (writeBehindCoordinator.beginClose() as? CloseLease.Owner)?.let {
            writeBehindCoordinatorCloseLease.compareAndSet(null, it)
        }
        val completed = CountDownLatch(1)
        writeBehindJob.invokeOnCompletion { completed.countDown() }

        var closeFailureKind = WriteBehindCloseFailureReason.TIMEOUT
        val completedInTime =
            try {
                val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
                completed.await(remaining, TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                closeFailureKind = WriteBehindCloseFailureReason.INTERRUPTED
                writeBehindCloseFailureReason.compareAndSet(null, closeFailureKind)
                writeBehindLateSideEffectGuard.set(true)
                Thread.currentThread().interrupt()
                false
            }

        if (!completedInTime) {
            writeBehindCloseFailureReason.compareAndSet(null, closeFailureKind)
            writeBehindLateSideEffectGuard.set(true)
            log.warn {
                "Write-Behind event: component=suspended-jdbc operation=close " +
                    "failureKind=${closeFailureKind.logName} queueDepth=${writeBehindQueueDepth.get()}"
            }
        }
        return completedInTime && !writeBehindJob.isCancelled
    }

    private fun publishCoordinatorCloseCompletion(drained: Boolean) {
        val owner = writeBehindCoordinatorCloseLease.get() ?: return
        if (!writeBehindCoordinatorCompletionPublished.compareAndSet(false, true)) return
        val snapshot = writeBehindCoordinator.snapshot()
        val completed = drained && snapshot.queueDepth == 0
        writeBehindCoordinator.publishCloseCompletion(
            owner,
            CloseCompletion(
                kind = if (completed) {
                    CloseCompletionKind.COMPLETED
                } else {
                    when (writeBehindCloseFailureReason.get()) {
                        WriteBehindCloseFailureReason.TIMEOUT -> CloseCompletionKind.TIMEOUT
                        WriteBehindCloseFailureReason.INTERRUPTED -> CloseCompletionKind.INTERRUPTED
                        null -> CloseCompletionKind.FAILED
                    }
                },
                workerState = if (completed) CacheWorkerState.STOPPED else CacheWorkerState.FAILED,
                queueDepth = snapshot.queueDepth,
            ),
        )
    }

    private enum class WriteBehindCloseFailureReason(val logName: String) {
        TIMEOUT("close_timeout"),
        INTERRUPTED("close_interrupted"),
    }

    private fun WriteBehindCloseFailureReason.toCoordinatorFailureKind(): WriteBehindFailureKind = when (this) {
        WriteBehindCloseFailureReason.TIMEOUT -> WriteBehindFailureKind.CLOSE_TIMEOUT
        WriteBehindCloseFailureReason.INTERRUPTED -> WriteBehindFailureKind.CLOSE_INTERRUPTED
    }

    private data class WriteBehindEntry<ID, E>(
        val id: ID,
        val entity: E,
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
    )
}
