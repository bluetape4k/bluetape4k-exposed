package io.bluetape4k.exposed.jdbc.caffeine.repository

import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.AbstractJdbcCaffeineTest
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorJdbcCaffeineRepository
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.CredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.toActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withActorTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withCredentialTable
import io.bluetape4k.exposed.jdbc.caffeine.domain.CredentialJdbcCaffeineRepository
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
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
import java.time.Instant
import java.util.*
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun `get - concurrent cache misses run one loader per key`() {
        val repository = CountingActorRepository("jdbc:caffeine:atomic:get")
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)

        try {
            val futures = List(8) {
                executor.submit<ActorRecord?> {
                    ready.countDown()
                    start.await()
                    repository.get(1L)
                }
            }

            ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
            start.countDown()

            futures.map { it.get(5, TimeUnit.SECONDS) }.toSet().size shouldBeEqualTo 1
            repository.singleLoadCount.get() shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `getAll - concurrent cache misses run one loader per key`() {
        val repository = CountingActorRepository("jdbc:caffeine:atomic:get-all")
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val ids = listOf(1L, 2L, 3L)

        try {
            val futures = List(8) {
                executor.submit<Map<Long, ActorRecord>> {
                    ready.countDown()
                    start.await()
                    repository.getAll(ids)
                }
            }

            ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
            start.countDown()

            futures.forEach { future ->
                future.get(5, TimeUnit.SECONDS).keys shouldBeEqualTo ids.toSet()
            }
            repository.singleLoadCount.get() shouldBeEqualTo ids.size
            repository.bulkLoadCount.get() shouldBeEqualTo 0
        } finally {
            executor.shutdownNow()
        }
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

                    entities.size shouldBeEqualTo ActorTable.selectAll().count().toInt()
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

                    entities.size shouldBeEqualTo ActorTable.selectAll().count().toInt()
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

                writeBehindJobOf(repository).isCompleted shouldBeEqualTo true
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
                writeBehindJobOf(repository).isCompleted shouldBeEqualTo true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Write-Behind queue overflow → IllegalStateException (not silent data loss)
    // -------------------------------------------------------------------------

    @Nested
    inner class WriteBehindOverflowTest: AbstractJdbcCaffeineTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `put - Write-Behind queue overflow throws IllegalStateException`(testDB: TestDB) {
            // Use a capacity large enough that we can fill it synchronously before the worker drains it.
            // The worker needs a full DB batch transaction to drain, which takes far longer than
            // filling the queue in a CPU-bound loop.
            val capacity = 500
            val config = LocalCacheConfig(
                keyPrefix = "jdbc:caffeine:extra:wb-overflow",
                writeMode = CacheWriteMode.WRITE_BEHIND,
                writeBehindBatchSize = capacity,
                writeBehindQueueCapacity = capacity,
            )
            val repository = CredentialJdbcCaffeineRepository(config)
            // Pre-generate entities in memory — no DB insert needed
            val entities = List(capacity * 2) { ActorSchema.newCredentialRecord() }

            withCredentialTable(testDB) {
                var overflowSeen = false
                try {
                    for (entity in entities) {
                        try {
                            repository.put(entity.id, entity)
                        } catch (e: IllegalStateException) {
                            overflowSeen = true
                            break
                        }
                    }
                } finally {
                    repository.close()
                }
                // At least one put must have thrown — data must NOT be silently dropped
                overflowSeen.shouldBeTrue()
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

    @Suppress("UNCHECKED_CAST")
    private fun writeBehindJobOf(repository: AbstractJdbcCaffeineRepository<*, *>): Job {
        val field = AbstractJdbcCaffeineRepository::class.java.getDeclaredField("writeBehindJob\$delegate")
        field.isAccessible = true

        return (field.get(repository) as Lazy<Job>).value
    }
}
