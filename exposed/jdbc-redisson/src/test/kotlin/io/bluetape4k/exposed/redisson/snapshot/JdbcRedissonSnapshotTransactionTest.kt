@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotValueValidator
import io.bluetape4k.exposed.cache.snapshot.ClaimedSnapshotMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailure
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLimits
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLookup
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMutation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import io.bluetape4k.exposed.cache.snapshot.stageSnapshotMutation
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import java.io.Serializable
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class JdbcRedissonSnapshotTransactionTest {

    @Test
    fun `source usage compiles and exposes only the exact transaction invalidation extension`() {
        val invalidator = fixture("api:v1").invalidator
        val database = database()

        transaction(database) {
            maxAttempts = 1
            stageInvalidation(invalidator, 1L)
        }

        val methods = Class.forName(
            "io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotTransactionKt",
        ).declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.name == "stageInvalidation"
        }
        methods.size shouldBeEqualTo 1
        methods.single().parameterTypes.toList() shouldBeEqualTo listOf(
            JdbcTransaction::class.java,
            JdbcRedissonSnapshotInvalidator::class.java,
            Any::class.java,
        )
    }

    @Test
    fun `commit submits the actual invalidator command only after transaction success`() {
        val fixture = fixture("commit:v1")

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            fixture.map.submittedIds.shouldBeEqualTo(emptyList())
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
        fixture.map.invokedMethods shouldBeEqualTo listOf("fastRemoveAsync")
    }

    @Test
    fun `rollback submits no Redis command`() {
        val fixture = fixture("rollback:v1")

        assertFailsWith<RollbackMarker> {
            transaction(database()) {
                maxAttempts = 1
                stageInvalidation(fixture.invalidator, 1L)
                throw RollbackMarker()
            }
        }

        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
        fixture.map.invokedMethods.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `captured closed transaction is rejected`() {
        val fixture = fixture("closed:v1")
        lateinit var captured: JdbcTransaction

        transaction(database()) {
            maxAttempts = 1
            captured = this
        }

        assertFailsWith<IllegalStateException> {
            captured.stageInvalidation(fixture.invalidator, 1L)
        }
        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `wrong current receiver and savepoint transaction are rejected`() {
        val fixture = fixture("nested:v1")
        val database = database(useNestedTransactions = true)

        transaction(database) outer@{
            maxAttempts = 1
            transaction(database) {
                maxAttempts = 1
                assertFailsWith<IllegalStateException> {
                    this@outer.stageInvalidation(fixture.invalidator, 1L)
                }
                assertFailsWith<IllegalStateException> {
                    stageInvalidation(fixture.invalidator, 2L)
                }
            }
        }

        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `transaction invalidation requires one configured database attempt`() {
        val fixture = fixture("attempts:v1")

        transaction(database()) {
            maxAttempts = 2
            assertFailsWith<IllegalStateException> {
                stageInvalidation(fixture.invalidator, 1L)
            }
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 2L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(2L))
    }

    @Test
    fun `repeated identifier coalesces to one last invalidation`() {
        val fixture = fixture("coalesce:v1")

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            stageInvalidation(fixture.invalidator, 1L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
    }

    @Test
    fun `same transaction rejects a failure buffer mismatch before second map access`() {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = SnapshotCacheConfig("buffer-identity:v1", "payload-v1", 32, 4),
            nearCacheMaximumSize = 16,
            maxEncodedKeyBytes = Long.SIZE_BYTES,
            maxBatchEncodedKeyBytes = Long.SIZE_BYTES,
            maxCommitEncodedKeyBytes = 256,
            maxOutstandingChunks = 4,
            maxOutstandingEncodedBytes = 256,
        )
        val first = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            config,
            snapshotCacheFailureBuffer(4),
        )

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(first, 1L)
            assertFailsWith<IllegalArgumentException> {
                jdbcRedissonSnapshotInvalidator(
                    client.proxy,
                    codec,
                    Long::class,
                    Payload::class,
                    config,
                    snapshotCacheFailureBuffer(4),
                )
            }
        }

        client.options.size shouldBeEqualTo 1
        map.submittedIds shouldBeEqualTo listOf(listOf(1L))
    }

    @Test
    fun `commit byte rejection preserves prior mutation and permits a later valid replacement`() {
        val fixture = fixture("commit-cap:v1", maxCommitEncodedKeyBytes = Long.SIZE_BYTES)

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            assertFailsWith<IllegalStateException> {
                stageInvalidation(fixture.invalidator, 2L)
            }
            stageInvalidation(fixture.invalidator, 1L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
    }

    @Test
    fun `all async stores and chunks are attempted before local invalidation and put phases`() {
        val events = mutableListOf<String>()
        val quotaRegistry = RedissonInvalidationQuotaRegistry()
        val sharedClient = RecordingRedissonClient(RecordingLocalMap<Long>().proxy).proxy
        val laterClient = RecordingRedissonClient(RecordingLocalMap<Long>().proxy).proxy
        val failedBuffer = snapshotCacheFailureBuffer(8)
        val neverBuffer = snapshotCacheFailureBuffer(8)
        val rejectedBuffer = snapshotCacheFailureBuffer(8)
        val laterBuffer = snapshotCacheFailureBuffer(8)
        val failureMap = RecordingLocalMap<Long>("failed", events).thenThrow(
            SubmissionFailure("redis://secret.example/identifier-1"),
        )
        val neverMap = RecordingLocalMap<Long>("never", events).thenReturn(rFuture(CompletableFuture()))
        val rejectedMap = RecordingLocalMap<Long>("rejected", events)
        val laterMap = RecordingLocalMap<Long>("later", events)
        val failed = testInvalidator("failed:v1", failureMap, sharedClient, quotaRegistry, failedBuffer, events, "failed")
        val pending = testInvalidator("never:v1", neverMap, sharedClient, quotaRegistry, neverBuffer, events, "never")
        val rejected = testInvalidator(
            "rejected:v1",
            rejectedMap,
            sharedClient,
            quotaRegistry,
            rejectedBuffer,
            events,
            "rejected",
        )
        val later = testInvalidator("later:v1", laterMap, laterClient, quotaRegistry, laterBuffer, events, "later")
        val local = RecordingSnapshotStore(events)

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(failed, 1L)
            stageInvalidation(failed, 2L)
            stageInvalidation(failed, 3L)
            stageInvalidation(pending, 4L)
            stageInvalidation(rejected, 5L)
            stageInvalidation(later, 6L)
            stageInvalidationMutation(this, TestJdbcBridge, local, 10L)
            val miss = SnapshotCacheLookup.miss<Long, Payload>().miss.shouldNotBeNull()
            stageSnapshotMutation(
                this,
                TestJdbcBridge,
                local,
                miss,
                CacheSnapshot(Payload("put")),
                CacheSnapshotValueValidator { },
            )
            events.clear()
        }

        events shouldBeEqualTo listOf(
            "failed:verify:1",
            "failed:submit:1",
            "failed:verify:2",
            "failed:submit:2",
            "failed:verify:3",
            "failed:submit:3",
            "never:verify:4",
            "never:submit:4",
            "rejected:verify:5",
            "later:verify:6",
            "later:submit:6",
            "local:invalidate:10",
            "local:put:20",
        )
        failureMap.submittedIds shouldBeEqualTo listOf(listOf(1L), listOf(2L), listOf(3L))
        rejectedMap.submittedIds.shouldBeEqualTo(emptyList())
        laterMap.submittedIds shouldBeEqualTo listOf(listOf(6L))
        pending.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 1, 8, 8, 1, true)
        val failedEvent = failedBuffer.poll().shouldNotBeNull()
        failedEvent shouldBeEqualTo SnapshotCacheFailure(
            SnapshotStoreId("redisson-jdbc", "failed:v1"),
            SnapshotCacheOperation.INVALIDATE,
            SnapshotCacheOutcome.FAILED,
            1,
            SubmissionFailure::class.java.name,
        )
        failedEvent.toString().contains("secret.example").shouldBeFalse()
        neverBuffer.size shouldBeEqualTo 0
        rejectedBuffer.poll() shouldBeEqualTo SnapshotCacheFailure(
            SnapshotStoreId("redisson-jdbc", "rejected:v1"),
            SnapshotCacheOperation.INVALIDATE,
            SnapshotCacheOutcome.REJECTED,
            1,
        )
        laterBuffer.size shouldBeEqualTo 0
        local.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `synchronous fatal submission escapes committed drain without sanitization or later phases`() {
        val events = mutableListOf<String>()
        val quotaRegistry = RedissonInvalidationQuotaRegistry()
        val fatalBuffer = snapshotCacheFailureBuffer(4)
        val laterBuffer = snapshotCacheFailureBuffer(4)
        val fatalError = FatalSubmissionError("redis://secret.example/identifier-1")
        val fatalMap = RecordingLocalMap<Long>("fatal", events).thenThrow(fatalError)
        val laterMap = RecordingLocalMap<Long>("later", events)
        val fatal = testInvalidator(
            "fatal:v1",
            fatalMap,
            RecordingRedissonClient(fatalMap.proxy).proxy,
            quotaRegistry,
            fatalBuffer,
            events,
            "fatal",
        )
        val later = testInvalidator(
            "later-after-fatal:v1",
            laterMap,
            RecordingRedissonClient(laterMap.proxy).proxy,
            quotaRegistry,
            laterBuffer,
            events,
            "later",
        )
        val local = RecordingSnapshotStore(events)

        val thrown = assertFailsWith<FatalSubmissionError> {
            transaction(database()) {
                maxAttempts = 1
                stageInvalidation(fatal, 1L)
                stageInvalidation(later, 2L)
                stageInvalidationMutation(this, TestJdbcBridge, local, 3L)
                events.clear()
            }
        }

        (thrown === fatalError).shouldBeTrue()
        events shouldBeEqualTo listOf("fatal:verify:1", "fatal:submit:1")
        fatal.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        fatalBuffer.size shouldBeEqualTo 0
        laterMap.submittedIds.shouldBeEqualTo(emptyList())
        laterBuffer.size shouldBeEqualTo 0
        local.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `asynchronous fatal completion remains exceptional without a failure event`() {
        val fatalError = FatalSubmissionError("redis://secret.example/identifier-9")
        val exceptional = CompletableFuture<Long>().apply { completeExceptionally(fatalError) }
        val map = RecordingLocalMap<Long>().thenReturn(rFuture(exceptional))
        val buffer = snapshotCacheFailureBuffer(4)
        val invalidator = testInvalidator(
            "async-fatal:v1",
            map,
            RecordingRedissonClient(map.proxy).proxy,
            RedissonInvalidationQuotaRegistry(),
            buffer,
            mutableListOf(),
            "async-fatal",
        )

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(invalidator, 9L)
        }

        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        buffer.size shouldBeEqualTo 0
    }

    @Test
    fun `duplicate exceptional completion records one sanitized event and releases quota once`() {
        val buffer = snapshotCacheFailureBuffer(4)
        val exceptional = CompletableFuture<Long>().apply {
            completeExceptionally(SubmissionFailure("redis://secret.example/identifier-99"))
        }
        val map = RecordingLocalMap<Long>().thenReturn(rFuture(exceptional, duplicateCallback = true))
        val invalidator = testInvalidator(
            namespace = "exceptional:v1",
            map = map,
            client = RecordingRedissonClient(map.proxy).proxy,
            quotaRegistry = RedissonInvalidationQuotaRegistry(),
            failureBuffer = buffer,
            events = mutableListOf(),
            label = "exceptional",
        )

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(invalidator, 99L)
        }

        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        buffer.size shouldBeEqualTo 1
        val failure = buffer.poll().shouldNotBeNull()
        failure shouldBeEqualTo SnapshotCacheFailure(
            SnapshotStoreId("redisson-jdbc", "exceptional:v1"),
            SnapshotCacheOperation.INVALIDATE,
            SnapshotCacheOutcome.FAILED,
            1,
            SubmissionFailure::class.java.name,
        )
        failure.toString().contains("99").shouldBeFalse()
        failure.toString().contains("secret.example").shouldBeFalse()
        buffer.size shouldBeEqualTo 0
    }

    @Test
    fun `caller thread drain consumes actual event and reports observer exception structurally`() {
        val buffer = snapshotCacheFailureBuffer(4)
        val map = RecordingLocalMap<Long>().thenThrow(
            SubmissionFailure("redis://secret.example/identifier-7"),
        )
        val invalidator = testInvalidator(
            namespace = "drain:v1",
            map = map,
            client = RecordingRedissonClient(map.proxy).proxy,
            quotaRegistry = RedissonInvalidationQuotaRegistry(),
            failureBuffer = buffer,
            events = mutableListOf(),
            label = "drain",
        )
        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(invalidator, 7L)
        }
        val caller = Thread.currentThread()
        val observedThread = AtomicReference<Thread>()
        val observedFailure = AtomicReference<SnapshotCacheFailure>()

        val result = buffer.drainTo(
            observer = { failure ->
                observedThread.set(Thread.currentThread())
                observedFailure.set(failure)
                throw ObserverFailure("do not retain this message")
            },
        )

        (observedThread.get() === caller).shouldBeTrue()
        observedFailure.get().shouldNotBeNull().storeId shouldBeEqualTo SnapshotStoreId("redisson-jdbc", "drain:v1")
        result.deliveredCount shouldBeEqualTo 0
        result.observerFailedCount shouldBeEqualTo 1
        result.remainingCount shouldBeEqualTo 0
        result.observerExceptionType shouldBeEqualTo ObserverFailure::class.java.name
        buffer.size shouldBeEqualTo 0
        buffer.observerFailureCount shouldBeEqualTo 1L
    }

    private fun fixture(
        namespace: String,
        maxCommitEncodedKeyBytes: Int = 256,
    ): Fixture {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = SnapshotCacheConfig(namespace, "payload-v1", 32, 4),
            nearCacheMaximumSize = 16,
            maxEncodedKeyBytes = Long.SIZE_BYTES,
            maxBatchEncodedKeyBytes = minOf(Long.SIZE_BYTES * 4, maxCommitEncodedKeyBytes),
            maxCommitEncodedKeyBytes = maxCommitEncodedKeyBytes,
            maxOutstandingChunks = 4,
            maxOutstandingEncodedBytes = 256,
        )
        return Fixture(
            invalidator = jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                Payload::class,
                config,
            ),
            map = map,
        )
    }

    private fun testInvalidator(
        namespace: String,
        map: RecordingLocalMap<Long>,
        client: RedissonClient,
        quotaRegistry: RedissonInvalidationQuotaRegistry,
        failureBuffer: SnapshotCacheFailureBuffer,
        events: MutableList<String>,
        label: String,
    ): JdbcRedissonSnapshotInvalidator<Long> {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = SnapshotCacheConfig(namespace, "payload-v1", 32, 8),
            nearCacheMaximumSize = 16,
            maxEncodedKeyBytes = Long.SIZE_BYTES,
            maxBatchEncodedKeyBytes = Long.SIZE_BYTES,
            maxCommitEncodedKeyBytes = 256,
            maxOutstandingChunks = 1,
            maxOutstandingEncodedBytes = Long.SIZE_BYTES.toLong(),
        )
        return JdbcRedissonSnapshotInvalidator(
            localCacheMap = map.proxy,
            codec = codec,
            idType = Long::class,
            valueType = Payload::class,
            config = config,
            quota = quotaRegistry.quotaFor(
                client,
                config.maxOutstandingChunks,
                config.maxOutstandingEncodedBytes,
            ),
            failureBuffer = failureBuffer,
            storeInstanceToken = Any(),
            identifierEncoder = { id ->
                events += "$label:verify:$id"
                codec.encodeSnapshotIdentifier(id)
            },
        )
    }

    private fun database(useNestedTransactions: Boolean = false): Database = Database.connect(
        url = "jdbc:h2:mem:redisson-transaction-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        databaseConfig = DatabaseConfig { this.useNestedTransactions = useNestedTransactions },
    )

    private data class Fixture(
        val invalidator: JdbcRedissonSnapshotInvalidator<Long>,
        val map: RecordingLocalMap<Long>,
    )

    private class RecordingSnapshotStore(
        private val events: MutableList<String>,
    ) : SnapshotCacheStore<Long, Payload> {
        override val storeId: SnapshotStoreId = SnapshotStoreId("local-test", "local:v1")
        override val storeInstanceToken: Any = this
        override val compatibilityFingerprint: String = "local-payload-v1"
        override val limits: SnapshotCacheLimits = SnapshotCacheLimits(32, 8)
        override val failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(4)

        override fun claimMiss(miss: io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss<Long, Payload>) =
            ClaimedSnapshotMiss<Long, Payload> { snapshot ->
                SnapshotCacheMutation.Put(20L, snapshot, estimatedWeight = 0L)
            }

        override fun applySnapshots(
            snapshots: List<SnapshotCacheMutation.Put<Long, Payload>>,
            deadline: SnapshotCacheDeadline,
        ) = successReport(SnapshotCacheOperation.PUT, snapshots.map { it.id }, "put")

        override fun applyInvalidations(
            ids: List<Long>,
            deadline: SnapshotCacheDeadline,
        ) = successReport(SnapshotCacheOperation.INVALIDATE, ids, "invalidate")

        private fun successReport(
            operation: SnapshotCacheOperation,
            ids: List<Long>,
            phase: String,
        ): io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport {
            events += ids.map { "local:$phase:$it" }
            return io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport(
                listOf(SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.SUCCESS, ids.size)),
            )
        }
    }

    private object TestJdbcBridge : SnapshotTransactionBridge<JdbcTransaction> {
        override fun isRoot(transaction: JdbcTransaction): Boolean = transaction.outerTransaction == null

        override fun isCurrent(transaction: JdbcTransaction): Boolean =
            transaction.transactionManager.currentOrNull() === transaction

        override fun maxAttempts(transaction: JdbcTransaction): Int = transaction.maxAttempts

        override fun registerInterceptor(transaction: JdbcTransaction, interceptor: StatementInterceptor) {
            transaction.registerInterceptor(interceptor)
        }
    }

    private class RecordingRedissonClient(localCacheMap: RLocalCachedMap<*, *>) {
        val options = mutableListOf<Any>()
        val proxy: RedissonClient = Proxy.newProxyInstance(
            RedissonClient::class.java.classLoader,
            arrayOf(RedissonClient::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getLocalCachedMap" -> {
                    options += args.orEmpty().single()
                    localCacheMap
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingRedissonClient"
                else -> error("Unexpected RedissonClient call: ${method.name}")
            }
        } as RedissonClient
    }

    private class RecordingLocalMap<ID : Any>(
        private val label: String? = null,
        private val events: MutableList<String>? = null,
    ) {
        private val behaviors = ArrayDeque<() -> RFuture<Long>>()
        val submittedIds = mutableListOf<List<ID>>()
        val invokedMethods = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val proxy: RLocalCachedMap<ID, Any?> = Proxy.newProxyInstance(
            RLocalCachedMap::class.java.classLoader,
            arrayOf(RLocalCachedMap::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "fastRemoveAsync" -> {
                    invokedMethods += method.name
                    val ids = (args.orEmpty().single() as Array<*>).map { it as ID }
                    submittedIds += ids
                    label?.let { events?.add("$it:submit:${ids.joinToString()}") }
                    if (behaviors.isEmpty()) completedRFuture(1L) else behaviors.removeFirst()()
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingLocalMap"
                else -> error("Non-invalidation Redisson map command invoked: ${method.name}")
            }
        } as RLocalCachedMap<ID, Any?>

        fun thenReturn(future: RFuture<Long>): RecordingLocalMap<ID> = apply {
            behaviors += { future }
        }

        fun thenThrow(failure: Throwable): RecordingLocalMap<ID> = apply {
            behaviors += { throw failure }
        }
    }

    private data class Payload(val value: String = "value") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class RollbackMarker : RuntimeException()
    private class SubmissionFailure(message: String? = null) : RuntimeException(message)
    private class ObserverFailure(message: String) : RuntimeException(message)
    private class FatalSubmissionError(message: String) : Error(message)

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun rFuture(
            delegate: CompletableFuture<Long>,
            duplicateCallback: Boolean = false,
        ): RFuture<Long> {
            lateinit var proxy: RFuture<Long>
            proxy = Proxy.newProxyInstance(
                RFuture::class.java.classLoader,
                arrayOf(RFuture::class.java),
            ) { instance, method, args ->
                when (method.name) {
                    "whenComplete" -> {
                        val callback = args.orEmpty().single() as java.util.function.BiConsumer<Long?, Throwable?>
                        delegate.whenComplete { value, failure ->
                            callback.accept(value, failure)
                            if (duplicateCallback) callback.accept(value, failure)
                        }
                        proxy
                    }
                    "equals" -> instance === args.orEmpty().singleOrNull()
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "RFutureProxy"
                    else -> method.invoke(delegate, *args.orEmpty())
                }
            } as RFuture<Long>
            return proxy
        }

        @Suppress("UNCHECKED_CAST")
        fun completedRFuture(value: Long): RFuture<Long> = Proxy.newProxyInstance(
            RFuture::class.java.classLoader,
            arrayOf(RFuture::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "whenComplete" -> {
                    val callback = args.orEmpty().single() as java.util.function.BiConsumer<Long?, Throwable?>
                    callback.accept(value, null)
                    instance
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "CompletedRFuture"
                else -> method.invoke(CompletableFuture.completedFuture(value), *args.orEmpty())
            }
        } as RFuture<Long>
    }
}
