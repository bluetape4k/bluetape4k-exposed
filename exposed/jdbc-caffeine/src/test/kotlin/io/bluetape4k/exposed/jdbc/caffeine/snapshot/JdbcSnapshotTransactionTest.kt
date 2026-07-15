package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotValueValidator
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotValueSizer
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.awaitility.Awaitility.await
import java.io.Serializable
import java.lang.ref.WeakReference
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JdbcSnapshotTransactionTest {

    @Test
    fun `commit publishes staged snapshot only after database success and performs zero extra SQL writes`() {
        val database = database()
        createTable(database)
        val cache = cache("commit:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val sqlWrites = AtomicInteger()

        transaction(database) {
            maxAttempts = 1
            addLogger(writeCountingLogger(sqlWrites))
            SnapshotRows.insert {
                it[id] = 1L
                it[value] = "db"
            }
            val writesBeforeStage = sqlWrites.get()

            val accepted = stageSnapshot(cache, miss, CacheSnapshot(Payload("cached"), "r1"))

            accepted.value shouldBeEqualTo Payload("cached")
            cache.lookup(1L).snapshot.shouldBeNull()
            sqlWrites.get() shouldBeEqualTo writesBeforeStage
        }

        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("cached")
        sqlWrites.get() shouldBeEqualTo 1
    }

    @Test
    fun `rollback discards snapshot and invalidation`() {
        val database = database()
        val cache = cache("rollback:v1")
        populate(database, cache, 1L, "old")
        val miss = cache.lookup(2L).miss.shouldNotBeNull()

        assertFailsWith<RollbackMarker> {
            transaction(database) {
                maxAttempts = 1
                stageSnapshot(cache, miss, CacheSnapshot(Payload("dirty")))
                stageInvalidation(cache, 1L)
                throw RollbackMarker()
            }
        }

        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("old")
        cache.lookup(2L).snapshot.shouldBeNull()
    }

    @Test
    fun `commit invalidation removes a previously committed snapshot`() {
        val database = database()
        val cache = cache("invalidate:v1")
        populate(database, cache, 1L, "old")

        transaction(database) { stageInvalidation(cache, 1L) }

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `mapped staging claims before mapper and executes mapper inside current root transaction`() {
        val database = database()
        val cache = cache("mapper:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val mappings = AtomicInteger()

        transaction(database) {
            maxAttempts = 1
            val receiver = this
            stageSnapshot(cache, miss, "source", CacheSnapshotMapper { source ->
                (transactionManager.currentOrNull() === receiver) shouldBeEqualTo true
                outerTransaction shouldBeEqualTo null
                mappings.incrementAndGet()
                CacheSnapshot(Payload(source))
            })
        }

        mappings.get() shouldBeEqualTo 1
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("source")
    }

    @Test
    fun `mapper failure consumes token before a second mapping call`() {
        val database = database()
        val cache = cache("mapper-failure:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val mappings = AtomicInteger()

        transaction(database) {
            maxAttempts = 1
            assertFailsWith<MapperMarker> {
                stageSnapshot(cache, miss, "bad", CacheSnapshotMapper {
                    mappings.incrementAndGet()
                    throw MapperMarker()
                })
            }
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, "again", CacheSnapshotMapper {
                    mappings.incrementAndGet()
                    CacheSnapshot(Payload(it))
                })
            }
        }

        mappings.get() shouldBeEqualTo 1
        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `snapshot fill rejects retry configuration before claiming while invalidation remains allowed`() {
        val database = database()
        val cache = cache("attempts:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        transaction(database) {
            maxAttempts = 2
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("wrong")))
            }
            stageInvalidation(cache, 2L)
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(Payload("accepted")))
        }

        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("accepted")
    }

    @Test
    fun `captured non-current and nested receivers fail before mapping`() {
        val database = database(useNestedTransactions = true)
        val cache = cache("receiver:v1")
        val captured = AtomicReference<JdbcTransaction>()
        val mappings = AtomicInteger()

        transaction(database) { captured.set(this) }
        val staleMiss = cache.lookup(1L).miss.shouldNotBeNull()
        assertFailsWith<IllegalStateException> {
            captured.get().stageSnapshot(cache, staleMiss, "stale", CacheSnapshotMapper {
                mappings.incrementAndGet()
                CacheSnapshot(Payload(it))
            })
        }
        transaction(database) {
            maxAttempts = 1
            stageSnapshot(cache, staleMiss, CacheSnapshot(Payload("valid-after-rejection")))
        }

        transaction(database) {
            transaction(database) {
                val nestedMiss = cache.lookup(2L).miss.shouldNotBeNull()
                assertFailsWith<IllegalStateException> {
                    stageSnapshot(cache, nestedMiss, "nested", CacheSnapshotMapper {
                        mappings.incrementAndGet()
                        CacheSnapshot(Payload(it))
                    })
                }
                assertFailsWith<IllegalStateException> { stageInvalidation(cache, 3L) }
            }
        }

        mappings.get() shouldBeEqualTo 0
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("valid-after-rejection")
    }

    @Test
    fun `caller validator failure consumes the miss without staging a snapshot`() {
        val database = database()
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
            CaffeineSnapshotCacheConfig(
                snapshot = SnapshotCacheConfig("validator:v1", "payload-v1"),
                fenceStripes = 64,
            ),
            validator = CacheSnapshotValueValidator { throw ValidatorMarker() },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        transaction(database) {
            maxAttempts = 1
            assertFailsWith<ValidatorMarker> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("rejected")))
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
        transaction(database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("reused")))
            }
        }
    }

    @Test
    fun `same logical store identity cannot mix distinct facade instances`() {
        val database = database()
        val first = cache("identity-collision:v1")
        val second = cache("identity-collision:v1")
        populate(database, first, 1L, "first")
        populate(database, second, 2L, "second")

        transaction(database) {
            stageInvalidation(first, 1L)
            assertFailsWith<IllegalStateException> { stageInvalidation(second, 2L) }
        }

        first.lookup(1L).snapshot.shouldBeNull()
        second.lookup(2L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("second")
    }

    @Test
    fun `older miss cannot repopulate when newer invalidation wins a controlled race`() {
        val database = database()
        val failures = io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer(RACE_REPETITIONS)
        val cache = cache("stale-race:v1", failureBuffer = failures)
        val executor = TrackedExecutor(threadCount = 2)

        try {
            repeat(RACE_REPETITIONS) { index ->
                val id = index.toLong()
                val oldMiss = cache.lookup(id).miss.shouldNotBeNull()
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val invalidated = CountDownLatch(1)
                val newerInvalidation = executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    transaction(database) { stageInvalidation(cache, id) }
                    invalidated.countDown()
                }
                val olderFill = executor.submit {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    invalidated.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    transaction(database) {
                        maxAttempts = 1
                        stageSnapshot(cache, oldMiss, CacheSnapshot(Payload("stale-$id")))
                    }
                }

                ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
                start.countDown()
                newerInvalidation.get(5, TimeUnit.SECONDS)
                olderFill.get(5, TimeUnit.SECONDS)

                cache.lookup(id).snapshot.shouldBeNull()
                failures.poll().shouldNotBeNull().outcome shouldBeEqualTo SnapshotCacheOutcome.REJECTED
            }
        } finally {
            executor.close()
        }
        failures.size shouldBeEqualTo 0
    }

    @Test
    fun `lookup capacity failure happens before caller database load begins`() {
        val cache = cache("capacity-timing:v1", maxOutstandingMissTokens = 1)
        val databaseReads = AtomicInteger()
        cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<IllegalStateException> {
            cache.lookup(2L).miss.shouldNotBeNull().also { databaseReads.incrementAndGet() }
        }

        databaseReads.get() shouldBeEqualTo 0
    }

    @Test
    fun `staged retained weight is rejected before buffer mutation`() {
        val database = database()
        val cache = cache(
            namespace = "staged-weight:v1",
            maxStagedWeight = 5L,
            valueSizer = SnapshotValueSizer { 10L },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        transaction(database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("too-large")))
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
        transaction(database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("reused")))
            }
        }
    }

    @Test
    fun `only one coordinator interceptor is registered for repeated staging`() {
        val database = database()
        val cache = cache("interceptor:v1")

        transaction(database) {
            val before = interceptorCount(this)
            stageInvalidation(cache, 1L)
            val afterFirst = interceptorCount(this)
            stageInvalidation(cache, 2L)
            val afterSecond = interceptorCount(this)

            afterFirst shouldBeEqualTo before + 1
            afterSecond shouldBeEqualTo afterFirst
        }
    }

    @Test
    fun `earlier beforeCommit staging is captured but earlier beforeRollback staging is discarded`() {
        val database = database()
        val cache = cache("callback-order:v1")
        populate(database, cache, 1L, "direct")
        populate(database, cache, 2L, "callback")

        transaction(database) {
            registerInterceptor(object : StatementInterceptor {
                override fun beforeCommit(transaction: Transaction) {
                    (transaction as JdbcTransaction).stageInvalidation(cache, 2L)
                }
            })
            stageInvalidation(cache, 1L)
        }
        cache.lookup(1L).snapshot.shouldBeNull()
        cache.lookup(2L).snapshot.shouldBeNull()

        populate(database, cache, 3L, "keep")
        assertFailsWith<RollbackMarker> {
            transaction(database) {
                registerInterceptor(object : StatementInterceptor {
                    override fun beforeRollback(transaction: Transaction) {
                        (transaction as JdbcTransaction).stageInvalidation(cache, 3L)
                    }
                })
                stageInvalidation(cache, 4L)
                throw RollbackMarker()
            }
        }
        cache.lookup(3L).snapshot.shouldNotBeNull()
    }

    @Test
    fun `staging from a callback after the coordinator boundary is rejected`() {
        val database = database()
        val cache = cache("late-callback:v1")
        populate(database, cache, 1L, "remove")
        populate(database, cache, 2L, "keep")
        val rejected = AtomicBoolean()

        transaction(database) {
            stageInvalidation(cache, 1L)
            registerInterceptor(object : StatementInterceptor {
                override fun beforeCommit(transaction: Transaction) {
                    runCatching {
                        (transaction as JdbcTransaction).stageInvalidation(cache, 2L)
                    }.onFailure { rejected.set(true) }
                }
            })
        }

        rejected.get().shouldBeTrue()
        cache.lookup(1L).snapshot.shouldBeNull()
        cache.lookup(2L).snapshot.shouldNotBeNull()
    }

    @Test
    fun `nested commit followed by outer rollback publishes nothing`() {
        val database = database(useNestedTransactions = true)
        val cache = cache("nested-outer-rollback:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<RollbackMarker> {
            transaction(database) {
                maxAttempts = 1
                stageSnapshot(cache, miss, CacheSnapshot(Payload("outer")))
                transaction(database) { }
                throw RollbackMarker()
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `nested rollback followed by outer commit publishes root work only`() {
        val database = database(useNestedTransactions = true)
        val cache = cache("nested-rollback-outer-commit:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        transaction(database) {
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(Payload("root")))
            assertFailsWith<RollbackMarker> {
                transaction(database) { throw RollbackMarker() }
            }
        }

        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("root")
    }

    @Test
    fun `earlier throwing lifecycle callbacks never publish cache mutation`() {
        assertEarlierThrowingCallbackDoesNotPublish(Callback.AFTER_COMMIT)
        assertEarlierThrowingCallbackDoesNotPublish(Callback.BEFORE_ROLLBACK)
        assertEarlierThrowingCallbackDoesNotPublish(Callback.AFTER_ROLLBACK)
    }

    @Test
    fun `earlier throwing lifecycle callbacks retain no staged payload beyond transaction collection`() {
        val database = database()
        Callback.entries.forEach { callback ->
            val failures = io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer(4)
            val cache = cache("throwing-${callback.name.lowercase()}-gc:v1", failureBuffer = failures)
            val payloadReference = stageSnapshotBehindThrowingCallback(database, cache, callback)

            cache.lookup(1L).snapshot.shouldBeNull()
            failures.size shouldBeEqualTo 0
            await().atMost(Duration.ofSeconds(5)).until {
                System.gc()
                payloadReference.get() == null
            }
        }
    }

    @Test
    fun `commit then stage and rollback then stage are rejected`() {
        val database = database()
        val cache = cache("terminal:v1")

        transaction(database) {
            stageInvalidation(cache, 1L)
            commit()
            assertFailsWith<IllegalStateException> { stageInvalidation(cache, 2L) }
        }
        transaction(database) {
            stageInvalidation(cache, 3L)
            rollback()
            assertFailsWith<IllegalStateException> { stageInvalidation(cache, 4L) }
        }
    }

    @Test
    fun `failed invalidation attempt leaks nothing and successful retry publishes once`() {
        val database = database()
        val cache = cache("retry-invalidation:v1")
        populate(database, cache, 1L, "cached")
        val attempts = AtomicInteger()

        transaction(database) {
            maxAttempts = 2
            minRetryDelay = 0
            maxRetryDelay = 0
            stageInvalidation(cache, 1L)
            if (attempts.incrementAndGet() == 1) throw SQLException("retry")
        }

        attempts.get() shouldBeEqualTo 2
        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `outer snapshot retry reacquires a fresh miss for each database read`() {
        val database = database()
        val cache = cache("retry-fill:v1")
        val reads = AtomicInteger()

        val first = cache.lookup(1L).miss.shouldNotBeNull()
        assertFailsWith<RollbackMarker> {
            transaction(database) {
                maxAttempts = 1
                reads.incrementAndGet()
                stageSnapshot(cache, first, CacheSnapshot(Payload("first")))
                throw RollbackMarker()
            }
        }
        val second = cache.lookup(1L).miss.shouldNotBeNull()
        transaction(database) {
            maxAttempts = 1
            reads.incrementAndGet()
            stageSnapshot(cache, second, CacheSnapshot(Payload("second")))
        }

        reads.get() shouldBeEqualTo 2
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("second")
    }

    private fun assertEarlierThrowingCallbackDoesNotPublish(callback: Callback) {
        val database = database()
        val cache = cache("throw-${callback.name.lowercase()}:v1")
        populate(database, cache, 1L, "keep")
        val transactionFailure = AtomicBoolean()

        runCatching {
            transaction(database) {
                registerInterceptor(object : StatementInterceptor {
                    override fun afterCommit(transaction: Transaction) {
                        if (callback == Callback.AFTER_COMMIT) throw CallbackMarker()
                    }

                    override fun beforeRollback(transaction: Transaction) {
                        if (callback == Callback.BEFORE_ROLLBACK) throw CallbackMarker()
                    }

                    override fun afterRollback(transaction: Transaction) {
                        if (callback == Callback.AFTER_ROLLBACK) throw CallbackMarker()
                    }
                })
                stageInvalidation(cache, 1L)
                if (callback != Callback.AFTER_COMMIT) throw RollbackMarker()
            }
        }.onFailure { transactionFailure.set(true) }

        transactionFailure.get().shouldBeTrue()
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("keep")
    }

    private fun stageSnapshotBehindThrowingCallback(
        database: Database,
        cache: JdbcCaffeineSnapshotCache<Long, Payload>,
        callback: Callback,
    ): WeakReference<Payload> {
        var payload: Payload? = Payload("unpublished")
        val payloadReference = WeakReference(requireNotNull(payload))
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        runCatching {
            transaction(database) {
                maxAttempts = 1
                registerInterceptor(object : StatementInterceptor {
                    override fun afterCommit(transaction: Transaction) {
                        if (callback == Callback.AFTER_COMMIT) throw CallbackMarker()
                    }

                    override fun beforeRollback(transaction: Transaction) {
                        if (callback == Callback.BEFORE_ROLLBACK) throw CallbackMarker()
                    }

                    override fun afterRollback(transaction: Transaction) {
                        if (callback == Callback.AFTER_ROLLBACK) throw CallbackMarker()
                    }
                })
                stageSnapshot(cache, miss, CacheSnapshot(requireNotNull(payload)))
                if (callback != Callback.AFTER_COMMIT) throw RollbackMarker()
            }
        }
        payload = null
        return payloadReference
    }

    private fun populate(
        database: Database,
        cache: JdbcCaffeineSnapshotCache<Long, Payload>,
        id: Long,
        value: String,
    ) {
        val miss = cache.lookup(id).miss.shouldNotBeNull()
        transaction(database) {
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(Payload(value)))
        }
    }

    private fun cache(
        namespace: String,
        maxOutstandingMissTokens: Int = 10_000,
        maxStagedWeight: Long? = null,
        valueSizer: SnapshotValueSizer<Payload>? = null,
        failureBuffer: io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer =
            io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer(),
    ) = jdbcCaffeineSnapshotCache<Long, Payload>(
        CaffeineSnapshotCacheConfig(
            snapshot = SnapshotCacheConfig(namespace, "payload-v1"),
            maxStagedWeight = maxStagedWeight,
            fenceStripes = 64,
            maxOutstandingMissTokens = maxOutstandingMissTokens,
        ),
        valueSizer = valueSizer,
        failureBuffer = failureBuffer,
    )

    private fun database(useNestedTransactions: Boolean = false): Database = Database.connect(
        url = "jdbc:h2:mem:snapshot-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        databaseConfig = DatabaseConfig { this.useNestedTransactions = useNestedTransactions },
    )

    private fun createTable(database: Database) {
        transaction(database) { SchemaUtils.create(SnapshotRows) }
    }

    private fun writeCountingLogger(counter: AtomicInteger) = object : SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            if (context.statement.type.name in setOf("INSERT", "UPDATE", "DELETE")) counter.incrementAndGet()
        }
    }

    private fun interceptorCount(transaction: JdbcTransaction): Int {
        val accessor = transaction.javaClass.declaredMethods.single { it.name.startsWith("getInterceptors") }
        val interceptors = accessor.invoke(transaction) as Collection<*>
        return interceptors.size
    }

    private object SnapshotRows : Table("snapshot_rows_task4") {
        val id = long("id")
        val value = varchar("value", 64)
        override val primaryKey = PrimaryKey(id)
    }

    private data class Payload(val value: String) : Serializable
    private class RollbackMarker : RuntimeException()
    private class MapperMarker : RuntimeException()
    private class ValidatorMarker : RuntimeException()
    private class CallbackMarker : RuntimeException()
    private enum class Callback { AFTER_COMMIT, BEFORE_ROLLBACK, AFTER_ROLLBACK }

    private class TrackedExecutor(threadCount: Int) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(threadCount)
        private val futures = mutableListOf<Future<*>>()

        fun <T> submit(task: () -> T): Future<T> = executor.submit<T> { task() }.also { futures += it }

        override fun close() {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
        }
    }

    companion object {
        private const val RACE_REPETITIONS: Int = 100
    }
}
