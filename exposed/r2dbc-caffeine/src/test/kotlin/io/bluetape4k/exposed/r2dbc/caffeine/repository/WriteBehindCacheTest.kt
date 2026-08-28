package io.bluetape4k.exposed.r2dbc.caffeine.repository

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.cache.scenarios.R2dbcWriteBehindScenario
import io.bluetape4k.exposed.r2dbc.caffeine.AbstractR2dbcCaffeineTest
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorR2dbcCaffeineRepository
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.ActorTable
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.CredentialRecord
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.CredentialTable
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.findActorById
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.toActorRecord
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.withActorTable
import io.bluetape4k.exposed.r2dbc.caffeine.domain.ActorSchema.withCredentialTable
import io.bluetape4k.exposed.r2dbc.caffeine.domain.CredentialR2dbcCaffeineRepository
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import kotlin.coroutines.CoroutineContext

/**
 * R2DBC Caffeine Write-Behind 캐시 통합 테스트.
 *
 * - AutoIncrement Long ID 테이블 ([ActorTable]) 과
 * - Client-generated UUID ID 테이블 ([CredentialTable]) 에 대해 각각 검증합니다.
 * 캐시에 먼저 저장하고 DB에는 비동기로 반영되는 패턴을 검증합니다.
 */
@Isolated
class WriteBehindCacheTest {

    companion object: KLoggingChannel()

    @Test
    fun `close cancels scope when cache invalidate fails`() {
        val repository = CloseProbeR2dbcCaffeineRepository()

        repository.close()

        repository.scopeCancelled.shouldBeTrue()
    }

    private class CloseProbeR2dbcCaffeineRepository:
        AbstractR2dbcCaffeineRepository<Long, ActorRecord>(
            LocalCacheConfig(
                keyPrefix = "r2dbc:caffeine:close-probe",
                writeMode = CacheWriteMode.READ_ONLY
            )
        ) {
        var scopeCancelled: Boolean = false

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = error("not used")

        override fun UpdateStatement.updateEntity(entity: ActorRecord) = Unit

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) = Unit

        override fun extractId(entity: ActorRecord): Long = entity.id

        override fun invalidateCacheOnClose() {
            throw IllegalStateException("planned cache invalidate failure")
        }

        override fun cancelScopeOnClose() {
            scopeCancelled = true
        }
    }

    // -------------------------------------------------------------------------
    // AutoIncrement Long ID — ActorTable
    // -------------------------------------------------------------------------

    @Nested
    inner class AutoIncActorWriteBehind:
        AbstractR2dbcCaffeineTest(),
        R2dbcWriteBehindScenario<Long, ActorRecord> {

        override val cacheWriteMode: CacheWriteMode = CacheWriteMode.WRITE_BEHIND
        override val cacheMode: CacheMode = CacheMode.LOCAL

        private val config = LocalCacheConfig(
            keyPrefix = "r2dbc:caffeine:write-behind:actors",
            writeMode = CacheWriteMode.WRITE_BEHIND,
            writeBehindBatchSize = 10,
            writeBehindQueueCapacity = 1_000,
        )

        override val repository by lazy {
            ActorR2dbcCaffeineRepository(config)
        }

        override suspend fun withR2dbcEntityTable(
            testDB: TestDB,
            context: CoroutineContext,
            statement: suspend R2dbcTransaction.() -> Unit,
        ) = withActorTable(testDB, statement)

        override suspend fun getExistingId(): Long =
            suspendTransaction {
                ActorTable.select(ActorTable.id).first()[ActorTable.id].value
            }

        override suspend fun getExistingIds(): List<Long> =
            suspendTransaction {
                ActorTable.select(ActorTable.id).map { it[ActorTable.id].value }.toList()
            }

        override suspend fun getNonExistentId(): Long = Long.MIN_VALUE

        override suspend fun createNewEntity(): ActorRecord =
            ActorSchema.newActorRecord()
    }

    // -------------------------------------------------------------------------
    // Client-generated UUID ID — CredentialTable
    // -------------------------------------------------------------------------

    @Nested
    inner class ClientGenIdCredentialWriteBehind:
        AbstractR2dbcCaffeineTest(),
        R2dbcWriteBehindScenario<UUID, CredentialRecord> {

        override val cacheWriteMode: CacheWriteMode = CacheWriteMode.WRITE_BEHIND
        override val cacheMode: CacheMode = CacheMode.LOCAL

        private val config = LocalCacheConfig(
            keyPrefix = "r2dbc:caffeine:write-behind:credentials",
            writeMode = CacheWriteMode.WRITE_BEHIND,
            writeBehindBatchSize = 10,
            writeBehindQueueCapacity = 1_000,
        )

        override val repository by lazy {
            CredentialR2dbcCaffeineRepository(config)
        }

        override suspend fun withR2dbcEntityTable(
            testDB: TestDB,
            context: CoroutineContext,
            statement: suspend R2dbcTransaction.() -> Unit,
        ) = withCredentialTable(testDB, statement)

        override suspend fun getExistingId(): UUID =
            suspendTransaction {
                CredentialTable.select(CredentialTable.id).first()[CredentialTable.id].value
            }

        override suspend fun getExistingIds(): List<UUID> =
            suspendTransaction {
                CredentialTable.select(CredentialTable.id).map { it[CredentialTable.id].value }.toList()
            }

        override suspend fun getNonExistentId(): UUID = Uuid.V7.nextId()

        override suspend fun createNewEntity(): CredentialRecord =
            ActorSchema.newCredentialRecord()
    }

    // -------------------------------------------------------------------------
    // Cancellation-safe final flush
    // -------------------------------------------------------------------------

    @Nested
    inner class CancellationSafeFinalFlush: AbstractR2dbcCaffeineTest() {

        @Test
        fun `close production wait default remains thirty seconds`() {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-default",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                )
            )

            try {
                repository.writeBehindCloseWaitDuration shouldBeEqualTo Duration.ofSeconds(30)
            } finally {
                repository.close()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind final batch is flushed after worker cancellation`(testDB: TestDB) = runSuspendIO {
            val config = LocalCacheConfig(
                keyPrefix = "r2dbc:caffeine:write-behind:cancel-final-flush",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 10,
                writeBehindQueueCapacity = 16,
            )
            val cachePutEntered = CountDownLatch(1)
            val releaseCachePut = CountDownLatch(1)
            val backing = Caffeine.newBuilder().buildAsync<String, ActorRecord>()
            lateinit var repository: CancellingActorRepository
            repository = CancellingActorRepository(config) {
                writeBehindJobOf(repository)
            }
            replaceCache(
                repository,
                BlockingPutAsyncCache(backing, cachePutEntered, releaseCachePut),
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "cancel-safe-final-flush")

                try {
                    val putFailure = async {
                        try {
                            repository.put(existingId, updated)
                            null
                        } catch (failure: IllegalStateException) {
                            failure
                        }
                    }
                    cachePutEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.FAILED }
                    releaseCachePut.countDown()

                    val failure = putFailure.await().shouldNotBeNull()
                    failure.message.orEmpty()
                        .contains("workerState=FAILED")
                        .shouldBeTrue()
                    failure.message.orEmpty()
                        .contains("terminalReason=")
                        .shouldBeTrue()
                    writeBehindJobOf(repository).join()

                    repository.updateAttempts.get() shouldBeEqualTo 2
                    findActorById(existingId).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
                } finally {
                    releaseCachePut.countDown()
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close waits for write-behind final flush to finish`(testDB: TestDB) = runSuspendIO {
            val config = LocalCacheConfig(
                keyPrefix = "r2dbc:caffeine:write-behind:close-final-flush",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 10,
                writeBehindQueueCapacity = 16,
            )
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = CloseWaitingActorRepository(
                config = config,
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "close-waits-final-flush")

                repository.put(existingId, updated)
                val closeFuture = CompletableFuture.runAsync {
                    repository.close()
                }

                try {
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    closeFuture.isDone.shouldBeFalse()
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                        .workerState shouldBeEqualTo CacheWorkerState.DRAINING
                    releaseFlush.countDown()
                    closeFuture.get(5, TimeUnit.SECONDS)

                    findActorById(existingId).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
                } finally {
                    releaseFlush.countDown()
                    closeFuture.cancel(true)
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close before any write-behind put completes without hanging`(testDB: TestDB) = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-before-put",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withActorTable(testDB) {
                repository.close()

                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `close timeout is failed and late worker completion cannot overwrite it`() = runSuspendIO {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = CloseWaitingActorRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-timeout",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
                closeWaitDuration = Duration.ofMillis(50),
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                try {
                    repository.put(actor.id, actor.copy(firstName = "close-timeout"))
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    repository.close()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                } finally {
                    releaseFlush.countDown()
                    writeBehindJobOf(repository).join()
                    repository.close()
                }
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `interrupted close restores flag and remains failed after late completion`() = runSuspendIO {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val closeCompleted = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val repository = CloseWaitingActorRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-interrupted",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                repository.put(actor.id, actor.copy(firstName = "close-interrupted"))
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val closeThread = Thread {
                    try {
                        repository.close()
                        interrupted.set(Thread.currentThread().isInterrupted)
                    } finally {
                        closeCompleted.countDown()
                    }
                }.apply { start() }

                try {
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                    closeThread.interrupt()
                    closeCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    interrupted.get().shouldBeTrue()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                } finally {
                    releaseFlush.countDown()
                    closeThread.interrupt()
                    closeThread.join(5_000)
                    writeBehindJobOf(repository).join()
                    repository.close()
                }
                closeThread.isAlive.shouldBeFalse()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `interrupted follower preserves flag without overriding owner completion`() = runSuspendIO {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val ownerCompleted = CountDownLatch(1)
            val followerCompleted = CountDownLatch(1)
            val followerInterrupted = AtomicBoolean(false)
            val repository = CloseWaitingActorRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-follower",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                repository.put(actor.id, actor.copy(firstName = "close-follower"))
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val owner = Thread {
                    try {
                        repository.close()
                    } finally {
                        ownerCompleted.countDown()
                    }
                }.apply { start() }
                awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                val follower = Thread {
                    try {
                        repository.close()
                        followerInterrupted.set(Thread.currentThread().isInterrupted)
                    } finally {
                        followerCompleted.countDown()
                    }
                }.apply { start() }

                try {
                    awaitThreadState(follower, Thread.State.WAITING).shouldBeTrue()
                    follower.interrupt()
                    delay(50)
                    followerCompleted.await(50, TimeUnit.MILLISECONDS).shouldBeFalse()
                    releaseFlush.countDown()
                    ownerCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    followerCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                } finally {
                    releaseFlush.countDown()
                    owner.interrupt()
                    follower.interrupt()
                    owner.join(5_000)
                    follower.join(5_000)
                    repository.close()
                }

                followerInterrupted.get().shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `await interruption wins even when completion callback later acquires lifecycle lock`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:completion-before-interrupt",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )
            val token = CompletableDeferred<Boolean>()
            val actor = ActorSchema.newActorRecord()
            val closeCompleted = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)

            startWriteBehindJob(repository)
            reserveAdmission(repository, actor.id.toString())
            writeBehindQueueOf(repository).send(writeBehindEntry(actor.id, actor, token))

            val closeThread = Thread {
                try {
                    repository.close()
                    interrupted.set(Thread.currentThread().isInterrupted)
                } finally {
                    closeCompleted.countDown()
                }
            }.apply { start() }

            try {
                awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                val lifecycleLock = writeBehindLifecycleLockOf(repository)
                lifecycleLock.lock()
                try {
                    val completedAt = System.nanoTime()
                    setWriteBehindQueueDepth(repository, 0)
                    setWriteBehindAdmissions(repository, inProgress = 0, drainedAtNanos = completedAt)
                    setWriteBehindJobCompletion(repository, cause = null, completedAtNanos = completedAt)
                    // The JVM exposes the thrown InterruptedException, not the timestamp of the
                    // interrupt() call. Holding the lock makes the observable order deterministic:
                    // await throws first, while callback-style publication can only acquire later.
                    closeThread.interrupt()
                } finally {
                    lifecycleLock.unlock()
                }

                closeCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                interrupted.get().shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                val rejected = ActorSchema.newActorRecord()
                val failure = assertFailsWith<IllegalStateException> {
                    repository.put(rejected.id, rejected)
                }
                failure.message.orEmpty().contains("INTERRUPTED").shouldBeTrue()
            } finally {
                token.complete(false)
                closeThread.interrupt()
                closeThread.join(5_000)
                writeBehindJobOf(repository).join()
                repository.close()
            }
        }

        @Test
        fun `deadline comparator uses immutable completion and admission event times`() {
            val repository = ActorR2dbcCaffeineRepository(LocalCacheConfig.READ_ONLY)
            val startedAt = 1_000L
            val budget = 100L

            try {
                setPrivateLong(repository, "writeBehindCloseStartedAtNanos", startedAt)
                setPrivateLong(repository, "writeBehindCloseWaitBudgetNanos", budget)

                setWriteBehindAdmissions(repository, inProgress = 0, drainedAtNanos = startedAt + budget - 1L)
                setWriteBehindJobCompletion(repository, cause = null, completedAtNanos = startedAt + budget)
                writeBehindReadinessWasWithinCloseBudget(repository).shouldBeTrue()

                setWriteBehindAdmissions(repository, inProgress = 0, drainedAtNanos = startedAt + budget)
                setWriteBehindJobCompletion(repository, cause = null, completedAtNanos = startedAt + budget + 1L)
                writeBehindReadinessWasWithinCloseBudget(repository).shouldBeFalse()
            } finally {
                repository.close()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close is idempotent after write-behind job started`(testDB: TestDB) = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:close-idempotent",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "close-idempotent-final-flush")

                repository.put(existingId, updated)
                repository.close()
                repository.close()

                findActorById(existingId).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close in write-through mode does not initialize write-behind job`(testDB: TestDB) = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-through:close",
                    writeMode = CacheWriteMode.WRITE_THROUGH,
                )
            )

            withActorTable(testDB) {
                writeBehindJobLazyOf(repository).isInitialized().shouldBeFalse()
                repository.close()

                writeBehindJobLazyOf(repository).isInitialized().shouldBeFalse()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind health report
    // -------------------------------------------------------------------------

    @Nested
    inner class HealthReportTest: AbstractR2dbcCaffeineTest() {

        @Test
        fun `non write-behind repository reports not applicable`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(LocalCacheConfig.READ_ONLY)

            try {
                val report = repository.validateConsistency()
                report.workerState shouldBeEqualTo CacheWorkerState.NOT_APPLICABLE
                report.queueDepth shouldBeEqualTo 0
            } finally {
                repository.close()
            }
        }

        @Test
        fun `failed in-progress publication masks dirty cache but preserves database read-through`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(LocalCacheConfig.READ_ONLY)

            withActorTable(TestDB.H2) {
                val persisted = ActorTable.selectAll().first().toActorRecord()
                val key = persisted.id.toString()
                val dirty = persisted.copy(firstName = "dirty-cache-publication")
                try {
                    repository.cache.put(key, CompletableFuture.completedFuture(dirty))
                    setWriteBehindWorkerState(repository, CacheWorkerState.FAILED)
                    startCachePublication(repository, key)

                    val loaded = repository.get(persisted.id).shouldNotBeNull()
                    loaded.firstName shouldBeEqualTo persisted.firstName
                    loaded.firstName.equals(dirty.firstName).shouldBeFalse()
                } finally {
                    completeCachePublication(repository, key)
                    repository.close()
                }
            }
        }

        @Test
        @Timeout(10, unit = TimeUnit.SECONDS)
        fun `accepted put throws terminal failure when worker fails during cache publication`() = runSuspendIO {
            val cachePutEntered = CountDownLatch(1)
            val releaseCachePut = CountDownLatch(1)
            val backing = Caffeine.newBuilder().buildAsync<String, ActorRecord>()
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:accepted-terminal",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )
            replaceCache(
                repository,
                BlockingPutAsyncCache(backing, cachePutEntered, releaseCachePut),
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                    .copy(firstName = "accepted-terminal")
                try {
                    coroutineScope {
                        val putFailure = async {
                            try {
                                repository.put(actor.id, actor)
                                null
                            } catch (cause: Throwable) {
                                cause
                            }
                        }
                        cachePutEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        writeBehindJobOf(repository).cancelAndJoin()
                        repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                        releaseCachePut.countDown()

                        val failure = putFailure.await().shouldNotBeNull()
                        (failure is IllegalStateException).shouldBeTrue()
                        failure.message.orEmpty().contains("terminal").shouldBeTrue()
                        repository.cache.synchronous().getIfPresent(actor.id.toString()).shouldBeNull()
                    }
                } finally {
                    releaseCachePut.countDown()
                    repository.close()
                }
            }
        }

        @Test
        @Timeout(10, unit = TimeUnit.SECONDS)
        fun `consumer waits for commit token and delivered cancellation rolls reservation back`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:token-handshake",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )

            withActorTable(TestDB.H2) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }.toList()
                val committed = actors[0].copy(firstName = "token-committed")
                val cancelled = actors[1].copy(firstName = "token-cancelled")
                val committedToken = CompletableDeferred<Boolean>()
                val cancelledToken = CompletableDeferred<Boolean>()
                val committedKey = committed.id.toString()
                val cancelledKey = cancelled.id.toString()

                try {
                    startWriteBehindJob(repository)
                    reserveAdmission(repository, committedKey)
                    writeBehindQueueOf(repository).send(writeBehindEntry(committed.id, committed, committedToken))

                    reserveAdmission(repository, cancelledKey)
                    writeBehindQueueOf(repository).send(writeBehindEntry(cancelled.id, cancelled, cancelledToken))
                    repository.validateConsistency().queueDepth shouldBeEqualTo 2

                    cancelledToken.complete(false).shouldBeTrue()
                    rollbackAdmission(repository)
                    completeCachePublication(repository, cancelledKey)
                    repository.validateConsistency().queueDepth shouldBeEqualTo 1

                    committedToken.complete(true).shouldBeTrue()
                    completeAdmission(repository)
                    completeCachePublication(repository, committedKey)
                    awaitHealthReport(repository) { it.queueDepth == 0 }.queueDepth shouldBeEqualTo 0
                } finally {
                    committedToken.complete(false)
                    cancelledToken.complete(false)
                    repository.close()
                }

                val terminal = repository.validateConsistency()
                terminal.queueDepth shouldBeEqualTo 0
                terminal.workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `validateConsistency - write-behind idle repository does not start flush job`(
            testDB: TestDB,
        ) = runSuspendIO {
            val repository = CredentialR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:health-idle",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withCredentialTable(testDB) {
                try {
                    val report = repository.validateConsistency()

                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 0
                    report.workerState shouldBeEqualTo CacheWorkerState.IDLE
                    report.lastFlushError.shouldBeNull()
                } finally {
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `validateConsistency - in-flight write-behind batch reports queue depth`(
            testDB: TestDB,
        ) = runSuspendIO {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = CloseWaitingActorRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:health-in-flight",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "health-in-flight")

                try {
                    repository.put(existingId, updated)
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val report = repository.validateConsistency()
                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 1
                    report.workerState shouldBeEqualTo CacheWorkerState.RUNNING
                    report.lastFlushError.shouldBeNull()
                } finally {
                    releaseFlush.countDown()
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `validateConsistency - write-behind flush failure is reported`(
            testDB: TestDB,
        ) = runSuspendIO {
            val flushFailed = CountDownLatch(1)
            val repository = FailingFlushR2dbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:health-failure",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushFailed = flushFailed,
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "health-failure")

                try {
                    repository.put(existingId, updated)
                    flushFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val report = awaitHealthReport(repository) { health ->
                        health.queueDepth == 1 && health.lastFlushError != null
                    }
                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 1
                    report.lastFlushError.shouldNotBeNull()
                } finally {
                    repository.close()
                }
                val terminalReport = repository.validateConsistency()
                terminalReport.workerState shouldBeEqualTo CacheWorkerState.FAILED
                terminalReport.queueDepth shouldBeEqualTo 1
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind retries retained batch after transient flush failure`(
            testDB: TestDB,
        ) = runSuspendIO {
            val flushFailed = CountDownLatch(1)
            val flushSucceeded = CountDownLatch(1)
            val repository = TransientFailingFlushR2dbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:transient-failure",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushFailed = flushFailed,
                flushSucceeded = flushSucceeded,
            )

            withActorTable(testDB) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }.toList()
                val first = actors[0].copy(firstName = "transient-first")
                val second = actors[1].copy(firstName = "transient-second")

                try {
                    repository.put(first.id, first)
                    flushFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val failedReport = awaitHealthReport(repository) { health ->
                        health.queueDepth == 1 && health.lastFlushError != null
                    }
                    failedReport.queueDepth shouldBeEqualTo 1
                    failedReport.lastFlushError.shouldNotBeNull()

                    repository.put(second.id, second)
                    flushSucceeded.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val recoveredReport = awaitHealthReport(repository) { health ->
                        health.queueDepth == 0 && health.lastFlushError == null
                    }
                    recoveredReport.queueDepth shouldBeEqualTo 0
                    recoveredReport.lastFlushError.shouldBeNull()
                    commit()
                    findActorById(first.id).shouldNotBeNull().firstName shouldBeEqualTo first.firstName
                    findActorById(second.id).shouldNotBeNull().firstName shouldBeEqualTo second.firstName
                } finally {
                    repository.close()
                }
            }
        }

        @Test
        fun `write-behind permanent flush failure exhausts retries and retains the batch`() = runSuspendIO {
            val attempts = AtomicInteger()
            val repository = PermanentlyFailingFlushR2dbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:permanent-failure",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 4,
                ),
                attempts = attempts,
            )

            withActorTable(TestDB.H2) {
                val existing = ActorTable.selectAll().first().toActorRecord()
                try {
                    repository.put(existing.id, existing.copy(firstName = "permanent-failure"))
                    val terminal = awaitHealthReport(repository) { health ->
                        health.workerState == CacheWorkerState.FAILED && health.lastFlushError != null
                    }
                    terminal.workerState shouldBeEqualTo CacheWorkerState.FAILED
                    terminal.queueDepth shouldBeEqualTo 1
                    attempts.get() shouldBeEqualTo 8
                    assertFailsWith<IllegalStateException> {
                        repository.put(existing.id, existing.copy(firstName = "rejected-after-failure"))
                    }
                } finally {
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind full admission rejects without publishing dirty cache`(
            testDB: TestDB,
        ) = runSuspendIO {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = CloseWaitingActorRepository(
                config = LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:cancel-full-send",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    // One item is in-flight; a second item fills the bounded admission
                    // budget so the third sender exercises cancellation while blocked.
                    writeBehindQueueCapacity = 2,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(testDB) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }.toList()
                val blocked = actors[0].copy(firstName = "blocked-flush")
                val queued = actors[1].copy(firstName = "queued-write")
                val cancelled = actors[2].copy(firstName = "cancelled-write")

                try {
                    repository.put(blocked.id, blocked)
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    repository.put(queued.id, queued)
                    val failure = assertFailsWith<IllegalStateException> {
                        repository.put(cancelled.id, cancelled)
                    }
                    failure.message.orEmpty().contains("queue is full").shouldBeTrue()
                    repository.cache.synchronous().getIfPresent(cancelled.id.toString()).shouldBeNull()
                    repository.validateConsistency().queueDepth shouldBeEqualTo 2
                } finally {
                    releaseFlush.countDown()
                    repository.close()
                }
                repository.validateConsistency().queueDepth shouldBeEqualTo 0
            }
        }

        @Test
        fun `terminal worker cancellation rejects later writes but preserves read-through`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:terminal-worker",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                try {
                    writeBehindJobOf(repository).cancelAndJoin()
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.FAILED }

                    repository.get(actor.id).shouldNotBeNull().id shouldBeEqualTo actor.id
                    val failure = assertFailsWith<IllegalStateException> {
                        repository.put(actor.id, actor.copy(firstName = "rejected-terminal"))
                    }
                    failure.message.orEmpty().contains("terminal").shouldBeTrue()
                    repository.validateConsistency().queueDepth shouldBeEqualTo 0
                } finally {
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind closed queue does not publish dirty cache`(
            testDB: TestDB,
        ) = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:closed-queue",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withActorTable(testDB) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                    .copy(firstName = "closed-queue")

                repository.close()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED

                assertFailsWith<IllegalStateException> {
                    repository.put(actor.id, actor)
                }
                repository.cache.synchronous().getIfPresent(actor.id.toString()).shouldBeNull()
            }
        }

        @Test
        @Timeout(15, unit = TimeUnit.SECONDS)
        fun `fast consumer admission never underflows and drains exactly to zero`() = runSuspendIO {
            val repository = ActorR2dbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "r2dbc:caffeine:write-behind:fast-consumer-depth",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )

            withActorTable(TestDB.H2) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                try {
                    repeat(30) { index ->
                        repository.put(actor.id, actor.copy(firstName = "fast-$index"))
                        val drained = awaitHealthReport(repository) { it.queueDepth == 0 }
                        (drained.queueDepth >= 0).shouldBeTrue()
                    }
                } finally {
                    repository.close()
                }
                val terminal = repository.validateConsistency()
                terminal.queueDepth shouldBeEqualTo 0
                terminal.workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }
    }

    private class CancellingActorRepository(
        config: LocalCacheConfig,
        private val jobProvider: () -> Job,
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        val updateAttempts = AtomicInteger()

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            if (updateAttempts.incrementAndGet() == 1) {
                val cancellation = CancellationException("cancel write-behind job during flush")
                jobProvider().cancel(cancellation)
                throw cancellation
            }

            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id
    }

    private class CloseWaitingActorRepository(
        config: LocalCacheConfig,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch,
        private val closeWaitDuration: Duration = Duration.ofSeconds(30),
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        override val writeBehindCloseWaitDuration: Duration = closeWaitDuration

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            flushStarted.countDown()
            releaseFlush.await(5, TimeUnit.SECONDS).shouldBeTrue()
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id
    }

    private class FailingFlushR2dbcRepository(
        config: LocalCacheConfig,
        private val flushFailed: CountDownLatch,
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            flushFailed.countDown()
            throw IllegalStateException("planned write-behind flush failure")
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id
    }

    private class TransientFailingFlushR2dbcRepository(
        config: LocalCacheConfig,
        private val flushFailed: CountDownLatch,
        private val flushSucceeded: CountDownLatch,
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        private val updateAttempts = AtomicInteger()

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            if (updateAttempts.incrementAndGet() == 1) {
                flushFailed.countDown()
                throw IllegalStateException("planned transient write-behind flush failure")
            }

            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
            flushSucceeded.countDown()
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id
    }

    private class PermanentlyFailingFlushR2dbcRepository(
        config: LocalCacheConfig,
        private val attempts: AtomicInteger,
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            attempts.incrementAndGet()
            throw IllegalStateException("planned permanent write-behind flush failure")
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id
    }

    private class BlockingPutAsyncCache<K: Any, V: Any>(
        private val delegate: AsyncCache<K, V>,
        private val cachePutEntered: CountDownLatch,
        private val releaseCachePut: CountDownLatch,
    ): AsyncCache<K, V> by delegate {

        override fun put(key: K, valueFuture: CompletableFuture<out V>) {
            cachePutEntered.countDown()
            releaseCachePut.await(5, TimeUnit.SECONDS).shouldBeTrue()
            delegate.put(key, valueFuture)
        }
    }

    private suspend fun awaitHealthReport(
        repository: R2dbcCaffeineRepository<*, *>,
        predicate: (CacheHealthReport) -> Boolean,
    ): CacheHealthReport {
        repeat(600) {
            val report = repository.validateConsistency()
            if (predicate(report)) {
                return report
            }
            delay(10)
        }
        return repository.validateConsistency()
    }

    private suspend fun awaitThreadState(thread: Thread, expected: Thread.State): Boolean {
        repeat(100) {
            if (thread.state == expected) return true
            delay(10)
        }
        return thread.state == expected
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindJobOf(repository: AbstractR2dbcCaffeineRepository<*, *>): Job {
        return writeBehindJobLazyOf(repository).value
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindJobLazyOf(repository: AbstractR2dbcCaffeineRepository<*, *>): Lazy<Job> {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindJob\$delegate")
        field.isAccessible = true

        return field.get(repository) as Lazy<Job>
    }

    private fun startWriteBehindJob(repository: AbstractR2dbcCaffeineRepository<*, *>): Job =
        invokePrivate(repository, "startWriteBehindJob") as Job

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindQueueOf(repository: AbstractR2dbcCaffeineRepository<*, *>): kotlinx.coroutines.channels.Channel<Any> {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindQueue\$delegate")
        field.isAccessible = true
        return (field.get(repository) as Lazy<kotlinx.coroutines.channels.Channel<Any>>).value
    }

    private fun writeBehindEntry(
        id: Any,
        entity: Any,
        accepted: CompletableDeferred<Boolean>,
    ): Any {
        val entryClass = AbstractR2dbcCaffeineRepository::class.java.declaredClasses
            .single { it.simpleName == "WriteBehindEntry" }
        val constructor = entryClass.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        return constructor.newInstance(id, entity, accepted)
    }

    private fun reserveAdmission(repository: AbstractR2dbcCaffeineRepository<*, *>, key: String) {
        invokePrivate(repository, "reserveWriteBehindAdmission", key)
    }

    private fun rollbackAdmission(repository: AbstractR2dbcCaffeineRepository<*, *>) {
        invokePrivate(repository, "rollbackWriteBehindAdmission")
    }

    private fun completeAdmission(repository: AbstractR2dbcCaffeineRepository<*, *>) {
        invokePrivate(repository, "completeWriteBehindAdmission")
    }

    private fun completeCachePublication(repository: AbstractR2dbcCaffeineRepository<*, *>, key: String) {
        invokePrivate(repository, "markWriteBehindCachePublicationCompleted", key)
    }

    private fun startCachePublication(repository: AbstractR2dbcCaffeineRepository<*, *>, key: String) {
        invokePrivate(repository, "markWriteBehindCachePublicationStarted", key)
    }

    private fun setWriteBehindWorkerState(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        state: CacheWorkerState,
    ) {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindWorkerState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as AtomicReference<CacheWorkerState>).set(state)
    }

    private fun replaceCache(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        replacement: AsyncCache<*, *>,
    ) {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("cache\$delegate")
        field.isAccessible = true
        field.set(repository, lazyOf(replacement))
    }

    private fun writeBehindLifecycleLockOf(repository: AbstractR2dbcCaffeineRepository<*, *>): ReentrantLock {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindLifecycleLock")
        field.isAccessible = true
        return field.get(repository) as ReentrantLock
    }

    private fun setWriteBehindQueueDepth(repository: AbstractR2dbcCaffeineRepository<*, *>, value: Int) {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindQueueDepth")
        field.isAccessible = true
        (field.get(repository) as AtomicInteger).set(value)
    }

    private fun setWriteBehindAdmissions(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        inProgress: Int,
        drainedAtNanos: Long,
    ) {
        val admissionsClass = AbstractR2dbcCaffeineRepository::class.java.declaredClasses
            .single { it.simpleName == "WriteBehindAdmissions" }
        val constructor = admissionsClass.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        val admissions = constructor.newInstance(inProgress, drainedAtNanos)
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindAdmissions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as AtomicReference<Any>).set(admissions)
    }

    private fun setWriteBehindJobCompletion(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        cause: Throwable?,
        completedAtNanos: Long,
    ) {
        val completionClass = AbstractR2dbcCaffeineRepository::class.java.declaredClasses
            .single { it.simpleName == "WriteBehindJobCompletion" }
        val constructor = completionClass.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        val completion = constructor.newInstance(cause, completedAtNanos)
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField("writeBehindJobCompletion")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as AtomicReference<Any>).set(completion)
    }

    private fun setPrivateLong(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        fieldName: String,
        value: Long,
    ) {
        val field = AbstractR2dbcCaffeineRepository::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setLong(repository, value)
    }

    private fun writeBehindReadinessWasWithinCloseBudget(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
    ): Boolean = invokePrivate(repository, "writeBehindReadinessWasWithinCloseBudgetLocked") as Boolean

    private fun invokePrivate(
        repository: AbstractR2dbcCaffeineRepository<*, *>,
        methodName: String,
        vararg arguments: Any,
    ): Any? {
        val method = AbstractR2dbcCaffeineRepository::class.java.declaredMethods
            .single { it.name == methodName && it.parameterCount == arguments.size }
        method.isAccessible = true
        return method.invoke(repository, *arguments)
    }
}
