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
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
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
    fun `snapshot fill rejects retries before registering or claiming and validates transaction before mapper`() {
        val transaction = TestTransaction()
        val retryBridge = TestBridge(maxAttempts = 2)
        val store = RecordingStore(preparedId = 1L)
        val miss = SnapshotCacheLookup.miss<Long, Payload>().miss ?: error("Expected miss")

        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(transaction, retryBridge, store, miss, CacheSnapshot(Payload("one")), VALIDATOR)
        }
        store.claimCount shouldBeEqualTo 0
        retryBridge.interceptors.size shouldBeEqualTo 0

        val validBridge = TestBridge()
        stageInvalidationMutation(transaction, validBridge, store, 1L)
        validBridge.interceptors.size shouldBeEqualTo 1

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
    fun `mapper and validator failures consume the miss token`() {
        val mapperTransaction = TestTransaction()
        val mapperBridge = TestBridge()
        val mapperStore = RecordingStore()
        val mapperMiss = miss()

        assertFailsWith<MapperFailure> {
            stageMappedSnapshotMutation(
                mapperTransaction,
                mapperBridge,
                mapperStore,
                mapperMiss,
                Payload("source"),
                CacheSnapshotMapper { throw MapperFailure() },
                VALIDATOR,
            )
        }
        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(
                mapperTransaction,
                mapperBridge,
                mapperStore,
                mapperMiss,
                CacheSnapshot(Payload("retry")),
                VALIDATOR,
            )
        }

        val validatorTransaction = TestTransaction()
        val validatorBridge = TestBridge()
        val validatorStore = RecordingStore()
        val validatorMiss = miss()
        assertFailsWith<ValidationFailure> {
            stageSnapshotMutation(
                validatorTransaction,
                validatorBridge,
                validatorStore,
                validatorMiss,
                CacheSnapshot(Payload("invalid")),
                CacheSnapshotValueValidator { throw ValidationFailure() },
            )
        }
        assertFailsWith<IllegalStateException> {
            stageSnapshotMutation(
                validatorTransaction,
                validatorBridge,
                validatorStore,
                validatorMiss,
                CacheSnapshot(Payload("retry")),
                VALIDATOR,
            )
        }

        mapperStore.claimCount shouldBeEqualTo 2
        validatorStore.claimCount shouldBeEqualTo 2
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
    fun `logical store collision rejects token fingerprint or buffer mismatch before mutation`() {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val storeId = SnapshotStoreId("local", "orders:v1")
        val token = Any()
        val first = RecordingStore(storeId = storeId, token = token, fingerprint = "v1")
        val wrongToken = RecordingStore(storeId = storeId, token = Any(), fingerprint = "v1", preparedId = 2L)
        val wrongFingerprint = RecordingStore(storeId = storeId, token = token, fingerprint = "v2", preparedId = 3L)
        val wrongFailureBuffer = RecordingStore(
            storeId = storeId,
            token = token,
            fingerprint = "v1",
            failureBuffer = snapshotCacheFailureBuffer(),
        )

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
        assertFailsWith<IllegalStateException> {
            stageInvalidationMutation(transaction, bridge, wrongFailureBuffer, 4L)
        }

        wrongToken.claimCount shouldBeEqualTo 0
        wrongFingerprint.claimCount shouldBeEqualTo 0
        bridge.commit(transaction)
        first.invalidations shouldBeEqualTo listOf(listOf(1L))
        wrongFailureBuffer.invalidations.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `commit drains distributed then local invalidations then local puts and continues after exceptions`() {
        val events = mutableListOf<String>()
        val failures = snapshotCacheFailureBuffer(16)
        val coordinator = SnapshotTransactionCoordinator()
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val asyncFailing = RecordingAsyncStore(
            "remote-fail",
            events,
            failureBuffer = failures,
            immediateFailure = CancellationException(),
        )
        val asyncNext = RecordingAsyncStore("remote-next", events, failureBuffer = failures)
        val localFailing = RecordingStore(
            storeId = SnapshotStoreId("local", "fail:v1"),
            events = events,
            failureBuffer = failures,
            invalidationFailure = IllegalStateException("sensitive"),
        )
        val localNext = RecordingStore(
            storeId = SnapshotStoreId("local", "next:v1"),
            preparedId = 4L,
            events = events,
            failureBuffer = failures,
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
        val coordinator = SnapshotTransactionCoordinator { now }
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val first = RecordingStore(
            storeId = SnapshotStoreId("local", "first:v1"),
            limits = SnapshotCacheLimits(10, 2, localDrainBudget = Duration.ofNanos(5)),
            failureBuffer = failures,
            afterInvalidation = { now = 6L },
        )
        val second = RecordingStore(
            storeId = SnapshotStoreId("local", "second:v1"),
            limits = SnapshotCacheLimits(10, 2, localDrainBudget = Duration.ofNanos(10)),
            failureBuffer = failures,
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
        val coordinator = SnapshotTransactionCoordinator()
        val malformedTransaction = TestTransaction()
        val malformedBridge = TestBridge()
        val malformed = RecordingStore(failureBuffer = failures, malformedReport = true)
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
    fun `caller supplied buffer identity is preserved and may be shared by participants`() {
        val failures = snapshotCacheFailureBuffer(4)
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val first = RecordingStore(
            storeId = SnapshotStoreId("local", "first:v1"),
            failureBuffer = failures,
            invalidationFailure = IllegalStateException(),
        )
        val second = RecordingStore(
            storeId = SnapshotStoreId("local", "second:v1"),
            failureBuffer = failures,
            invalidationFailure = IllegalArgumentException(),
        )

        (first.failureBuffer === failures).shouldBeTrue()
        (second.failureBuffer === failures).shouldBeTrue()
        stageInvalidationMutation(transaction, bridge, first, 1L)
        stageInvalidationMutation(transaction, bridge, second, 2L)
        bridge.commit(transaction)

        failures.size shouldBeEqualTo 2
        failures.poll()?.storeId shouldBeEqualTo first.storeId
        failures.poll()?.storeId shouldBeEqualTo second.storeId
    }

    @Test
    fun `asynchronous completion is observed without waiting and reports every non-success outcome`() {
        val failures = snapshotCacheFailureBuffer(8)
        val completion = CompletableFuture<SnapshotCacheApplyReport>()
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingAsyncStore(
            "outcomes",
            mutableListOf(),
            failureBuffer = failures,
            completion = completion,
        )
        repeat(4) { index ->
            stageInvalidationMutation(transaction, bridge, store, index.toLong())
        }

        bridge.commit(transaction)
        failures.size shouldBeEqualTo 0

        completion.complete(
            SnapshotCacheApplyReport(
                listOf(
                    SnapshotCacheOperationResult(
                        SnapshotCacheOperation.INVALIDATE,
                        SnapshotCacheOutcome.SUCCESS,
                        1,
                    ),
                    SnapshotCacheOperationResult(
                        SnapshotCacheOperation.INVALIDATE,
                        SnapshotCacheOutcome.FAILED,
                        1,
                        IllegalStateException::class.java.name,
                    ),
                    SnapshotCacheOperationResult(
                        SnapshotCacheOperation.INVALIDATE,
                        SnapshotCacheOutcome.REJECTED,
                        1,
                    ),
                    SnapshotCacheOperationResult(
                        SnapshotCacheOperation.INVALIDATE,
                        SnapshotCacheOutcome.NOT_ATTEMPTED,
                        1,
                    ),
                ),
            ),
        ).shouldBeTrue()

        failures.size shouldBeEqualTo 3
        failures.poll()?.outcome shouldBeEqualTo SnapshotCacheOutcome.FAILED
        failures.poll()?.outcome shouldBeEqualTo SnapshotCacheOutcome.REJECTED
        failures.poll()?.outcome shouldBeEqualTo SnapshotCacheOutcome.NOT_ATTEMPTED
    }

    @Test
    fun `asynchronous exception cancellation and malformed completion are isolated in responsible buffers`() {
        val exceptionalFailures = snapshotCacheFailureBuffer(2)
        val cancellationFailures = snapshotCacheFailureBuffer(2)
        val malformedFailures = snapshotCacheFailureBuffer(2)
        val exceptionalCompletion = CompletableFuture<SnapshotCacheApplyReport>()
        val cancelledCompletion = CompletableFuture<SnapshotCacheApplyReport>()
        val malformedCompletion = CompletableFuture<SnapshotCacheApplyReport>()

        val exceptionalTransaction = TestTransaction()
        val exceptionalBridge = TestBridge()
        val exceptionalStore = RecordingAsyncStore(
            "exceptional",
            mutableListOf(),
            failureBuffer = exceptionalFailures,
            completion = exceptionalCompletion,
        )
        stageInvalidationMutation(exceptionalTransaction, exceptionalBridge, exceptionalStore, 0L)
        exceptionalBridge.commit(exceptionalTransaction)
        exceptionalCompletion.completeExceptionally(IllegalStateException()).shouldBeTrue()

        val cancelledTransaction = TestTransaction()
        val cancelledBridge = TestBridge()
        val cancelledStore = RecordingAsyncStore(
            "cancelled",
            mutableListOf(),
            failureBuffer = cancellationFailures,
            completion = cancelledCompletion,
        )
        stageInvalidationMutation(cancelledTransaction, cancelledBridge, cancelledStore, 1L)
        cancelledBridge.commit(cancelledTransaction)
        cancelledCompletion.cancel(false).shouldBeTrue()

        val malformedTransaction = TestTransaction()
        val malformedBridge = TestBridge()
        val malformedStore = RecordingAsyncStore(
            "malformed",
            mutableListOf(),
            failureBuffer = malformedFailures,
            completion = malformedCompletion,
        )
        stageInvalidationMutation(malformedTransaction, malformedBridge, malformedStore, 2L)
        malformedBridge.commit(malformedTransaction)
        malformedCompletion.complete(successReport(SnapshotCacheOperation.PUT, 1)).shouldBeTrue()

        exceptionalFailures.poll()?.exceptionType shouldBeEqualTo IllegalStateException::class.java.name
        cancellationFailures.poll()?.exceptionType shouldBeEqualTo CancellationException::class.java.name
        malformedFailures.poll()?.exceptionType shouldBeEqualTo IllegalArgumentException::class.java.name
        exceptionalFailures.size shouldBeEqualTo 0
        cancellationFailures.size shouldBeEqualTo 0
        malformedFailures.size shouldBeEqualTo 0
    }

    @Test
    fun `asynchronous fatal error remains exceptional and never becomes a failure event`() {
        val failures = snapshotCacheFailureBuffer(2)
        val completion = CompletableFuture<SnapshotCacheApplyReport>()
        val observed = observeAsyncCompletion(completion, ASYNC_STORE_ID, failures, 1)
        val fatal = StoreFatalError()

        completion.completeExceptionally(fatal).shouldBeTrue()

        val thrown = assertFailsWith<CompletionException> { observed.toCompletableFuture().join() }
        (thrown.cause === fatal).shouldBeTrue()
        failures.size shouldBeEqualTo 0
    }

    @Test
    fun `never completing async observation does not retain the submitting store`() {
        val completion = CompletableFuture<SnapshotCacheApplyReport>()
        val reclaimedStores = ReferenceQueue<RecordingAsyncStore>()
        val weakStore = stagePendingAsyncInvalidation(completion, reclaimedStores)

        awaitReclamation(weakStore, reclaimedStores).shouldBeTrue()
        completion.isDone.shouldBeFalse()
    }

    @Test
    fun `earlier transaction callbacks remain outside the coordinator boundary start`() {
        val committedStore = RecordingStore()
        val commitTransaction = TestTransaction()
        val commitBridge = TestBridge()
        commitBridge.interceptors += object : StatementInterceptor {
            override fun beforeCommit(transaction: Transaction) {
                stageInvalidationMutation(commitTransaction, commitBridge, committedStore, 2L)
            }
        }
        stageInvalidationMutation(commitTransaction, commitBridge, committedStore, 1L)

        commitBridge.commitAll(commitTransaction)

        committedStore.invalidations shouldBeEqualTo listOf(listOf(1L, 2L))

        val rolledBackStore = RecordingStore()
        val rollbackTransaction = TestTransaction()
        val rollbackBridge = TestBridge()
        rollbackBridge.interceptors += object : StatementInterceptor {
            override fun beforeRollback(transaction: Transaction) {
                stageInvalidationMutation(rollbackTransaction, rollbackBridge, rolledBackStore, 2L)
            }
        }
        stageInvalidationMutation(rollbackTransaction, rollbackBridge, rolledBackStore, 1L)

        rollbackBridge.rollbackAll(rollbackTransaction)

        rolledBackStore.invalidations.shouldBeEqualTo(emptyList())
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
        val reclaimedTransactions = ReferenceQueue<TestTransaction>()
        val weak = stageWithoutCompletion(skippedBridge, store, reclaimedTransactions)
        awaitReclamation(weak, reclaimedTransactions).shouldBeTrue()
        store.invalidations.flatten() shouldBeEqualTo listOf(1L)
    }

    @Test
    fun `earlier third party after commit failure skips cache drain`() {
        val failures = snapshotCacheFailureBuffer(4)
        val coordinator = SnapshotTransactionCoordinator()
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingStore(failureBuffer = failures)
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
        reclaimedTransactions: ReferenceQueue<TestTransaction>,
    ): WeakReference<TestTransaction> {
        val transaction = TestTransaction()
        val weak = WeakReference(transaction, reclaimedTransactions)
        stageInvalidationMutation(transaction, bridge, store, 3L)
        bridge.interceptor().beforeCommit(transaction)
        return weak
    }

    private fun stagePendingAsyncInvalidation(
        completion: CompletionStage<SnapshotCacheApplyReport>,
        reclaimedStores: ReferenceQueue<RecordingAsyncStore>,
    ): WeakReference<RecordingAsyncStore> {
        val transaction = TestTransaction()
        val bridge = TestBridge()
        val store = RecordingAsyncStore(
            "pending",
            mutableListOf(),
            completion = completion,
        )
        val weakStore = WeakReference(store, reclaimedStores)
        stageInvalidationMutation(transaction, bridge, store, 1L)
        bridge.commit(transaction)
        return weakStore
    }

    private fun <T : Any> awaitReclamation(
        reference: WeakReference<T>,
        referenceQueue: ReferenceQueue<T>,
    ): Boolean {
        repeat(64) { attempt ->
            if (referenceQueue.poll() === reference) return true
            val pressure = Array(8) { ByteArray(256 * 1024) }
            pressure[attempt % pressure.size][0] = attempt.toByte()
            System.gc()
        }
        return referenceQueue.poll() === reference
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

        fun commitAll(transaction: TestTransaction) {
            interceptors.forEach { it.beforeCommit(transaction) }
            interceptors.forEach { it.afterCommit(transaction) }
        }

        fun rollbackAll(transaction: TestTransaction) {
            interceptors.forEach { it.beforeRollback(transaction) }
            interceptors.forEach { it.afterRollback(transaction) }
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
        override val failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(16),
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
            failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(16),
        ) : this(
            storeId = storeId,
            preparedId = preparedId,
            storeInstanceToken = token,
            compatibilityFingerprint = fingerprint,
            failureBuffer = failureBuffer,
        )

        var claimCount = 0
        private val claimedMisses = Collections.newSetFromMap(
            IdentityHashMap<SnapshotCacheMiss<Long, Payload>, Boolean>(),
        )
        val puts = mutableListOf<List<SnapshotCacheMutation.Put<Long, Payload>>>()
        val invalidations = mutableListOf<List<Long>>()

        override fun claimMiss(miss: SnapshotCacheMiss<Long, Payload>): ClaimedSnapshotMiss<Long, Payload> {
            claimCount++
            check(claimedMisses.add(miss)) { "Snapshot cache miss has already been claimed." }
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
        override val failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(16),
        private val immediateFailure: Exception? = null,
        private val completion: CompletionStage<SnapshotCacheApplyReport>? = null,
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
            return completion
                ?: CompletableFuture.completedFuture(successReport(SnapshotCacheOperation.INVALIDATE, batch.size))
        }
    }

    private data class Payload(val text: String) : Serializable

    private class StoreFatalError : Error()

    private class MapperFailure : RuntimeException()

    private class ValidationFailure : RuntimeException()

    private class ThirdPartyFailure : RuntimeException()

    companion object {
        private val VALIDATOR = CacheSnapshotValueValidator<Payload> {}
        private val ASYNC_STORE_ID = SnapshotStoreId("remote", "fatal:v1")

        private fun successReport(operation: SnapshotCacheOperation, count: Int) =
            SnapshotCacheApplyReport(
                listOf(SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.SUCCESS, count)),
            )
    }
}
