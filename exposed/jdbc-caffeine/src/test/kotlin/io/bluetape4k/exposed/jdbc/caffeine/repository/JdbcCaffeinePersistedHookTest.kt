package io.bluetape4k.exposed.jdbc.caffeine.repository

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.AbstractJdbcCaffeineTest
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.toActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withActorTable
import io.bluetape4k.exposed.tests.TestDB
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class JdbcCaffeinePersistedHookTest: AbstractJdbcCaffeineTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `write-through invokes hook after database write succeeds`(testDB: TestDB) {
        val repository = RecordingActorRepository(
            LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:hook:wt",
                writeMode = CacheWriteMode.WRITE_THROUGH,
            )
        )

        withActorTable(testDB) {
            val existing = ActorTable.selectAll().first().toActorRecord()
            val updated = existing.copy(firstName = "hook-write-through")

            try {
                repository.put(existing.id, updated)

                ActorSchema.findActorById(existing.id).shouldNotBeNull().firstName shouldBeEqualTo updated.firstName
                repository.persisted.map { it.id } shouldBeEqualTo listOf(existing.id)
            } finally {
                repository.close()
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `write-behind publishes retained batch plus appended writes only after successful retry`(testDB: TestDB) {
        val flushFailed = CountDownLatch(1)
        val flushSucceeded = CountDownLatch(1)
        val repository = TransientFailingHookRepository(
            config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:hook:wb:retry",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 10,
                writeBehindQueueCapacity = 16,
            ),
            flushFailed = flushFailed,
            flushSucceeded = flushSucceeded,
        )

        withActorTable(testDB) {
            val actors = ActorTable.selectAll().map { it.toActorRecord() }
            val first = actors[0].copy(firstName = "hook-retry-first")
            val second = actors[1].copy(firstName = "hook-retry-second")

            try {
                repository.put(first.id, first)
                flushFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()
                repository.persisted.shouldBeEmpty()

                repository.put(second.id, second)
                flushSucceeded.await(5, TimeUnit.SECONDS).shouldBeTrue()

                awaitHealthReport(repository) { it.queueDepth == 0 && it.lastFlushError == null }
                repository.persisted.map { it.id } shouldBeEqualTo listOf(first.id, second.id)
            } finally {
                repository.close()
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `write-behind queue full failure does not invoke hook for rejected write`(testDB: TestDB) {
        val flushStarted = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val repository = BlockingHookRepository(
            config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:hook:wb:full",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 1,
                writeBehindQueueCapacity = 1,
            ),
            flushStarted = flushStarted,
            releaseFlush = releaseFlush,
        )

        withActorTable(testDB) {
            val actors = ActorTable.selectAll().map { it.toActorRecord() }
            val first = actors[0].copy(firstName = "hook-full-first")
            val second = actors[1].copy(firstName = "hook-full-second")
            val rejected = actors[2].copy(firstName = "hook-full-rejected")

            try {
                repository.put(first.id, first)
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                repository.put(second.id, second)

                runCatching { repository.put(rejected.id, rejected) }.isFailure.shouldBeTrue()
                releaseFlush.countDown()
                awaitHealthReport(repository) { it.queueDepth == 0 }

                repository.persisted.map { it.id }.contains(rejected.id) shouldBeEqualTo false
            } finally {
                releaseFlush.countDown()
                repository.close()
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `write-behind hook failure does not retain committed batch or stop worker`(testDB: TestDB) {
        val firstHookAttempted = CountDownLatch(1)
        val secondHookSucceeded = CountDownLatch(1)
        val repository = FailingOnceHookRepository(
            config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:hook:wb:failure",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 1,
                writeBehindQueueCapacity = 16,
            ),
            firstHookAttempted = firstHookAttempted,
            secondHookSucceeded = secondHookSucceeded,
        )

        withActorTable(testDB) {
            val actors = ActorTable.selectAll().map { it.toActorRecord() }
            val first = actors[0].copy(firstName = "hook-failure-first")
            val second = actors[1].copy(firstName = "hook-failure-second")

            try {
                repository.put(first.id, first)
                firstHookAttempted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                awaitHealthReport(repository) { it.queueDepth == 0 && it.lastFlushError == null }

                repository.put(second.id, second)
                secondHookSucceeded.await(5, TimeUnit.SECONDS).shouldBeTrue()

                awaitHealthReport(repository) { it.queueDepth == 0 && it.lastFlushError == null }
                repository.persisted.map { it.id } shouldBeEqualTo listOf(second.id)
            } finally {
                repository.close()
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `write-behind cancellation stops accepting later writes before cache mutation`(testDB: TestDB) {
        val hookCancelled = CountDownLatch(1)
        val repository = CancellingHookRepository(
            config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:hook:wb:cancel",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 1,
                writeBehindQueueCapacity = 16,
            ),
            hookCancelled = hookCancelled,
        )

        withActorTable(testDB) {
            val actors = ActorTable.selectAll().map { it.toActorRecord() }
            val first = actors[0].copy(firstName = "hook-cancel-first")
            val second = actors[1].copy(firstName = "hook-cancel-second")

            try {
                repository.put(first.id, first)
                hookCancelled.await(5, TimeUnit.SECONDS).shouldBeTrue()

                awaitHealthReport(repository) { !it.isFlushJobRunning }
                runCatching { repository.put(second.id, second) }.isFailure.shouldBeTrue()
                repository.cache.getIfPresent(repository.serializeKey(second.id)) shouldBeEqualTo null
            } finally {
                repository.close()
            }
        }
    }

    private open class RecordingActorRepository(
        config: LocalCacheConfig,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

        val persisted = CopyOnWriteArrayList<CachePersistedWrite<Long, ActorRecord>>()

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            applyActorUpdate(entity)
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun extractId(entity: ActorRecord): Long = entity.id

        override fun afterPersisted(id: Long, entity: ActorRecord) {
            persisted += CachePersistedWrite(id, entity)
        }

        override fun afterPersisted(writes: List<CachePersistedWrite<Long, ActorRecord>>) {
            persisted += writes
        }

        protected fun UpdateStatement.applyActorUpdate(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }
    }

    private class BlockingHookRepository(
        config: LocalCacheConfig,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch,
    ): RecordingActorRepository(config) {

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            flushStarted.countDown()
            releaseFlush.await(5, TimeUnit.SECONDS).shouldBeTrue()
            applyActorUpdate(entity)
        }
    }

    private class TransientFailingHookRepository(
        config: LocalCacheConfig,
        private val flushFailed: CountDownLatch,
        private val flushSucceeded: CountDownLatch,
    ): RecordingActorRepository(config) {

        private val attempts = AtomicInteger()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            if (attempts.incrementAndGet() == 1) {
                flushFailed.countDown()
                throw IllegalStateException("planned transient flush failure")
            }
            applyActorUpdate(entity)
            flushSucceeded.countDown()
        }
    }

    private class FailingOnceHookRepository(
        config: LocalCacheConfig,
        private val firstHookAttempted: CountDownLatch,
        private val secondHookSucceeded: CountDownLatch,
    ): RecordingActorRepository(config) {

        private val hookAttempts = AtomicInteger()

        override fun afterPersisted(writes: List<CachePersistedWrite<Long, ActorRecord>>) {
            if (hookAttempts.incrementAndGet() == 1) {
                firstHookAttempted.countDown()
                throw IllegalStateException("planned hook failure")
            }
            persisted += writes
            secondHookSucceeded.countDown()
        }
    }

    private class CancellingHookRepository(
        config: LocalCacheConfig,
        private val hookCancelled: CountDownLatch,
    ): RecordingActorRepository(config) {

        override fun afterPersisted(writes: List<CachePersistedWrite<Long, ActorRecord>>) {
            hookCancelled.countDown()
            throw CancellationException("planned hook cancellation")
        }
    }

    private fun awaitHealthReport(
        repository: JdbcCaffeineRepository<*, *>,
        predicate: (CacheHealthReport) -> Boolean,
    ): CacheHealthReport {
        repeat(100) {
            val report = repository.validateConsistency()
            if (predicate(report)) {
                return report
            }
            Thread.sleep(10)
        }
        return repository.validateConsistency()
    }
}
