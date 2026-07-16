package io.bluetape4k.exposed.jdbc.caffeine.repository

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.AbstractJdbcCaffeineTest
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorJdbcCaffeineRepository
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.CredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.toActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.toCredentialRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withCredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.CredentialJdbcCaffeineRepository
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.autoIncColumnType
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JDBC Caffeine 레포지토리 추가 커버리지 테스트.
 *
 * 기존 시나리오 인터페이스에서 검증하지 않는 경로를 직접 테스트합니다:
 * - [AbstractJdbcCaffeineRepository.countFromDb]
 * - [AbstractJdbcCaffeineRepository.clear] 후 재조회
 * - [AbstractJdbcCaffeineRepository.invalidateAll] (Collection 오버로드)
 * - Write-Behind [AbstractJdbcCaffeineRepository.close] 시 남은 큐 flush 보장
 */
class JdbcCaffeineRepositoryExtraTest {

    companion object: KLogging()

    @Test
    fun `get - MultithreadingTester cache misses run one loader per key`() {
        val repository = CountingActorRepository("jdbc:caffeine:atomic:get")
        val results = Collections.synchronizedList(mutableListOf<ActorRecord?>())

        MultithreadingTester()
            .workers(8)
            .rounds(1)
            .addAll(
                List(8) {
                    {
                        results += repository.get(1L)
                    }
                }
            )
            .run()

        results.toSet() shouldHaveSize 1
        repository.singleLoadCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `getAll - MultithreadingTester cache misses run one loader per key`() {
        val repository = CountingActorRepository("jdbc:caffeine:atomic:get-all")
        val ids = listOf(1L, 2L, 3L)
        val results = Collections.synchronizedList(mutableListOf<Map<Long, ActorRecord>>())

        MultithreadingTester()
            .workers(8)
            .rounds(1)
            .addAll(
                List(8) {
                    {
                        results += repository.getAll(ids)
                    }
                }
            )
            .run()

        results.forEach { result ->
            result.keys shouldBeEqualTo ids.toSet()
        }
        repository.singleLoadCount.get() shouldBeEqualTo ids.size
        repository.bulkLoadCount.get() shouldBeEqualTo 0
    }

    private class CountingActorRepository(
        keyPrefix: String,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(
        LocalCacheConfig(keyPrefix = keyPrefix, writeMode = CacheWriteMode.READ_ONLY)
    ) {
        val singleLoadCount = AtomicInteger()
        val bulkLoadCount = AtomicInteger()

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord =
            error("DB row conversion is not used by this in-memory concurrency test")

        override fun UpdateStatement.updateEntity(entity: ActorRecord) = Unit

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) = Unit

        override fun extractId(entity: ActorRecord): Long = entity.id

        override fun findByIdFromDb(id: Long): ActorRecord? {
            singleLoadCount.incrementAndGet()
            Thread.sleep(100)
            return ActorSchema.newActorRecord().withId(id)
        }

        override fun findAllFromDb(ids: Collection<Long>): List<ActorRecord> {
            bulkLoadCount.incrementAndGet()
            Thread.sleep(100)
            return ids.map { ActorSchema.newActorRecord().withId(it) }
        }
    }

    // -------------------------------------------------------------------------
    // countFromDb + clear + invalidateAll — AutoInc Actor
    // -------------------------------------------------------------------------

    @Nested
    inner class ActorCacheManagementTest: AbstractJdbcCaffeineTest() {

        // 파라미터화 테스트 실행마다 새 레포지토리를 생성해서 DB 인스턴스 간 캐시 오염을 방지한다
        private fun newRepository() = ActorJdbcCaffeineRepository(
            LocalCacheConfig(keyPrefix = "jdbc:caffeine:extra:actor", writeMode = CacheWriteMode.READ_ONLY)
        )

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `countFromDb - DB 직접 카운트를 반환한다`(testDB: TestDB) {
            val repository = newRepository()
            withActorTable(testDB) {
                // countFromDb()는 캐시를 우회해 DB에서 직접 집계해야 한다
                val count = repository.countFromDb()
                count shouldBeEqualTo ActorTable.selectAll().count()
                count shouldBeGreaterThan 0L
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `clear - 캐시를 비우면 다음 get은 DB에서 다시 로드된다`(testDB: TestDB) {
            // DB 인스턴스마다 독립된 레포지토리를 사용해야 캐시 오염을 방지할 수 있다
            val repository = newRepository()
            withActorTable(testDB) {
                val id = transaction {
                    ActorTable.select(ActorTable.id).limit(1).first()[ActorTable.id].value
                }

                // 캐시에 적재
                val first = repository.get(id)
                first.shouldNotBeNull()

                // 캐시 전체 비우기
                repository.clear()

                // 내부 Caffeine 캐시에서 직접 조회하면 없어야 한다
                repository.cache.getIfPresent(id.toString()).shouldBeNull()

                // get()으로 재조회하면 Read-Through로 다시 로드된다
                val reloaded = repository.get(id)
                reloaded.shouldNotBeNull()
                // clear 후 재로드하면 동일한 DB 행이므로 같은 값이어야 한다
                reloaded shouldBeEqualTo first
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `invalidateAll - 여러 ID를 한 번에 캐시에서 제거한다`(testDB: TestDB) {
            val repository = newRepository()
            withActorTable(testDB) {
                val ids = transaction {
                    ActorTable.select(ActorTable.id).map { it[ActorTable.id].value }
                }

                // 모두 캐시에 로드
                ids.forEach { repository.get(it) }

                // invalidateAll로 일괄 제거
                repository.invalidateAll(ids)

                // 캐시에서 모두 사라졌는지 확인
                ids.forEach { id ->
                    repository.cache.getIfPresent(id.toString()).shouldBeNull()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // findAll cache warming failures
    // -------------------------------------------------------------------------

    @Nested
    inner class CacheWarmingFailureTest: AbstractJdbcCaffeineTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming Exception is skipped without losing query result`(testDB: TestDB) {
            val repository = FailingCacheWarmJdbcRepository(
                failure = IllegalStateException("cache key failure")
            )

            withActorTable(testDB) {
                RecordingLogbackAppender().use { appender ->
                    val entities = repository.findAll()

                    entities shouldHaveSize ActorTable.selectAll().count().toInt()
                    repository.cache.asMap().shouldBeEmpty()
                    appender.hasWarnContaining(
                        "Cache warming failed for entity - skipping. " +
                            "cacheName=jdbc:caffeine:extra:find-all-failure"
                    ).shouldBeTrue()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache key serialization Exception is warned and skipped`(testDB: TestDB) {
            val repository = FailingCacheWarmJdbcRepository(
                failure = IllegalStateException("cache key serialization failure"),
                failInSerializeKey = true,
            )

            withActorTable(testDB) {
                RecordingLogbackAppender().use { appender ->
                    val entities = repository.findAll()

                    entities shouldHaveSize ActorTable.selectAll().count().toInt()
                    repository.cache.asMap().shouldBeEmpty()
                    appender.hasWarnContaining(
                        "Cache warming failed for entity - skipping. " +
                            "cacheName=jdbc:caffeine:extra:find-all-failure"
                    ).shouldBeTrue()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming CancellationException is not swallowed`(testDB: TestDB) {
            val repository = FailingCacheWarmJdbcRepository(
                failure = CancellationException("cache warming cancelled")
            )

            withActorTable(testDB) {
                val error = assertFailsWith<CancellationException> {
                    repository.findAll()
                }

                error.message shouldBeEqualTo "cache warming cancelled"
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming Error is not swallowed`(testDB: TestDB) {
            val repository = FailingCacheWarmJdbcRepository(
                failure = AssertionError("fatal cache warming failure")
            )

            withActorTable(testDB) {
                val error = assertFailsWith<AssertionError> {
                    repository.findAll()
                }

                error.message shouldBeEqualTo "fatal cache warming failure"
            }
        }
    }

    // -------------------------------------------------------------------------
    // countFromDb + clear + invalidateAll — Client-gen UUID Credential
    // -------------------------------------------------------------------------

    @Nested
    inner class CredentialCacheManagementTest: AbstractJdbcCaffeineTest() {

        // 파라미터화 테스트 실행마다 새 레포지토리를 생성해서 DB 인스턴스 간 캐시 오염을 방지한다
        private fun newRepository() = CredentialJdbcCaffeineRepository(
            LocalCacheConfig(keyPrefix = "jdbc:caffeine:extra:credential", writeMode = CacheWriteMode.READ_ONLY)
        )

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `countFromDb - UUID 테이블 DB 직접 카운트를 반환한다`(testDB: TestDB) {
            val repository = newRepository()
            withCredentialTable(testDB) {
                val count = repository.countFromDb()
                count shouldBeEqualTo CredentialTable.selectAll().count()
                count shouldBeGreaterThan 0L
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `clear - UUID 테이블 캐시를 비우면 다음 get은 DB에서 다시 로드된다`(testDB: TestDB) {
            val repository = newRepository()
            withCredentialTable(testDB) {
                val id = transaction {
                    CredentialTable.select(CredentialTable.id).limit(1).first()[CredentialTable.id].value
                }

                val first = repository.get(id)
                first.shouldNotBeNull()

                repository.clear()
                repository.cache.getIfPresent(id.toString()).shouldBeNull()

                val reloaded = repository.get(id)
                reloaded.shouldNotBeNull()
                reloaded shouldBeEqualTo first
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `invalidateAll - UUID 테이블 여러 ID를 한 번에 캐시에서 제거한다`(testDB: TestDB) {
            val repository = newRepository()
            withCredentialTable(testDB) {
                val ids = transaction {
                    CredentialTable.select(CredentialTable.id).map { it[CredentialTable.id].value }
                }

                ids.forEach { repository.get(it) }

                repository.invalidateAll(ids)

                ids.forEach { id ->
                    repository.cache.getIfPresent(id.toString()).shouldBeNull()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind close() flush 보장 테스트 — Client-gen UUID (non-AutoInc)
    // -------------------------------------------------------------------------

    @Nested
    inner class WriteBehindCloseFlushTest: AbstractJdbcCaffeineTest() {

        @Test
        fun `close - production wait default remains thirty seconds`() {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-default",
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
        fun `close - Write-Behind 종료 시 큐에 남은 항목이 DB에 flush된다`(testDB: TestDB) {
            // AutoInc 테이블은 새 엔티티를 DB에 삽입하지 않으므로 UUID 테이블로 검증
            val config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:extra:wb-close",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 100,
                writeBehindQueueCapacity = 10_000,
            )
            val repository = CredentialJdbcCaffeineRepository(config)

            // autoInc면 건너뜀 (UUID 테이블이므로 항상 통과)
            Assumptions.assumeTrue(repository.table.id.autoIncColumnType == null) {
                "AutoInc 테이블은 Write-Behind 신규 삽입을 지원하지 않아 건너뜁니다"
            }

            val newEntities = List(10) { ActorSchema.newCredentialRecord() }
            val newMap = newEntities.associateBy { it.id }
            var prevCount: Long = 0

            // dropTables=false: statement 완료 후 테이블 유지 → statement 바깥에서 count 조회 가능
            // 트랜잭션 격리(REPEATABLE READ) 문제를 피하기 위해 count 조회를 statement 바깥(독립 트랜잭션)에서 수행한다.
            withTables(testDB, CredentialTable, dropTables = false) {
                repeat(3) {
                    CredentialTable.insert {
                        it[loginId] = faker.internet().domainWord() + "_wb_close_$it"
                        it[email] = "test_$it@example.com"
                        it[lastLoginAt] = Instant.now().minusSeconds(3600)
                    }
                }
                commit()

                prevCount = CredentialTable.selectAll().count()

                // 새 엔티티 10개를 Write-Behind 큐에 넣음
                repository.putAll(newMap)

                // close() 호출 → 큐 드레인 후 종료 (flushBatch가 별도 transaction으로 커밋)
                repository.close()
            }

            // DB에 모두 반영됐는지 독립 트랜잭션으로 확인 (격리 수준 문제 우회)
            val newCount = transaction { CredentialTable.selectAll().count() }
            newCount shouldBeEqualTo prevCount + newEntities.size
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        @Timeout(35, unit = TimeUnit.SECONDS)
        fun `close - write-behind put 전에도 hang 없이 종료한다`(testDB: TestDB) {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-before-put",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withCredentialTable(testDB) {
                repository.close()

                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        @Timeout(35, unit = TimeUnit.SECONDS)
        fun `close - write-behind job 시작 후 반복 호출해도 hang 없이 종료한다`(testDB: TestDB) {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-idempotent",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withCredentialTable(testDB) {
                val entity = ActorSchema.newCredentialRecord()

                repository.put(entity.id, entity)
                repository.close()
                repository.close()

                CredentialTable.selectAll()
                    .where { CredentialTable.id eq entity.id }
                    .count() shouldBeEqualTo 1L
                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close - blocked write-behind drain exposes draining then stopped`(testDB: TestDB) {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val closeCompleted = CountDownLatch(1)
            val repository = BlockingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-draining",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(testDB) {
                val existing = ActorTable.selectAll().first().toActorRecord()
                val updated = existing.copy(firstName = "close-draining")
                repository.put(existing.id, updated)
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

                val closeThread = Thread {
                    try {
                        repository.close()
                    } finally {
                        closeCompleted.countDown()
                    }
                }.apply { start() }

                try {
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                        .workerState shouldBeEqualTo CacheWorkerState.DRAINING
                } finally {
                    releaseFlush.countDown()
                    closeCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    closeThread.join()
                }

                closeThread.isAlive.shouldBeFalse()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `close - blocked write-behind drain times out as failed and late completion stays failed`() {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val closeCompleted = CountDownLatch(1)
            val repository = BlockingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-timeout",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
                writeBehindCloseWaitDuration = Duration.ofMillis(50),
            )

            withActorTable(TestDB.H2_MYSQL) {
                val existing = ActorTable.selectAll().first().toActorRecord()
                repository.put(existing.id, existing.copy(firstName = "close-timeout"))
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val workerCompleted = CountDownLatch(1)
                writeBehindJobOf(repository).invokeOnCompletion { workerCompleted.countDown() }

                val closeThread = Thread {
                    try {
                        repository.close()
                    } finally {
                        closeCompleted.countDown()
                    }
                }.apply { start() }

                try {
                    closeCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                } finally {
                    releaseFlush.countDown()
                    closeThread.interrupt()
                    closeThread.join(5_000)
                }

                closeThread.isAlive.shouldBeFalse()
                workerCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
            }
        }

        @Test
        fun `close - deadline outcome uses readiness event time rather than later lock acquisition`() {
            val repository = ActorJdbcCaffeineRepository(LocalCacheConfig.READ_ONLY)
            val startedAt = 1_000L
            val budget = 100L

            try {
                setPrivateLong(repository, "writeBehindCloseStartedAtNanos", startedAt)
                setPrivateLong(repository, "writeBehindCloseWaitBudgetNanos", budget)

                setWriteBehindAdmissions(repository, drainedAtNanos = startedAt + budget)
                setWriteBehindJobCompletionAt(repository, startedAt + budget + 1L)
                writeBehindReadinessWasWithinCloseBudget(repository).shouldBeFalse()

                setWriteBehindAdmissions(repository, drainedAtNanos = startedAt + budget - 1L)
                setWriteBehindJobCompletionAt(repository, startedAt + budget)
                writeBehindReadinessWasWithinCloseBudget(repository).shouldBeTrue()
            } finally {
                repository.close()
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `close - interrupt publishes failed restores flag and late completion stays failed`() {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val closeCompleted = CountDownLatch(1)
            val interruptedAfterClose = AtomicBoolean(false)
            val repository = BlockingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-interrupted",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(TestDB.H2_MYSQL) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }
                val blocked = actors[0].copy(firstName = "close-interrupted")
                val rejected = actors[1].copy(firstName = "close-interrupted-rejected")
                repository.put(blocked.id, blocked)
                flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val workerCompleted = CountDownLatch(1)
                writeBehindJobOf(repository).invokeOnCompletion { workerCompleted.countDown() }

                val closeThread = Thread {
                    try {
                        repository.close()
                        interruptedAfterClose.set(Thread.currentThread().isInterrupted)
                    } finally {
                        closeCompleted.countDown()
                    }
                }.apply { start() }

                try {
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                        .workerState shouldBeEqualTo CacheWorkerState.DRAINING
                    closeThread.interrupt()
                    closeCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()

                    interruptedAfterClose.get().shouldBeTrue()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                    val failure = assertFailsWith<IllegalStateException> {
                        repository.put(rejected.id, rejected)
                    }
                    failure.message.orEmpty().contains("INTERRUPTED").shouldBeTrue()
                    repository.cache.getIfPresent(repository.serializeKey(rejected.id)).shouldBeNull()

                    val repeatedCloseCompleted = CountDownLatch(1)
                    val repeatedCloseThread = Thread {
                        repository.close()
                        repeatedCloseCompleted.countDown()
                    }.apply { start() }
                    repeatedCloseCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    repeatedCloseThread.join(5_000)
                    repeatedCloseThread.isAlive.shouldBeFalse()
                } finally {
                    releaseFlush.countDown()
                    closeThread.interrupt()
                    closeThread.join(5_000)
                }

                workerCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
            }
        }

        @Test
        @Timeout(5, unit = TimeUnit.SECONDS)
        fun `close - interrupted follower preserves flag without overriding owner completion`() {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val ownerCompleted = CountDownLatch(1)
            val followerCompleted = CountDownLatch(1)
            val followerInterrupted = AtomicBoolean(false)
            val repository = BlockingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-follower",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(TestDB.H2_MYSQL) {
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
                    awaitThreadWaiting(follower).shouldBeTrue()
                    follower.interrupt()
                    Thread.sleep(50)
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
        fun `close - accepted put blocked before cache mutation cannot repopulate after timeout`() {
            val cachePutEntered = CountDownLatch(1)
            val releaseCachePut = Semaphore(0)
            val cacheValuePublished = Semaphore(0)
            val releaseCachePutAfterPublish = Semaphore(0)
            val cachePutCompleted = Semaphore(0)
            val putFailure = AtomicReference<Throwable?>()
            val readValue = AtomicReference<ActorRecord?>()
            val readCompleted = CountDownLatch(1)
            val repository = BlockingCachePutJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-close-admission-timeout",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                cachePutEntered = cachePutEntered,
                releaseCachePut = releaseCachePut,
                cacheValuePublished = cacheValuePublished,
                releaseCachePutAfterPublish = releaseCachePutAfterPublish,
                cachePutCompleted = cachePutCompleted,
                writeBehindCloseWaitDuration = Duration.ofMillis(50),
            )

            withActorTable(TestDB.H2_MYSQL) {
                val existing = ActorTable.selectAll().first().toActorRecord()
                val updated = existing.copy(firstName = "close-admission-timeout")
                commit()

                val putThread = Thread {
                    try {
                        repository.put(updated.id, updated)
                    } catch (cause: Throwable) {
                        putFailure.set(cause)
                    }
                }.apply { start() }
                var readThread: Thread? = null

                try {
                    cachePutEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val workerCompleted = CountDownLatch(1)
                    writeBehindJobOf(repository).invokeOnCompletion { workerCompleted.countDown() }
                    repository.close()
                    val reportAfterClose = repository.validateConsistency()
                    reportAfterClose.workerState shouldBeEqualTo CacheWorkerState.FAILED

                    releaseCachePut.release()
                    cacheValuePublished.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()

                    readThread = Thread {
                        try {
                            readValue.set(repository.get(updated.id))
                        } finally {
                            readCompleted.countDown()
                        }
                    }.apply { start() }

                    readCompleted.await(1, TimeUnit.SECONDS).shouldBeTrue()
                    readValue.get().shouldBeNull()

                    releaseCachePutAfterPublish.release()
                    cachePutCompleted.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()
                    putThread.join(5_000)
                    readCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    readThread.join(5_000)

                    putThread.isAlive.shouldBeFalse()
                    readThread.isAlive.shouldBeFalse()
                    workerCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val failure = putFailure.get().shouldNotBeNull()
                    (failure is IllegalStateException).shouldBeTrue()
                    failure.message.orEmpty().contains("TIMEOUT").shouldBeTrue()
                    repository.cache.getIfPresent(repository.serializeKey(updated.id)).shouldBeNull()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.FAILED
                } finally {
                    releaseCachePut.release()
                    releaseCachePutAfterPublish.release()
                    putThread.join(5_000)
                    readThread?.join(5_000)
                    repository.close()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind queue overflow → IllegalStateException (not silent data loss)
    // -------------------------------------------------------------------------

    @Nested
    inner class WriteBehindOverflowTest: AbstractJdbcCaffeineTest() {

        @Test
        fun `put - closed write-behind rejects as terminal without queue-full classification`() {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-closed",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                )
            )
            val rejected = ActorSchema.newCredentialRecord()

            repository.close()

            val failure = assertFailsWith<IllegalStateException> {
                repository.put(rejected.id, rejected)
            }
            failure.message.orEmpty().contains("STOPPED").shouldBeTrue()
            failure.message.orEmpty().contains("queue is full").shouldBeFalse()
            repository.validateConsistency().queueDepth shouldBeEqualTo 0
            repository.cache.getIfPresent(repository.serializeKey(rejected.id)).shouldBeNull()
        }

        @Test
        @Timeout(15, unit = TimeUnit.SECONDS)
        fun `put versus close gate tracks multiple accepted admissions and rejects later writes`() {
            withCredentialTable(TestDB.H2_MYSQL) {
                commit()

                val admissionCount = 3
                val cachePutsEntered = CountDownLatch(admissionCount)
                val releaseCachePuts = Semaphore(0)
                val cachePutsCompleted = Semaphore(0)
                val repository = BlockingCachePutCredentialRepository(
                    config = LocalCacheConfig(
                        keyPrefix = "jdbc:caffeine:extra:wb-close-admissions",
                        writeMode = CacheWriteMode.WRITE_BEHIND,
                        writeBehindBatchSize = admissionCount,
                        writeBehindQueueCapacity = 16,
                    ),
                    cachePutEntered = cachePutsEntered,
                    releaseCachePut = releaseCachePuts,
                    cachePutCompleted = cachePutsCompleted,
                )
                val accepted = List(admissionCount) { index ->
                    ActorSchema.newCredentialRecord().copy(loginId = "close-admitted-$index")
                }
                val putFailures = List(admissionCount) { AtomicReference<Throwable?>() }
                val putThreads = accepted.mapIndexed { index, entity ->
                    Thread {
                        try {
                            repository.put(entity.id, entity)
                        } catch (cause: Throwable) {
                            putFailures[index].set(cause)
                        }
                    }.apply { start() }
                }
                val closeCompleted = CountDownLatch(1)
                val closeThread = Thread {
                    repository.close()
                    closeCompleted.countDown()
                }

                try {
                    cachePutsEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    closeThread.start()
                    awaitHealthReport(repository) { it.workerState == CacheWorkerState.DRAINING }
                        .workerState shouldBeEqualTo CacheWorkerState.DRAINING

                    val rejected = ActorSchema.newCredentialRecord().copy(loginId = "close-rejected")
                    val rejection = assertFailsWith<IllegalStateException> {
                        repository.put(rejected.id, rejected)
                    }
                    rejection.message.orEmpty().contains("queue is full").shouldBeFalse()
                    rejection.message.orEmpty().contains("closing, closed, or terminal").shouldBeTrue()
                    repository.cache.getIfPresent(repository.serializeKey(rejected.id)).shouldBeNull()

                    releaseCachePuts.release(2)
                    cachePutsCompleted.tryAcquire(2, 5, TimeUnit.SECONDS).shouldBeTrue()
                    closeCompleted.await(100, TimeUnit.MILLISECONDS).shouldBeFalse()
                    repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.DRAINING

                    releaseCachePuts.release()
                    cachePutsCompleted.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()
                    closeCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                } finally {
                    releaseCachePuts.release(admissionCount)
                    putThreads.forEach { it.join(5_000) }
                    closeThread.interrupt()
                    closeThread.join(5_000)
                    repository.close()
                }

                putThreads.forEach { it.isAlive.shouldBeFalse() }
                closeThread.isAlive.shouldBeFalse()
                putFailures.forEach { it.get().shouldBeNull() }
                repository.validateConsistency().workerState shouldBeEqualTo CacheWorkerState.STOPPED
                repository.validateConsistency().queueDepth shouldBeEqualTo 0
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `put - Write-Behind queue overflow throws IllegalStateException`(testDB: TestDB) {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:extra:wb-overflow",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 1,
                writeBehindQueueCapacity = 1,
            )
            val repository = BlockingFlushCredentialRepository(
                config = config,
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )
            val entities = List(3) { ActorSchema.newCredentialRecord() }

            withCredentialTable(testDB) {
                try {
                    repository.put(entities[0].id, entities[0])
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    repository.put(entities[1].id, entities[1])

                    val failure = assertFailsWith<IllegalStateException> {
                        repository.put(entities[2].id, entities[2])
                    }
                    failure.message.orEmpty().contains("queue is full").shouldBeTrue()
                } finally {
                    releaseFlush.countDown()
                    repository.close()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind health report
    // -------------------------------------------------------------------------

    @Nested
    inner class WriteBehindHealthReportTest: AbstractJdbcCaffeineTest() {

        @Test
        fun `validateConsistency - non-write-behind repository reports not applicable`() {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:read-only-health",
                    writeMode = CacheWriteMode.READ_ONLY,
                )
            )

            try {
                val report = repository.validateConsistency()

                report.mode shouldBeEqualTo CacheWriteMode.READ_ONLY
                report.queueDepth shouldBeEqualTo 0
                report.workerState shouldBeEqualTo CacheWorkerState.NOT_APPLICABLE
                report.lastFlushError.shouldBeNull()
            } finally {
                repository.close()
            }
        }

        @Test
        fun `validateConsistency - write-behind idle repository does not start flush job`() {
            val repository = CredentialJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-health-idle",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            try {
                isWriteBehindJobInitialized(repository).shouldBeFalse()
                val report = repository.validateConsistency()

                report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                report.queueDepth shouldBeEqualTo 0
                report.workerState shouldBeEqualTo CacheWorkerState.IDLE
                report.lastFlushError.shouldBeNull()
                isWriteBehindJobInitialized(repository).shouldBeFalse()
            } finally {
                repository.close()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `validateConsistency - in-flight write-behind batch reports queue depth`(testDB: TestDB) {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = BlockingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-health-in-flight",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = ActorSchema.findActorById(existingId).shouldNotBeNull()
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
        fun `validateConsistency - write-behind flush failure is reported`(testDB: TestDB) {
            val flushFailed = CountDownLatch(1)
            val repository = FailingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-health-failure",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushFailed = flushFailed,
            )

            withActorTable(testDB) {
                val existingId = ActorTable.select(ActorTable.id).first()[ActorTable.id].value
                val updated = ActorSchema.findActorById(existingId).shouldNotBeNull()
                    .copy(firstName = "health-failure")

                try {
                    repository.put(existingId, updated)
                    flushFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val report = awaitHealthReport(repository) { health ->
                        health.queueDepth == 1 && health.lastFlushError != null
                    }
                    report.mode shouldBeEqualTo CacheWriteMode.WRITE_BEHIND
                    report.queueDepth shouldBeEqualTo 1
                    report.workerState shouldBeEqualTo CacheWorkerState.RUNNING
                    report.lastFlushError.shouldNotBeNull()
                } finally {
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `write-behind retries retained batch after transient flush failure`(testDB: TestDB) {
            val flushFailed = CountDownLatch(1)
            val flushSucceeded = CountDownLatch(1)
            val repository = TransientFailingFlushJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:extra:wb-transient-failure",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                ),
                flushFailed = flushFailed,
                flushSucceeded = flushSucceeded,
            )

            withActorTable(testDB) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }
                val first = actors[0].copy(firstName = "transient-first")
                val second = actors[1].copy(firstName = "transient-second")

                try {
                    repository.put(first.id, first)
                    flushFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val failedReport = awaitHealthReport(repository) { health ->
                        health.queueDepth == 1 && health.lastFlushError != null
                    }
                    failedReport.queueDepth shouldBeEqualTo 1
                    failedReport.workerState shouldBeEqualTo CacheWorkerState.RUNNING
                    failedReport.lastFlushError.shouldNotBeNull()

                    repository.put(second.id, second)
                    flushSucceeded.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    val recoveredReport = awaitHealthReport(repository) { health ->
                        health.queueDepth == 0 && health.lastFlushError == null
                    }
                    recoveredReport.queueDepth shouldBeEqualTo 0
                    recoveredReport.workerState shouldBeEqualTo CacheWorkerState.RUNNING
                    recoveredReport.lastFlushError.shouldBeNull()
                    commit()
                    ActorSchema.findActorById(first.id).shouldNotBeNull().firstName shouldBeEqualTo first.firstName
                    ActorSchema.findActorById(second.id).shouldNotBeNull().firstName shouldBeEqualTo second.firstName
                } finally {
                    repository.close()
                }
            }
        }
    }

    private class FailingCacheWarmJdbcRepository(
        private val failure: Throwable,
        private val failInSerializeKey: Boolean = false,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(
        LocalCacheConfig(keyPrefix = "jdbc:caffeine:extra:find-all-failure")
    ) {

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
            this[ActorTable.firstName] = entity.firstName
            this[ActorTable.lastName] = entity.lastName
            this[ActorTable.email] = entity.email
        }

        override fun serializeKey(id: Long): String {
            if (failInSerializeKey) {
                throw failure
            }
            return super.serializeKey(id)
        }

        override fun extractId(entity: ActorRecord): Long {
            if (failInSerializeKey) {
                return entity.id
            }
            throw failure
        }
    }

    private class BlockingFlushJdbcRepository(
        config: LocalCacheConfig,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch,
        override val writeBehindCloseWaitDuration: Duration = Duration.ofSeconds(30),
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

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

    private class BlockingCachePutJdbcRepository(
        config: LocalCacheConfig,
        cachePutEntered: CountDownLatch,
        releaseCachePut: Semaphore,
        cacheValuePublished: Semaphore,
        releaseCachePutAfterPublish: Semaphore,
        cachePutCompleted: Semaphore,
        override val writeBehindCloseWaitDuration: Duration,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

        private val delegateCache = Caffeine.newBuilder().build<String, ActorRecord>()

        override val cache: Cache<String, ActorRecord> = object: Cache<String, ActorRecord> by delegateCache {
            override fun put(key: String, value: ActorRecord) {
                cachePutEntered.countDown()
                try {
                    releaseCachePut.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()
                    delegateCache.put(key, value)
                    cacheValuePublished.release()
                    releaseCachePutAfterPublish.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()
                } finally {
                    cachePutCompleted.release()
                }
            }
        }

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

        override fun UpdateStatement.updateEntity(entity: ActorRecord) {
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

    private class BlockingCachePutCredentialRepository(
        config: LocalCacheConfig,
        cachePutEntered: CountDownLatch,
        releaseCachePut: Semaphore,
        cachePutCompleted: Semaphore,
    ): AbstractJdbcCaffeineRepository<UUID, ActorSchema.CredentialRecord>(config) {

        private val delegateCache = Caffeine.newBuilder().build<String, ActorSchema.CredentialRecord>()

        override val cache: Cache<String, ActorSchema.CredentialRecord> =
            object: Cache<String, ActorSchema.CredentialRecord> by delegateCache {
                override fun put(key: String, value: ActorSchema.CredentialRecord) {
                    cachePutEntered.countDown()
                    try {
                        releaseCachePut.tryAcquire(5, TimeUnit.SECONDS).shouldBeTrue()
                        delegateCache.put(key, value)
                    } finally {
                        cachePutCompleted.release()
                    }
                }
            }

        override val table: IdTable<UUID> = CredentialTable

        override fun ResultRow.toEntity(): ActorSchema.CredentialRecord = toCredentialRecord()

        override fun UpdateStatement.updateEntity(entity: ActorSchema.CredentialRecord) {
            this[CredentialTable.loginId] = entity.loginId
            this[CredentialTable.email] = entity.email
            this[CredentialTable.lastLoginAt] = entity.lastLoginAt
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorSchema.CredentialRecord) {
            this[CredentialTable.id] = entity.id
            this[CredentialTable.loginId] = entity.loginId
            this[CredentialTable.email] = entity.email
            this[CredentialTable.lastLoginAt] = entity.lastLoginAt
        }

        override fun extractId(entity: ActorSchema.CredentialRecord): UUID = entity.id
    }

    private class BlockingFlushCredentialRepository(
        config: LocalCacheConfig,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch,
    ): AbstractJdbcCaffeineRepository<UUID, ActorSchema.CredentialRecord>(config) {

        override val table: IdTable<UUID> = CredentialTable

        override fun ResultRow.toEntity(): ActorSchema.CredentialRecord = toCredentialRecord()

        override fun UpdateStatement.updateEntity(entity: ActorSchema.CredentialRecord) {
            awaitFlushRelease()
            this[CredentialTable.loginId] = entity.loginId
            this[CredentialTable.email] = entity.email
            this[CredentialTable.lastLoginAt] = entity.lastLoginAt
        }

        override fun BatchInsertStatement.insertEntity(entity: ActorSchema.CredentialRecord) {
            awaitFlushRelease()
            this[CredentialTable.id] = entity.id
            this[CredentialTable.loginId] = entity.loginId
            this[CredentialTable.email] = entity.email
            this[CredentialTable.lastLoginAt] = entity.lastLoginAt
        }

        override fun extractId(entity: ActorSchema.CredentialRecord): UUID = entity.id

        private fun awaitFlushRelease() {
            flushStarted.countDown()
            releaseFlush.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }
    }

    private class FailingFlushJdbcRepository(
        config: LocalCacheConfig,
        private val flushFailed: CountDownLatch,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

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

    private class TransientFailingFlushJdbcRepository(
        config: LocalCacheConfig,
        private val flushFailed: CountDownLatch,
        private val flushSucceeded: CountDownLatch,
    ): AbstractJdbcCaffeineRepository<Long, ActorRecord>(config) {

        private val updateAttempts = AtomicInteger()

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = toActorRecord()

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

    private fun awaitThreadWaiting(thread: Thread): Boolean {
        repeat(100) {
            if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING) return true
            Thread.sleep(10)
        }
        return thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindJobOf(repository: AbstractJdbcCaffeineRepository<*, *>): Job {
        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindJob\$delegate")
        field.isAccessible = true

        return (field.get(repository) as Lazy<Job>).value
    }

    private fun setPrivateLong(
        repository: AbstractJdbcCaffeineRepository<*, *>,
        fieldName: String,
        value: Long,
    ) {
        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.setLong(repository, value)
    }

    private fun setWriteBehindAdmissions(
        repository: AbstractJdbcCaffeineRepository<*, *>,
        inProgress: Int = 0,
        drainedAtNanos: Long,
    ) {
        val admissionsClass = AbstractJdbcCaffeineRepository::class.java.declaredClasses
            .single { it.simpleName == "WriteBehindAdmissions" }
        val constructor = admissionsClass.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        val admissions = constructor.newInstance(inProgress, drainedAtNanos)

        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindAdmissions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as AtomicReference<Any?>).set(admissions)
    }

    private fun setWriteBehindJobCompletionAt(
        repository: AbstractJdbcCaffeineRepository<*, *>,
        completedAtNanos: Long,
    ) {
        val completionClass = AbstractJdbcCaffeineRepository::class.java.declaredClasses
            .single { it.simpleName == "WriteBehindJobCompletion" }
        val constructor = completionClass.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        val completion = constructor.newInstance(null, completedAtNanos)

        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindJobCompletion")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repository) as AtomicReference<Any?>).set(completion)
    }

    private fun writeBehindReadinessWasWithinCloseBudget(
        repository: AbstractJdbcCaffeineRepository<*, *>,
    ): Boolean {
        val method = AbstractJdbcCaffeineRepository::class.java
            .getDeclaredMethod("writeBehindReadinessWasWithinCloseBudgetLocked")
        method.isAccessible = true
        return method.invoke(repository) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun isWriteBehindJobInitialized(repository: AbstractJdbcCaffeineRepository<*, *>): Boolean {
        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindJob\$delegate")
        field.isAccessible = true

        return (field.get(repository) as Lazy<Job>).isInitialized()
    }
}
