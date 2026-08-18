package io.bluetape4k.exposed.jdbc.caffeine.repository

import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.AbstractJdbcCaffeineTest
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.CredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.toActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withSuspendedActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withSuspendedCredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSuspendedJdbcCaffeineRepository
import io.bluetape4k.exposed.jdbc.caffeine.domain.CredentialSuspendedJdbcCaffeineRepository
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
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
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Suspended JDBC Caffeine 레포지토리 추가 커버리지 테스트.
 *
 * 기존 시나리오 인터페이스에서 검증하지 않는 경로를 직접 테스트합니다:
 * - [AbstractSuspendedJdbcCaffeineRepository.countFromDb]
 * - [AbstractSuspendedJdbcCaffeineRepository.clear] 후 재조회
 * - [AbstractSuspendedJdbcCaffeineRepository.invalidateAll] (Collection 오버로드)
 * - Write-Behind [AbstractSuspendedJdbcCaffeineRepository.close] 시 남은 큐 flush 보장
 */
@Suppress("DEPRECATION")
class SuspendedJdbcCaffeineRepositoryExtraTest {

    companion object: KLogging()

    @Test
    fun `get - concurrent suspended cache misses run one loader per key`() = runSuspendIO {
        val repository = CountingSuspendedActorRepository("jdbc:caffeine:s-atomic:get")

        val results = Collections.synchronizedList(mutableListOf<ActorRecord?>())

        SuspendedJobTester()
            .workers(8)
            .rounds(1)
            .addAll(
                List(8) {
                    suspend {
                        results += repository.get(1L)
                    }
                }
            )
            .run()

        results.toSet() shouldHaveSize 1
        repository.singleLoadCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `getAll - concurrent suspended cache misses run one loader per key`() = runSuspendIO {
        val repository = CountingSuspendedActorRepository("jdbc:caffeine:s-atomic:get-all")
        val ids = listOf(1L, 2L, 3L)

        val results = Collections.synchronizedList(mutableListOf<Map<Long, ActorRecord>>())

        SuspendedJobTester()
            .workers(8)
            .rounds(1)
            .addAll(
                List(8) {
                    suspend {
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

    @Test
    fun `get - completed unique-key misses reclaim private load coordination entries`() = runSuspendIO {
        val repository = CountingSuspendedActorRepository("jdbc:caffeine:s-lifecycle:success")

        repeat(12) { id ->
            repository.get(id.toLong()).shouldNotBeNull()
        }

        loadMutexSizeOf(repository) shouldBeEqualTo 0
    }

    @Test
    fun `get - loader exception releases entry and a later caller retries`() = runSuspendIO {
        val expected = IllegalStateException("planned loader failure")
        val repository = ScriptedSuspendedActorRepository("jdbc:caffeine:s-lifecycle:failure") { id, attempt ->
            if (attempt == 1) throw expected
            ActorSchema.newActorRecord().withId(id)
        }

        val error = assertFailsWith<IllegalStateException> {
            repository.get(1L)
        }
        error shouldBeEqualTo expected

        repository.get(1L).shouldNotBeNull()
        repository.loadCount.get() shouldBeEqualTo 2
        loadMutexSizeOf(repository) shouldBeEqualTo 0
    }

    @Test
    fun `get - null result releases entry and a later caller retries`() = runSuspendIO {
        val repository = ScriptedSuspendedActorRepository("jdbc:caffeine:s-lifecycle:null") { id, attempt ->
            if (attempt == 1) null else ActorSchema.newActorRecord().withId(id)
        }

        repository.get(1L).shouldBeNull()
        repository.get(1L).shouldNotBeNull()
        repository.loadCount.get() shouldBeEqualTo 2
        loadMutexSizeOf(repository) shouldBeEqualTo 0
    }

    @Test
    fun `get - caller cancellation releases entry and preserves retry`() = runSuspendIO(timeout = 10.seconds) {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val repository = ScriptedSuspendedActorRepository("jdbc:caffeine:s-lifecycle:cancel") { id, _ ->
            loadStarted.complete(Unit)
            releaseLoad.await()
            ActorSchema.newActorRecord().withId(id)
        }
        val caller = async(Dispatchers.Default) {
            repository.get(1L)
        }

        loadStarted.await()
        caller.cancel(CancellationException("caller cancelled"))
        val error = assertFailsWith<CancellationException> {
            caller.await()
        }
        error.message shouldBeEqualTo "caller cancelled"

        releaseLoad.complete(Unit)
        repository.get(1L).shouldNotBeNull()
        repository.loadCount.get() shouldBeEqualTo 2
        loadMutexSizeOf(repository) shouldBeEqualTo 0
    }

    @Test
    fun `get - cancelled waiter does not remove holder entry`() = runSuspendIO(timeout = 10.seconds) {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val repository = ScriptedSuspendedActorRepository("jdbc:caffeine:s-lifecycle:waiter") { id, _ ->
            loadStarted.complete(Unit)
            releaseLoad.await()
            ActorSchema.newActorRecord().withId(id)
        }
        val holder = async(Dispatchers.Default) {
            repository.get(1L)
        }
        loadStarted.await()

        val waiter = async(Dispatchers.Default) {
            repository.get(1L)
        }
        withTimeout(5.seconds) {
            while (loadMutexUsersOf(repository) < 2) yield()
        }
        loadMutexUsersOf(repository) shouldBeEqualTo 2

        waiter.cancelAndJoin()
        loadMutexUsersOf(repository) shouldBeEqualTo 1

        releaseLoad.complete(Unit)
        holder.await().shouldNotBeNull()
        loadMutexSizeOf(repository) shouldBeEqualTo 0
    }

    @Test
    fun `get - release and new acquire boundary keeps successful loaders non-overlapping`() =
        runSuspendIO(timeout = 10.seconds) {
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val secondLoadStarted = CompletableDeferred<Unit>()
            val releaseSecondLoad = CompletableDeferred<Unit>()
            val thirdLoadStarted = CompletableDeferred<Unit>()
            val activeLoads = AtomicInteger()
            val maxActiveLoads = AtomicInteger()
            val repository = ScriptedSuspendedActorRepository("jdbc:caffeine:s-lifecycle:boundary") { id, attempt ->
                val active = activeLoads.incrementAndGet()
                maxActiveLoads.updateAndGet { current -> maxOf(current, active) }
                try {
                    loadStarted.complete(Unit)
                    if (attempt == 1) {
                        releaseLoad.await()
                        null
                    } else if (attempt == 2) {
                        secondLoadStarted.complete(Unit)
                        releaseSecondLoad.await()
                        null
                    } else {
                        thirdLoadStarted.complete(Unit)
                        ActorSchema.newActorRecord().withId(id)
                    }
                } finally {
                    activeLoads.decrementAndGet()
                }
            }
            val holder = async(Dispatchers.Default) {
                repository.get(1L)
            }
            loadStarted.await()

            val waiter = async(Dispatchers.Default) {
                repository.get(1L)
            }
            withTimeout(5.seconds) {
                while (loadMutexUsersOf(repository) < 2) yield()
            }
            loadMutexUsersOf(repository) shouldBeEqualTo 2

            releaseLoad.complete(Unit)
            holder.await().shouldBeNull()

            secondLoadStarted.await()
            val third = async(Dispatchers.Default) {
                repository.get(1L)
            }
            withTimeout(5.seconds) {
                while (loadMutexUsersOf(repository) < 2 && !thirdLoadStarted.isCompleted) yield()
            }
            loadMutexUsersOf(repository) shouldBeEqualTo 2
            thirdLoadStarted.isCompleted shouldBeEqualTo false

            releaseSecondLoad.complete(Unit)
            val results = listOf(waiter.await(), third.await())
            results.count { it == null } shouldBeEqualTo 1
            results.count { it != null } shouldBeEqualTo 1

            maxActiveLoads.get() shouldBeEqualTo 1
            repository.loadCount.get() shouldBeEqualTo 3
            loadMutexSizeOf(repository) shouldBeEqualTo 0
        }

    @Test
    fun `close cancels scope when cache invalidate fails`() {
        val repository = CloseProbeSuspendedJdbcCaffeineRepository()

        repository.close()

        repository.scopeCancelled.shouldBeTrue()
    }

    private class CountingSuspendedActorRepository(
        keyPrefix: String,
    ): AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(
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

        override suspend fun findByIdFromDb(id: Long): ActorRecord? {
            singleLoadCount.incrementAndGet()
            delay(100)
            return ActorSchema.newActorRecord().withId(id)
        }

        override suspend fun findAllFromDb(ids: Collection<Long>): List<ActorRecord> {
            bulkLoadCount.incrementAndGet()
            delay(100)
            return ids.map { ActorSchema.newActorRecord().withId(it) }
        }
    }

    private class ScriptedSuspendedActorRepository(
        keyPrefix: String,
        private val loader: suspend (Long, Int) -> ActorRecord?,
    ): AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(
        LocalCacheConfig(keyPrefix = keyPrefix, writeMode = CacheWriteMode.READ_ONLY)
    ) {
        val loadCount = AtomicInteger()

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord =
            error("DB row conversion is not used by this scripted lifecycle test")

        override fun UpdateStatement.updateEntity(entity: ActorRecord) = Unit

        override fun BatchInsertStatement.insertEntity(entity: ActorRecord) = Unit

        override fun extractId(entity: ActorRecord): Long = entity.id

        override suspend fun findByIdFromDb(id: Long): ActorRecord? =
            loader(id, loadCount.incrementAndGet())
    }

    private class CloseProbeSuspendedJdbcCaffeineRepository:
        AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(
            LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:s-close-probe",
                writeMode = CacheWriteMode.READ_ONLY
            )
        ) {
        var scopeCancelled: Boolean = false

        override val table: IdTable<Long> = ActorTable

        override fun ResultRow.toEntity(): ActorRecord = error("not used")

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
    // countFromDb + clear + invalidateAll — AutoInc Actor
    // -------------------------------------------------------------------------

    @Nested
    inner class SuspendedActorCacheManagementTest: AbstractJdbcCaffeineTest() {

        // 파라미터화 테스트 실행마다 새 레포지토리를 생성해서 DB 인스턴스 간 캐시 오염을 방지한다
        private fun newRepository() = ActorSuspendedJdbcCaffeineRepository(
            LocalCacheConfig(keyPrefix = "jdbc:caffeine:s-extra:actor", writeMode = CacheWriteMode.READ_ONLY)
        )

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `countFromDb - DB 직접 카운트를 반환한다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = newRepository()
            withSuspendedActorTable(testDB) {
                // countFromDb()는 캐시를 우회해 DB에서 직접 집계해야 한다
                val count = repository.countFromDb()
                count shouldBeEqualTo ActorTable.selectAll().count()
                count shouldBeGreaterThan 0L
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `clear - 캐시를 비우면 다음 get은 DB에서 다시 로드된다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            // DB 인스턴스마다 독립된 레포지토리를 사용해야 캐시 오염을 방지할 수 있다
            val repository = newRepository()
            withSuspendedActorTable(testDB) {
                val id = newSuspendedTransaction(Dispatchers.IO) {
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
        fun `invalidateAll - 여러 ID를 한 번에 캐시에서 제거한다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = newRepository()
            withSuspendedActorTable(testDB) {
                val ids = newSuspendedTransaction(Dispatchers.IO) {
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
    inner class SuspendedCacheWarmingFailureTest: AbstractJdbcCaffeineTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming Exception is skipped without losing query result`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 30.seconds) {
            val repository = FailingCacheWarmSuspendedJdbcRepository(
                failure = IllegalStateException("cache key failure")
            )

            withSuspendedActorTable(testDB) {
                RecordingLogbackAppender().use { appender ->
                    val entities = repository.findAll()

                    entities shouldHaveSize ActorTable.selectAll().count().toInt()
                    repository.cache.asMap().shouldBeEmpty()
                    appender.hasWarnContaining(
                        "Cache warming failed for entity - skipping. " +
                            "cacheName=jdbc:caffeine:s-extra:find-all-failure"
                    ).shouldBeTrue()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache key serialization Exception is warned and skipped`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 30.seconds) {
            val repository = FailingCacheWarmSuspendedJdbcRepository(
                failure = IllegalStateException("cache key serialization failure"),
                failInSerializeKey = true,
            )

            withSuspendedActorTable(testDB) {
                RecordingLogbackAppender().use { appender ->
                    val entities = repository.findAll()

                    entities shouldHaveSize ActorTable.selectAll().count().toInt()
                    repository.cache.asMap().shouldBeEmpty()
                    appender.hasWarnContaining(
                        "Cache warming failed for entity - skipping. " +
                            "cacheName=jdbc:caffeine:s-extra:find-all-failure"
                    ).shouldBeTrue()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming CancellationException is not swallowed`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 30.seconds) {
            val repository = FailingCacheWarmSuspendedJdbcRepository(
                failure = CancellationException("cache warming cancelled")
            )

            withSuspendedActorTable(testDB) {
                val error = assertFailsWith<CancellationException> {
                    repository.findAll()
                }

                error.message shouldBeEqualTo "cache warming cancelled"
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `findAll - cache warming Error is not swallowed`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = FailingCacheWarmSuspendedJdbcRepository(
                failure = AssertionError("fatal cache warming failure")
            )

            withSuspendedActorTable(testDB) {
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
    inner class SuspendedCredentialCacheManagementTest: AbstractJdbcCaffeineTest() {

        // 파라미터화 테스트 실행마다 새 레포지토리를 생성해서 DB 인스턴스 간 캐시 오염을 방지한다
        private fun newRepository() = CredentialSuspendedJdbcCaffeineRepository(
            LocalCacheConfig(keyPrefix = "jdbc:caffeine:s-extra:credential", writeMode = CacheWriteMode.READ_ONLY)
        )

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `countFromDb - UUID 테이블 DB 직접 카운트를 반환한다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = newRepository()
            withSuspendedCredentialTable(testDB) {
                val count = repository.countFromDb()
                count shouldBeEqualTo CredentialTable.selectAll().count()
                count shouldBeGreaterThan 0L
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `clear - UUID 테이블 캐시를 비우면 다음 get은 DB에서 다시 로드된다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = newRepository()
            withSuspendedCredentialTable(testDB) {
                val id = newSuspendedTransaction(Dispatchers.IO) {
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
        fun `invalidateAll - UUID 테이블 여러 ID를 한 번에 캐시에서 제거한다`(testDB: TestDB) = runSuspendIO(timeout = 30.seconds) {
            val repository = newRepository()
            withSuspendedCredentialTable(testDB) {
                val ids = newSuspendedTransaction(Dispatchers.IO) {
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
    inner class SuspendedWriteBehindCloseFlushTest: AbstractJdbcCaffeineTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close - Write-Behind 종료 시 큐에 남은 항목이 DB에 flush된다`(testDB: TestDB) = runSuspendIO(timeout = 60.seconds) {
            // AutoInc 테이블은 새 엔티티를 DB에 삽입하지 않으므로 UUID 테이블로 검증
            val config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:s-extra:wb-close",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 100,
                writeBehindQueueCapacity = 10_000,
            )
            val repository = CredentialSuspendedJdbcCaffeineRepository(config)

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
                        it[loginId] = faker.internet().domainWord() + "_swb_close_$it"
                        it[email] = "test_s_$it@example.com"
                        it[lastLoginAt] = Instant.now().minusSeconds(3600)
                    }
                }
                commit()

                prevCount = CredentialTable.selectAll().count()

                // 새 엔티티 10개를 Write-Behind 큐에 넣음
                // withTables는 suspend 미지원이므로 runBlocking으로 suspend 호출 감쌈
                runBlocking { repository.putAll(newMap) }

                // close() 호출 → 큐 드레인 후 종료 (flushBatch가 별도 transaction으로 커밋)
                repository.close()
            }

            // DB에 모두 반영됐는지 독립 트랜잭션으로 확인 (격리 수준 문제 우회)
            val newCount = transaction { CredentialTable.selectAll().count() }
            newCount shouldBeEqualTo prevCount + newEntities.size
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close - write-behind put 전에도 hang 없이 종료한다`(testDB: TestDB) = runSuspendIO(timeout = 35.seconds) {
            val repository = CredentialSuspendedJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:s-extra:wb-close-before-put",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withSuspendedCredentialTable(testDB) {
                repository.close()

                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `close - write-behind job 시작 후 반복 호출해도 hang 없이 종료한다`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 35.seconds) {
            val repository = CredentialSuspendedJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:s-extra:wb-close-idempotent",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withSuspendedCredentialTable(testDB) {
                val entity = ActorSchema.newCredentialRecord()

                repository.put(entity.id, entity)
                repository.close()
                repository.close()

                CredentialTable.selectAll()
                    .where { CredentialTable.id eq entity.id }
                    .count() shouldBeEqualTo 1L
                writeBehindJobOf(repository).isCompleted.shouldBeTrue()
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `put - cancelled full-queue send does not publish dirty cache`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 35.seconds) {
            val flushStarted = CountDownLatch(1)
            val releaseFlush = CountDownLatch(1)
            val repository = BlockingFlushSuspendedJdbcRepository(
                config = LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:s-extra:wb-cancel-full-send",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 1,
                    writeBehindQueueCapacity = 1,
                ),
                flushStarted = flushStarted,
                releaseFlush = releaseFlush,
            )

            withSuspendedActorTable(testDB) {
                val actors = ActorTable.selectAll().map { it.toActorRecord() }
                val blocked = actors[0].copy(firstName = "blocked-flush")
                val queued = actors[1].copy(firstName = "queued-write")
                val cancelled = actors[2].copy(firstName = "cancelled-write")

                try {
                    repository.put(blocked.id, blocked)
                    flushStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    repository.put(queued.id, queued)
                    val pending = async { repository.put(cancelled.id, cancelled) }
                    delay(100)

                    pending.isActive.shouldBeTrue()
                    repository.cache.getIfPresent(cancelled.id.toString()).shouldBeNull()

                    pending.cancelAndJoin()
                    repository.cache.getIfPresent(cancelled.id.toString()).shouldBeNull()
                } finally {
                    releaseFlush.countDown()
                    repository.close()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `put - closed write-behind queue does not publish dirty cache`(
            testDB: TestDB,
        ) = runSuspendIO(timeout = 35.seconds) {
            val repository = ActorSuspendedJdbcCaffeineRepository(
                LocalCacheConfig(
                    keyPrefix = "jdbc:caffeine:s-extra:wb-closed-queue",
                    writeMode = CacheWriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = 10,
                    writeBehindQueueCapacity = 16,
                )
            )

            withSuspendedActorTable(testDB) {
                val actor = ActorTable.selectAll().first().toActorRecord()
                    .copy(firstName = "closed-queue")

                repository.close()

                assertFailsWith<IllegalStateException> {
                    repository.put(actor.id, actor)
                }
                repository.cache.getIfPresent(actor.id.toString()).shouldBeNull()
            }
        }
    }

    private class FailingCacheWarmSuspendedJdbcRepository(
        private val failure: Throwable,
        private val failInSerializeKey: Boolean = false,
    ): AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(
        LocalCacheConfig(keyPrefix = "jdbc:caffeine:s-extra:find-all-failure")
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

    private class BlockingFlushSuspendedJdbcRepository(
        config: LocalCacheConfig,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch,
    ): AbstractSuspendedJdbcCaffeineRepository<Long, ActorRecord>(config) {

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

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindJobOf(repository: AbstractSuspendedJdbcCaffeineRepository<*, *>): Job {
        val field = AbstractSuspendedJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindJob\$delegate")
        field.isAccessible = true

        return (field.get(repository) as Lazy<Job>).value
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadMutexSizeOf(repository: AbstractSuspendedJdbcCaffeineRepository<*, *>): Int {
        val field = AbstractSuspendedJdbcCaffeineRepository::class.java.getDeclaredField("loadMutexes")
        field.isAccessible = true
        return (field.get(repository) as Map<String, *>).size
    }

    private fun loadMutexUsersOf(repository: AbstractSuspendedJdbcCaffeineRepository<*, *>): Int {
        val registryField = AbstractSuspendedJdbcCaffeineRepository::class.java
            .getDeclaredField("loadMutexes")
            .also { it.isAccessible = true }
        val entry = (registryField.get(repository) as Map<String, *>).values.firstOrNull() ?: return 0
        val usersField = entry.javaClass.getDeclaredField("users").also { it.isAccessible = true }
        return usersField.getInt(entry)
    }
}
