@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.r2dbc.caffeine.snapshot

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
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.statements.api.R2dbcExposedConnection
import org.jetbrains.exposed.v1.r2dbc.transactions.R2dbcTransactionInterface
import org.jetbrains.exposed.v1.r2dbc.transactions.R2dbcTransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.transactionManager
import org.junit.jupiter.api.Test
import org.awaitility.Awaitility.await
import io.r2dbc.spi.R2dbcTransientResourceException
import io.r2dbc.spi.IsolationLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.Serializable
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class R2dbcSnapshotTransactionTest {

    @Test
    fun `commit publishes staged snapshot only after database success and performs zero extra SQL writes`() = runSuspendIO {
        val database = database()
        createTable(database)
        val cache = cache("commit:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val sqlWrites = AtomicInteger()

        suspendTransaction(db = database) {
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
    fun `rollback discards snapshot and invalidation`() = runSuspendIO {
        val database = database()
        val cache = cache("rollback:v1")
        populate(database, cache, 1L, "old")
        val miss = cache.lookup(2L).miss.shouldNotBeNull()

        assertFailsWith<RollbackMarker> {
            suspendTransaction(db = database) {
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
    fun `cancellation before commit publishes no dirty snapshot`() = runSuspendIO {
        val database = database()
        val cache = cache("cancel-before-commit:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<CancellationException> {
            suspendTransaction(db = database) {
                maxAttempts = 1
                stageSnapshot(cache, miss, CacheSnapshot(Payload("dirty")))
                throw CancellationException("cancel before commit")
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `unknown physical commit cancellation invokes no afterCommit cache event`() = runSuspendIO {
        val probe = CommitProbe()
        val database = cancellingCommitDatabase(probe)
        val failures = io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer(4)
        val cache = cache("unknown-commit:v1", failureBuffer = failures)
        val store = cache as io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore<Long, Payload>
        val prepared = store.claimMiss(cache.lookup(1L).miss.shouldNotBeNull())
            .prepare(CacheSnapshot(Payload("keep")))
        store.applySnapshots(listOf(prepared), NeverExpiredDeadline)

        val cancellation = assertFailsWith<CancellationException> {
            suspendTransaction(db = database) {
                maxAttempts = 1
                registerInterceptor(object : StatementInterceptor {
                    override fun beforeCommit(transaction: Transaction) {
                        probe.record(CommitEvent.BEFORE_COMMIT)
                    }

                    override fun afterCommit(transaction: Transaction) {
                        probe.record(CommitEvent.AFTER_COMMIT)
                    }
                })
                stageInvalidation(cache, 1L)
            }
        }

        cancellation.message shouldBeEqualTo "physical commit outcome unknown"
        probe.commitEvents shouldBeEqualTo listOf(
            CommitEvent.BEFORE_COMMIT,
            CommitEvent.PHYSICAL_COMMIT_STARTED,
        )
        probe.afterCommitCount shouldBeEqualTo 0
        probe.rollbackAttemptCount shouldBeEqualTo 1
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("keep")
        failures.size shouldBeEqualTo 0
    }

    @Test
    fun `commit invalidation removes a previously committed snapshot`() = runSuspendIO {
        val database = database()
        val cache = cache("invalidate:v1")
        populate(database, cache, 1L, "old")

        suspendTransaction(db = database) { stageInvalidation(cache, 1L) }

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `mapped staging claims before mapper and executes mapper inside current root transaction`() = runSuspendIO {
        val database = database()
        val cache = cache("mapper:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val mappings = AtomicInteger()

        suspendTransaction(db = database) {
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
    fun `mapper failure consumes token before a second mapping call`() = runSuspendIO {
        val database = database()
        val cache = cache("mapper-failure:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        val mappings = AtomicInteger()

        suspendTransaction(db = database) {
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
    fun `snapshot fill rejects retry configuration before claiming while invalidation remains allowed`() = runSuspendIO {
        val database = database()
        val cache = cache("attempts:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        suspendTransaction(db = database) {
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
    fun `captured non-current and nested receivers fail before mapping`() = runSuspendIO {
        val database = database(useNestedTransactions = true)
        val cache = cache("receiver:v1")
        val captured = AtomicReference<R2dbcTransaction>()
        val mappings = AtomicInteger()

        suspendTransaction(db = database) { captured.set(this) }
        val staleMiss = cache.lookup(1L).miss.shouldNotBeNull()
        assertFailsWith<IllegalStateException> {
            captured.get().stageSnapshot(cache, staleMiss, "stale", CacheSnapshotMapper {
                mappings.incrementAndGet()
                CacheSnapshot(Payload(it))
            })
        }
        suspendTransaction(db = database) {
            maxAttempts = 1
            stageSnapshot(cache, staleMiss, CacheSnapshot(Payload("valid-after-rejection")))
        }

        suspendTransaction(db = database) {
            suspendTransaction(db = database) {
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
    fun `caller validator failure consumes the miss without staging a snapshot`() = runSuspendIO {
        val database = database()
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            CaffeineSnapshotCacheConfig(
                snapshot = SnapshotCacheConfig("validator:v1", "payload-v1"),
                fenceStripes = 64,
            ),
            validator = CacheSnapshotValueValidator { throw ValidatorMarker() },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        suspendTransaction(db = database) {
            maxAttempts = 1
            assertFailsWith<ValidatorMarker> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("rejected")))
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
        suspendTransaction(db = database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("reused")))
            }
        }
    }

    @Test
    fun `same logical store identity cannot mix distinct facade instances`() = runSuspendIO {
        val database = database()
        val first = cache("identity-collision:v1")
        val second = cache("identity-collision:v1")
        populate(database, first, 1L, "first")
        populate(database, second, 2L, "second")

        suspendTransaction(db = database) {
            stageInvalidation(first, 1L)
            assertFailsWith<IllegalStateException> { stageInvalidation(second, 2L) }
        }

        first.lookup(1L).snapshot.shouldBeNull()
        second.lookup(2L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("second")
    }

    @Test
    fun `older miss cannot repopulate when newer invalidation wins a controlled race`() = runSuspendIO {
        val database = database()
        val failures = io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer(RACE_REPETITIONS)
        val cache = cache("stale-race:v1", failureBuffer = failures)
        coroutineScope {
            repeat(RACE_REPETITIONS) { index ->
                val id = index.toLong()
                val oldMiss = cache.lookup(id).miss.shouldNotBeNull()
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val invalidated = CountDownLatch(1)
                val newerInvalidation = async(Dispatchers.IO) {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    suspendTransaction(db = database) { stageInvalidation(cache, id) }
                    invalidated.countDown()
                }
                val olderFill = async(Dispatchers.IO) {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    invalidated.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    suspendTransaction(db = database) {
                        maxAttempts = 1
                        stageSnapshot(cache, oldMiss, CacheSnapshot(Payload("stale-$id")))
                    }
                }

                ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
                start.countDown()
                withTimeout(5_000) {
                    newerInvalidation.await()
                    olderFill.await()
                }

                cache.lookup(id).snapshot.shouldBeNull()
                failures.poll().shouldNotBeNull().outcome shouldBeEqualTo SnapshotCacheOutcome.REJECTED
            }
        }
        failures.size shouldBeEqualTo 0
    }

    @Test
    fun `lookup capacity failure happens before caller database load begins`() = runSuspendIO {
        val cache = cache("capacity-timing:v1", maxOutstandingMissTokens = 1)
        val databaseReads = AtomicInteger()
        cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<IllegalStateException> {
            cache.lookup(2L).miss.shouldNotBeNull().also { databaseReads.incrementAndGet() }
        }

        databaseReads.get() shouldBeEqualTo 0
    }

    @Test
    fun `staged retained weight is rejected before buffer mutation`() = runSuspendIO {
        val database = database()
        val cache = cache(
            namespace = "staged-weight:v1",
            maxStagedWeight = 5L,
            valueSizer = SnapshotValueSizer { 10L },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        suspendTransaction(db = database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("too-large")))
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
        suspendTransaction(db = database) {
            maxAttempts = 1
            assertFailsWith<IllegalStateException> {
                stageSnapshot(cache, miss, CacheSnapshot(Payload("reused")))
            }
        }
    }

    @Test
    fun `only one coordinator interceptor is registered for repeated staging`() = runSuspendIO {
        val database = database()
        val cache = cache("interceptor:v1")

        suspendTransaction(db = database) {
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
    fun `earlier beforeCommit staging is captured but earlier beforeRollback staging is discarded`() = runSuspendIO {
        val database = database()
        val cache = cache("callback-order:v1")
        populate(database, cache, 1L, "direct")
        populate(database, cache, 2L, "callback")

        suspendTransaction(db = database) {
            registerInterceptor(object : StatementInterceptor {
                override fun beforeCommit(transaction: Transaction) {
                    (transaction as R2dbcTransaction).stageInvalidation(cache, 2L)
                }
            })
            stageInvalidation(cache, 1L)
        }
        cache.lookup(1L).snapshot.shouldBeNull()
        cache.lookup(2L).snapshot.shouldBeNull()

        populate(database, cache, 3L, "keep")
        assertFailsWith<RollbackMarker> {
            suspendTransaction(db = database) {
                registerInterceptor(object : StatementInterceptor {
                    override fun beforeRollback(transaction: Transaction) {
                        (transaction as R2dbcTransaction).stageInvalidation(cache, 3L)
                    }
                })
                stageInvalidation(cache, 4L)
                throw RollbackMarker()
            }
        }
        cache.lookup(3L).snapshot.shouldNotBeNull()
    }

    @Test
    fun `staging from a callback after the coordinator boundary is rejected`() = runSuspendIO {
        val database = database()
        val cache = cache("late-callback:v1")
        populate(database, cache, 1L, "remove")
        populate(database, cache, 2L, "keep")
        val rejected = AtomicBoolean()

        suspendTransaction(db = database) {
            stageInvalidation(cache, 1L)
            registerInterceptor(object : StatementInterceptor {
                override fun beforeCommit(transaction: Transaction) {
                    runCatching {
                        (transaction as R2dbcTransaction).stageInvalidation(cache, 2L)
                    }.onFailure { rejected.set(true) }
                }
            })
        }

        rejected.get().shouldBeTrue()
        cache.lookup(1L).snapshot.shouldBeNull()
        cache.lookup(2L).snapshot.shouldNotBeNull()
    }

    @Test
    fun `nested commit followed by outer rollback publishes nothing`() = runSuspendIO {
        val database = database(useNestedTransactions = true)
        val cache = cache("nested-outer-rollback:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<RollbackMarker> {
            suspendTransaction(db = database) {
                maxAttempts = 1
                stageSnapshot(cache, miss, CacheSnapshot(Payload("outer")))
                suspendTransaction(db = database) { }
                throw RollbackMarker()
            }
        }

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `nested rollback followed by outer commit publishes root work only`() = runSuspendIO {
        val database = database(useNestedTransactions = true)
        val cache = cache("nested-rollback-outer-commit:v1")
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        suspendTransaction(db = database) {
            maxAttempts = 1
            stageSnapshot(cache, miss, CacheSnapshot(Payload("root")))
            assertFailsWith<RollbackMarker> {
                suspendTransaction(db = database) { throw RollbackMarker() }
            }
        }

        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("root")
    }

    @Test
    fun `earlier throwing lifecycle callbacks never publish cache mutation`() = runSuspendIO {
        assertEarlierThrowingCallbackDoesNotPublish(Callback.AFTER_COMMIT)
        assertEarlierThrowingCallbackDoesNotPublish(Callback.BEFORE_ROLLBACK)
        assertEarlierThrowingCallbackDoesNotPublish(Callback.AFTER_ROLLBACK)
    }

    @Test
    fun `earlier throwing lifecycle callbacks retain no staged payload beyond transaction collection`() = runSuspendIO {
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
    fun `commit then stage and rollback then stage are rejected`() = runSuspendIO {
        val database = database()
        val cache = cache("terminal:v1")

        suspendTransaction(db = database) {
            stageInvalidation(cache, 1L)
            commit()
            assertFailsWith<IllegalStateException> { stageInvalidation(cache, 2L) }
        }
        suspendTransaction(db = database) {
            stageInvalidation(cache, 3L)
            rollback()
            assertFailsWith<IllegalStateException> { stageInvalidation(cache, 4L) }
        }
    }

    @Test
    fun `failed invalidation attempt leaks nothing and successful retry publishes once`() = runSuspendIO {
        val database = database()
        val cache = cache("retry-invalidation:v1")
        populate(database, cache, 1L, "cached")
        val attempts = AtomicInteger()

        suspendTransaction(db = database) {
            maxAttempts = 2
            minRetryDelay = 0
            maxRetryDelay = 0
            stageInvalidation(cache, 1L)
            if (attempts.incrementAndGet() == 1) throw R2dbcTransientResourceException("retry")
        }

        attempts.get() shouldBeEqualTo 2
        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `outer snapshot retry reacquires a fresh miss for each database read`() = runSuspendIO {
        val database = database()
        val cache = cache("retry-fill:v1")
        val reads = AtomicInteger()

        val first = cache.lookup(1L).miss.shouldNotBeNull()
        assertFailsWith<RollbackMarker> {
            suspendTransaction(db = database) {
                maxAttempts = 1
                reads.incrementAndGet()
                stageSnapshot(cache, first, CacheSnapshot(Payload("first")))
                throw RollbackMarker()
            }
        }
        val second = cache.lookup(1L).miss.shouldNotBeNull()
        suspendTransaction(db = database) {
            maxAttempts = 1
            reads.incrementAndGet()
            stageSnapshot(cache, second, CacheSnapshot(Payload("second")))
        }

        reads.get() shouldBeEqualTo 2
        cache.lookup(1L).snapshot.shouldNotBeNull().value shouldBeEqualTo Payload("second")
    }

    private suspend fun assertEarlierThrowingCallbackDoesNotPublish(callback: Callback) {
        val database = database()
        val cache = cache("throw-${callback.name.lowercase()}:v1")
        populate(database, cache, 1L, "keep")
        val transactionFailure = AtomicBoolean()

        runCatching {
            suspendTransaction(db = database) {
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

    private suspend fun stageSnapshotBehindThrowingCallback(
        database: R2dbcDatabase,
        cache: R2dbcCaffeineSnapshotCache<Long, Payload>,
        callback: Callback,
    ): WeakReference<Payload> {
        var payload: Payload? = Payload("unpublished")
        val payloadReference = WeakReference(requireNotNull(payload))
        val miss = cache.lookup(1L).miss.shouldNotBeNull()
        runCatching {
            suspendTransaction(db = database) {
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

    private suspend fun populate(
        database: R2dbcDatabase,
        cache: R2dbcCaffeineSnapshotCache<Long, Payload>,
        id: Long,
        value: String,
    ) {
        val miss = cache.lookup(id).miss.shouldNotBeNull()
        suspendTransaction(db = database) {
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
    ) = r2dbcCaffeineSnapshotCache<Long, Payload>(
        CaffeineSnapshotCacheConfig(
            snapshot = SnapshotCacheConfig(namespace, "payload-v1"),
            maxStagedWeight = maxStagedWeight,
            fenceStripes = 64,
            maxOutstandingMissTokens = maxOutstandingMissTokens,
        ),
        valueSizer = valueSizer,
        failureBuffer = failureBuffer,
    )

    private fun database(useNestedTransactions: Boolean = false): R2dbcDatabase {
        val config = R2dbcDatabaseConfig {
            setUrl("r2dbc:h2:mem:///snapshot-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;")
            this.useNestedTransactions = useNestedTransactions
        }
        return R2dbcDatabase.connect(databaseConfig = config)
    }

    private fun cancellingCommitDatabase(probe: CommitProbe): R2dbcDatabase {
        return R2dbcDatabase.connect(
            manager = { database -> CancellingTransactionManager(TransactionManager(database), probe) },
        ) {
            setUrl("r2dbc:h2:mem:///unknown-commit-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;")
        }
    }

    private suspend fun createTable(database: R2dbcDatabase) {
        suspendTransaction(db = database) { SchemaUtils.create(SnapshotRows) }
    }

    private fun writeCountingLogger(counter: AtomicInteger) = object : SqlLogger {
        override fun log(context: StatementContext, transaction: Transaction) {
            if (context.statement.type.name in setOf("INSERT", "UPDATE", "DELETE")) counter.incrementAndGet()
        }
    }

    private fun interceptorCount(transaction: R2dbcTransaction): Int {
        val accessor = transaction.javaClass.declaredMethods.single { it.name.startsWith("getInterceptors") }
        val interceptors = accessor.invoke(transaction) as Collection<*>
        return interceptors.size
    }

    private object SnapshotRows : Table("snapshot_rows_task6") {
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

    private object NeverExpiredDeadline : io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline {
        override fun remaining(): Duration = Duration.ofDays(1)
        override val isExpired: Boolean = false
    }

    private class CancellingTransactionManager(
        private val delegate: TransactionManager,
        private val probe: CommitProbe,
    ) : R2dbcTransactionManager by delegate {
        override fun newTransaction(
            isolation: IsolationLevel?,
            readOnly: Boolean?,
            outerTransaction: R2dbcTransaction?,
        ): R2dbcTransaction = R2dbcTransaction(
            CancellingCommitTransaction(
                delegate.db,
                this,
                requireNotNull(isolation ?: delegate.defaultIsolationLevel),
                readOnly == true,
                outerTransaction,
                probe,
            ),
        )
    }

    /** Injectable Exposed transaction seam whose physical commit outcome is deliberately unknown. */
    private class CancellingCommitTransaction(
        override val db: R2dbcDatabase,
        override val transactionManager: R2dbcTransactionManager,
        override val transactionIsolation: IsolationLevel,
        override val readOnly: Boolean,
        override val outerTransaction: R2dbcTransaction?,
        private val probe: CommitProbe,
    ) : R2dbcTransactionInterface {
        override suspend fun connection(): R2dbcExposedConnection<*> =
            error("The physical commit seam does not need a connection handle.")

        override suspend fun commit(): Nothing {
            probe.record(CommitEvent.PHYSICAL_COMMIT_STARTED)
            throw CancellationException("physical commit outcome unknown")
        }

        override suspend fun rollback() {
            probe.recordRollbackAttempt()
        }

        override suspend fun close() = Unit
    }

    private class CommitProbe {
        private val events = CopyOnWriteArrayList<CommitEvent>()
        private val afterCommits = AtomicInteger()
        private val rollbacks = AtomicInteger()

        val commitEvents: List<CommitEvent> get() = events.toList()
        val afterCommitCount: Int get() = afterCommits.get()
        val rollbackAttemptCount: Int get() = rollbacks.get()

        fun record(event: CommitEvent) {
            check(events.size < MAX_COMMIT_EVENTS) { "Commit probe exceeded its bounded event capacity." }
            events += event
            if (event == CommitEvent.AFTER_COMMIT) afterCommits.incrementAndGet()
        }

        fun recordRollbackAttempt() {
            rollbacks.incrementAndGet()
        }
    }

    private enum class CommitEvent { BEFORE_COMMIT, PHYSICAL_COMMIT_STARTED, AFTER_COMMIT }

    companion object {
        private const val RACE_REPETITIONS: Int = 100
        private const val MAX_COMMIT_EVENTS: Int = 3
    }
}
