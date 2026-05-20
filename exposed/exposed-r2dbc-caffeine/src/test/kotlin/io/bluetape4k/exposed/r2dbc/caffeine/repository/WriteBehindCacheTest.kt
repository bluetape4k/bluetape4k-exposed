package io.bluetape4k.exposed.r2dbc.caffeine.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheMode
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
import kotlinx.coroutines.Job
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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * R2DBC Caffeine Write-Behind 캐시 통합 테스트.
 *
 * - AutoIncrement Long ID 테이블 ([ActorTable]) 과
 * - Client-generated UUID ID 테이블 ([CredentialTable]) 에 대해 각각 검증합니다.
 * 캐시에 먼저 저장하고 DB에는 비동기로 반영되는 패턴을 검증합니다.
 */
class WriteBehindCacheTest {

    companion object: KLoggingChannel()

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

        override suspend fun getNonExistentId(): UUID = UUID.randomUUID()

        override suspend fun createNewEntity(): CredentialRecord =
            ActorSchema.newCredentialRecord()
    }

    // -------------------------------------------------------------------------
    // Cancellation-safe final flush
    // -------------------------------------------------------------------------

    @Nested
    inner class CancellationSafeFinalFlush: AbstractR2dbcCaffeineTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind final batch is flushed after job cancellation`(testDB: TestDB) = runSuspendIO {
            val config = LocalCacheConfig(
                keyPrefix = "r2dbc:caffeine:write-behind:cancel-final-flush",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 10,
                writeBehindQueueCapacity = 16,
            )
            lateinit var repository: CancellingActorRepository
            repository = CancellingActorRepository(config) {
                writeBehindJobOf(repository)
            }

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "cancel-safe-final-flush")

                try {
                    repository.put(existingId, updated)
                    writeBehindJobOf(repository).join()

                    repository.updateAttempts.get() shouldBeEqualTo 2
                    findActorById(existingId).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
                } finally {
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
                    flushStarted.await(5, TimeUnit.SECONDS) shouldBeEqualTo true
                    closeFuture.isDone shouldBeEqualTo false

                    releaseFlush.countDown()
                    closeFuture.get(5, TimeUnit.SECONDS)

                    findActorById(existingId).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
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

                writeBehindJobOf(repository).isCompleted shouldBeEqualTo true
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
                writeBehindJobOf(repository).isCompleted shouldBeEqualTo true
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
                writeBehindJobLazyOf(repository).isInitialized() shouldBeEqualTo false

                repository.close()

                writeBehindJobLazyOf(repository).isInitialized() shouldBeEqualTo false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind health report
    // -------------------------------------------------------------------------

    @Nested
    inner class HealthReportTest: AbstractR2dbcCaffeineTest() {

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
                    report.isFlushJobRunning shouldBeEqualTo false
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
                    flushStarted.await(5, TimeUnit.SECONDS) shouldBeEqualTo true

                    val report = repository.validateConsistency()
                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 1
                    report.isFlushJobRunning shouldBeEqualTo true
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
                    flushFailed.await(5, TimeUnit.SECONDS) shouldBeEqualTo true

                    val report = awaitHealthReport(repository) { health ->
                        health.queueDepth == 0 && health.lastFlushError != null
                    }
                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 0
                    report.lastFlushError.shouldNotBeNull()
                } finally {
                    repository.close()
                }
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
    ): AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

        override val table: IdTable<Long> = ActorTable

        override suspend fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            flushStarted.countDown()
            releaseFlush.await(5, TimeUnit.SECONDS) shouldBeEqualTo true

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

    private suspend fun awaitHealthReport(
        repository: R2dbcCaffeineRepository<*, *>,
        predicate: (CacheHealthReport) -> Boolean,
    ): CacheHealthReport {
        repeat(100) {
            val report = repository.validateConsistency()
            if (predicate(report)) {
                return report
            }
            delay(10)
        }
        return repository.validateConsistency()
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
}
