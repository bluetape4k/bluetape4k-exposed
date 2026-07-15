@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.TransactionManagerApi
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class SnapshotTransactionCoordinatorTest {

    @Test
    fun `staging accepts only current root open transactions and registers once`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore()

        stageInvalidationMutation(transaction, bridge, store, 1L)
        stageInvalidationMutation(transaction, bridge, store, 2L)

        bridge.interceptors.size shouldBeEqualTo 1
        bridge.current = false
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, store, 3L)
        }

        val captured = TestTransaction()
        val capturedBridge = TestBridge(current = false)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(captured, capturedBridge, store, 1L)
        }

        val nested = TestTransaction(outerTransaction = transaction)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(nested, TestBridge(), store, 1L)
        }

        bridge.current = true
        bridge.interceptor().beforeCommit(transaction)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, store, 4L)
        }
    }

    @Test
    fun `snapshot fill rejects retries before claiming and validates before mapper`() {
        val transaction = TestTransaction()
        val retryBridge = TestBridge(maxAttempts = 2)
        val store = RecordingStore(preparedId = 1L)
        val miss = SnapshotCacheLookup.miss<Long, Payload>().miss ?: error("Expected miss")

        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(transaction, retryBridge, store, miss, CacheSnapshot(Payload("one")), VALIDATOR)
        }
        store.claimCount shouldBeEqualTo 0

        val nonCurrentBridge = TestBridge(current = false)
        var mapperCalled = false
        assertFailsWith<IllegalStateException> {
            stageMappedSnapshotMutation(
                TestTransaction(),
                nonCurrentBridge,
                store,
                miss,
                Payload("source"),
                CacheSnapshotMapper {
                    mapperCalled = true
                    CacheSnapshot(it)
                },
                VALIDATOR,
            )
        }
        mapperCalled.shouldBeFalse()
    }

    @Test
    fun `last mutation wins without moving first insertion position`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore()

        stageInvalidationMutation(transaction, bridge, store, 1L)
        stageInvalidationMutation(transaction, bridge, store, 2L)
        stageInvalidationMutation(transaction, bridge, store, 1L)
        bridge.commit(transaction)

        store.invalidations shouldBeEqualTo listOf(listOf(1L, 2L))
    }

    @Test
    fun `rollback clears staged work and post boundary staging fails`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore()
        stageInvalidationMutation(transaction, bridge, store, 1L)

        bridge.rollback(transaction)

        store.invalidations.shouldBeEqualTo(emptyList())
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, store, 2L)
        }
    }

    @Test
    fun `participant limits and replacement weight are atomic minima`() {
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheMutation.Put(1L, CacheSnapshot(Payload("invalid")), estimatedWeight = -1L)
        }
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore(
            preparedId = 1L,
            preparedWeights = ArrayDeque(listOf(6L, 4L)),
            limits = SnapshotCacheLimits(1, 1, maxStagedWeight = 6L),
        )

        stageSnapshotMutation(transaction, bridge, store, miss(), CacheSnapshot(Payload("heavy")), VALIDATOR)
        stageSnapshotMutation(transaction, bridge, store, miss(), CacheSnapshot(Payload("light")), VALIDATOR)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, store, 2L)
        }
        bridge.commit(transaction)

        store.puts.single().single().snapshot.value shouldBeEqualTo Payload("light")
        store.puts.single().single().estimatedWeight shouldBeEqualTo 4L
    }

    @Test
    fun `lower candidate limits reject without changing prior participant or buffer`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val first = RecordingStore(storeId = SnapshotStoreId("local", "first:v1"))
        val second = RecordingStore(
            storeId = SnapshotStoreId("local", "second:v1"),
            limits = SnapshotCacheLimits(1, 1),
        )

        stageInvalidationMutation(transaction, bridge, first, 1L)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, second, 2L)
        }
        bridge.commit(transaction)

        first.invalidations shouldBeEqualTo listOf(listOf(1L))
        second.invalidations.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `weighted participant rejects an existing put whose prepared weight is unknown`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val unweighted = RecordingStore(
            storeId = SnapshotStoreId("local", "unweighted:v1"),
            preparedId = 1L,
        )
        val weighted = RecordingStore(
            storeId = SnapshotStoreId("local", "weighted:v1"),
            limits = SnapshotCacheLimits(10, 2, maxStagedWeight = 100L),
        )

        stageSnapshotMutation(transaction, bridge, unweighted, miss(), CacheSnapshot(Payload("one")), VALIDATOR)
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, weighted, 2L)
        }
        bridge.commit(transaction)

        unweighted.puts.single().single().snapshot.value shouldBeEqualTo Payload("one")
        weighted.invalidations.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `logical store collision rejects token or fingerprint mismatch before claim`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val storeId = SnapshotStoreId("local", "orders:v1")
        val token = Any()
        val first = RecordingStore(storeId = storeId, token = token, fingerprint = "v1")
        val wrongToken = RecordingStore(storeId = storeId, token = Any(), fingerprint = "v1", preparedId = 2L)
        val wrongFingerprint = RecordingStore(storeId = storeId, token = token, fingerprint = "v2", preparedId = 3L)

        stageInvalidationMutation(transaction, bridge, first, 1L)
        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(transaction, bridge, wrongToken, miss(), CacheSnapshot(Payload("two")), VALIDATOR)
        }
        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(
                transaction,
                bridge,
                wrongFingerprint,
                miss(),
                CacheSnapshot(Payload("three")),
                VALIDATOR,
            )
        }

        wrongToken.claimCount shouldBeEqualTo 0
        wrongFingerprint.claimCount shouldBeEqualTo 0
    }

    @Test
    fun `commit drains distributed then local invalidations then local puts and continues after exceptions`() {
        val events = mutableListOf<String>()
        val failures = snapshotCacheFailureBuffer(16)
        val coordinator = SnapshotTransactionCoordinator(failures)
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val asyncFailing = RecordingAsyncStore("remote-fail", events, immediateFailure = CancellationException())
        val asyncNext = RecordingAsyncStore("remote-next", events)
        val localFailing = RecordingStore(
            storeId = SnapshotStoreId("local", "fail:v1"),
            events = events,
            invalidationFailure = IllegalStateException("sensitive"),
        )
        val localNext = RecordingStore(
            storeId = SnapshotStoreId("local", "next:v1"),
            preparedId = 4L,
            events = events,
        )

        coordinator.stageInvalidation(transaction, bridge, asyncFailing, 1L)
        coordinator.stageInvalidation(transaction, bridge, asyncNext, 2L)
        coordinator.stageInvalidation(transaction, bridge, localFailing, 3L)
        coordinator.stageSnapshot(transaction, bridge, localNext, miss(), CacheSnapshot(Payload("four")), VALIDATOR)
        bridge.commit(transaction)

        events shouldBeEqualTo listOf("async:remote-fail", "async:remote-next", "invalidate:fail:v1", "put:next:v1")
        failures.size shouldBeEqualTo 2
        failures.poll()?.exceptionType shouldBeEqualTo CancellationException::class.java.name
        failures.poll()?.exceptionType shouldBeEqualTo IllegalStateException::class.java.name
    }

    @Test
    fun `one shared deadline skips later local store after cooperative overrun`() {
        var now = 0L
        val failures = snapshotCacheFailureBuffer(8)
        val coordinator = SnapshotTransactionCoordinator(failures) { now }
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val first = RecordingStore(
            storeId = SnapshotStoreId("local", "first:v1"),
            limits = SnapshotCacheLimits(10, 2, localDrainBudget = Duration.ofNanos(5)),
            afterInvalidation = { now = 6L },
        )
        val second = RecordingStore(
            storeId = SnapshotStoreId("local", "second:v1"),
            limits = SnapshotCacheLimits(10, 2, localDrainBudget = Duration.ofNanos(10)),
        )

        coordinator.stageInvalidation(transaction, bridge, first, 1L)
        coordinator.stageInvalidation(transaction, bridge, second, 2L)
        bridge.commit(transaction)

        first.invalidations shouldBeEqualTo listOf(listOf(1L))
        second.invalidations.shouldBeEqualTo(emptyList())
        failures.poll()?.outcome shouldBeEqualTo SnapshotCacheOutcome.NOT_ATTEMPTED
    }

    @Test
    fun `malformed report is isolated but fatal store error escapes`() {
        val failures = snapshotCacheFailureBuffer(8)
        val coordinator = SnapshotTransactionCoordinator(failures)
        val malformedTransaction = TestTransaction()
        val malformedBridge = TestBridge()
        val malformed = RecordingStore(malformedReport = true)
        coordinator.stageInvalidation(malformedTransaction, malformedBridge, malformed, 1L)

        malformedBridge.commit(malformedTransaction)
        failures.poll()?.outcome shouldBeEqualTo SnapshotCacheOutcome.FAILED

        val fatalTransaction = TestTransaction()
        val fatalBridge = TestBridge()
        val fatal = RecordingStore(fatalError = StoreFatalError())
        coordinator.stageInvalidation(fatalTransaction, fatalBridge, fatal, 2L)
        assertFailsWith<StoreFatalError> { fatalBridge.commit(fatalTransaction) }
    }

    @Test
    fun `transaction state is cleaned before cache callbacks and skipped after commit retains no strong guard`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        lateinit var store: RecordingStore
        store = RecordingStore(
            afterInvalidation = {
                assertFailsWith<IllegalStateException> {
                    stageInvalidationMutation(transaction, bridge, store, 2L)
                }
            },
        )
        stageInvalidationMutation(transaction, bridge, store, 1L)
        bridge.commit(transaction)

        val skippedBridge = TestBridge()
        val weak = stageWithoutCompletion(skippedBridge, store)
        repeat(20) {
            System.gc()
            if (weak.get() == null) return@repeat
            ByteArray(128 * 1024)
        }
        weak.get() shouldBeEqualTo null
        store.invalidations.flatten() shouldBeEqualTo listOf(1L)
    }

    @Test
    fun `earlier third party after commit failure skips cache drain`() {
        val failures = snapshotCacheFailureBuffer(4)
        val coordinator = SnapshotTransactionCoordinator(failures)
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore()
        coordinator.stageInvalidation(transaction, bridge, store, 1L)
        val coordinatorInterceptor = bridge.interceptor()
        coordinatorInterceptor.beforeCommit(transaction)
        val earlierInterceptor = object : StatementInterceptor {
            override fun afterCommit(transaction: Transaction) {
                throw ThirdPartyFailure()
            }
        }

        assertFailsWith<ThirdPartyFailure> {
            listOf(earlierInterceptor, coordinatorInterceptor).forEach { it.afterCommit(transaction) }
        }

        store.invalidations.shouldBeEqualTo(emptyList())
        failures.size shouldBeEqualTo 0
    }

    private fun stageWithoutCompletion(
        bridge: TestBridge,
        store: RecordingStore,
    ): WeakReference<TestTransaction> {
        val transaction = TestTransaction()
        val weak = WeakReference(transaction)
        stageInvalidationMutation(transaction, bridge, store, 3L)
        bridge.interceptor().beforeCommit(transaction)
        return weak
    }

    private fun miss(): SnapshotCacheMiss<Long, Payload> =
        SnapshotCacheLookup.miss<Long, Payload>().miss ?: error("Expected miss")

    private class TestBridge(
        var current: Boolean = true,
        private val root: Boolean = true,
        private val maxAttempts: Int = 1,
    ) : SnapshotTransactionBridge<TestTransaction> {
        val interceptors = mutableListOf<StatementInterceptor>()

        override fun isRoot(transaction: TestTransaction): Boolean = root && transaction.outerTransaction == null

        override fun isCurrent(transaction: TestTransaction): Boolean = current

        override fun maxAttempts(transaction: TestTransaction): Int = maxAttempts

        override fun registerInterceptor(transaction: TestTransaction, interceptor: StatementInterceptor) {
            interceptors += interceptor
        }

        fun interceptor(): StatementInterceptor = interceptors.single()

        fun commit(transaction: TestTransaction) {
            interceptor().beforeCommit(transaction)
            interceptor().afterCommit(transaction)
        }

        fun rollback(transaction: TestTransaction) {
            interceptor().beforeRollback(transaction)
            interceptor().afterRollback(transaction)
        }
    }

    private class TestTransaction(
        override val outerTransaction: Transaction? = null,
    ) : Transaction() {
        override val db: DatabaseApi
            get() = error("Database is not used by coordinator tests")
        override val transactionManager: TransactionManagerApi
            get() = error("Transaction manager is not used by coordinator tests")
        override val readOnly: Boolean = false
    }

    private class RecordingStore(
        override val storeId: SnapshotStoreId = SnapshotStoreId("local", "orders:v1"),
        private val preparedId: Long = 1L,
        private val preparedWeights: ArrayDeque<Long?> = ArrayDeque(),
        private val events: MutableList<String> = mutableListOf(),
        override val storeInstanceToken: Any = Any(),
        override val compatibilityFingerprint: String = "local:v1",
        override val limits: SnapshotCacheLimits = SnapshotCacheLimits(10, 4),
        private val invalidationFailure: Exception? = null,
        private val malformedReport: Boolean = false,
        private val fatalError: Error? = null,
        private val afterInvalidation: () -> Unit = {},
    ) : SnapshotCacheStore<Long, Payload> {
        constructor(
            storeId: SnapshotStoreId,
            token: Any,
            fingerprint: String,
            preparedId: Long = 1L,
        ) : this(
            storeId = storeId,
            preparedId = preparedId,
            storeInstanceToken = token,
            compatibilityFingerprint = fingerprint,
        )

        var claimCount = 0
        val puts = mutableListOf<List<SnapshotCacheMutation.Put<Long, Payload>>>()
        val invalidations = mutableListOf<List<Long>>()

        override fun claimMiss(miss: SnapshotCacheMiss<Long, Payload>): ClaimedSnapshotMiss<Long, Payload> {
            claimCount++
            return ClaimedSnapshotMiss { snapshot ->
                SnapshotCacheMutation.Put(
                    id = preparedId,
                    snapshot = snapshot,
                    estimatedWeight = if (preparedWeights.isEmpty()) null else preparedWeights.removeFirst(),
                )
            }
        }

        override fun applySnapshots(
            snapshots: List<SnapshotCacheMutation.Put<Long, Payload>>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport {
            events += "put:${storeId.namespace}"
            puts += snapshots
            return successReport(SnapshotCacheOperation.PUT, snapshots.size)
        }

        override fun applyInvalidations(ids: List<Long>, deadline: SnapshotCacheDeadline): SnapshotCacheApplyReport {
            events += "invalidate:${storeId.namespace}"
            fatalError?.let { throw it }
            invalidationFailure?.let { throw it }
            invalidations += ids
            afterInvalidation()
            return if (malformedReport) successReport(SnapshotCacheOperation.PUT, ids.size)
            else successReport(SnapshotCacheOperation.INVALIDATE, ids.size)
        }
    }

    private class RecordingAsyncStore(
        name: String,
        private val events: MutableList<String>,
        private val immediateFailure: Exception? = null,
    ) : AsyncSnapshotInvalidationStore<Long> {
        override val storeId = SnapshotStoreId("remote", "$name:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "remote:v1"
        override val limits = SnapshotCacheLimits(10, 4)

        override fun measure(id: Long): MeasuredInvalidation<Long> =
            MeasuredInvalidation(id, Long.SIZE_BYTES, "a".repeat(64))

        override fun submitInvalidation(
            batch: List<MeasuredInvalidation<Long>>,
        ): CompletionStage<SnapshotCacheApplyReport> {
            events += "async:${storeId.namespace.removeSuffix(":v1")}"
            immediateFailure?.let { throw it }
            return CompletableFuture.completedFuture(successReport(SnapshotCacheOperation.INVALIDATE, batch.size))
        }
    }

    private data class Payload(val text: String) : Serializable

    private class StoreFatalError : Error()

    private class ThirdPartyFailure : RuntimeException()

    companion object {
        private val VALIDATOR = CacheSnapshotValueValidator<Payload> {}

        private fun successReport(operation: SnapshotCacheOperation, count: Int) =
            SnapshotCacheApplyReport(
                listOf(SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.SUCCESS, count)),
            )
    }
}
