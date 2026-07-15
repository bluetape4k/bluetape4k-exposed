package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass
import java.io.Serializable
import java.lang.ref.Reference
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(InternalSnapshotCacheApi::class)
class SnapshotCacheStoreTest {

    @Test
    fun `lookup represents exactly one hit or opaque miss`() {
        val snapshot = CacheSnapshot(Payload("cached"), "r-1")
        val hit = SnapshotCacheLookup.hit<Long, Payload>(snapshot)
        val miss = SnapshotCacheLookup.miss<Long, Payload>()

        hit.snapshot shouldBeEqualTo snapshot
        hit.miss.shouldBeNull()
        miss.snapshot.shouldBeNull()
        miss.miss?.toString() shouldBeEqualTo "SnapshotCacheMiss(opaque)"
        Serializable::class.java.isAssignableFrom(miss.miss?.javaClass).shouldBeFalse()
        miss.miss?.javaClass?.declaredFields?.isEmpty()?.shouldBeTrue()
    }

    @Test
    fun `miss capability is weak identity bounded and claimed exactly once`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 1)
        val lookup = registry.register(1L, fences.capture(1L))

        assertFailsWith<IllegalStateException> {
            registry.register(2L, fences.capture(2L))
        }

        val miss = lookup.miss ?: error("Expected miss capability")
        val claimed = registry.claim(miss)
        val mutation = claimed.prepare(CacheSnapshot(Payload("loaded"), "r-2"))

        mutation.id shouldBeEqualTo 1L
        mutation.snapshot shouldBeEqualTo CacheSnapshot(Payload("loaded"), "r-2")
        val localFence = mutation.localFence ?: error("Expected local fence")
        fences.putIfCurrent(mutation.id, localFence) {}.shouldBeTrue()
        assertFailsWith<IllegalStateException> { registry.claim(miss) }
        assertFailsWith<IllegalStateException> {
            claimed.prepare(CacheSnapshot(Payload("loaded-again")))
        }
    }

    @Test
    fun `foreign miss and mapper failure both require a fresh lookup`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 2)
        val foreign = SnapshotCacheLookup.miss<Long, Payload>().miss ?: error("Expected foreign miss")

        assertFailsWith<IllegalStateException> { registry.claim(foreign) }

        val first = registry.register(1L, fences.capture(1L)).miss ?: error("Expected first miss")
        registry.claim(first)
        assertFailsWith<MappingFailure> { throw MappingFailure() }
        assertFailsWith<IllegalStateException> { registry.claim(first) }

        val fresh = registry.register(1L, fences.capture(1L)).miss ?: error("Expected fresh miss")
        registry.claim(fresh).prepare(CacheSnapshot(Payload("fresh"))).id shouldBeEqualTo 1L
    }

    @Test
    fun `queued weak miss releases bounded registry capacity`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 1)
        val retainedLookup = registry.register(1L, fences.capture(1L))

        clearAndEnqueueRegisteredWeakKey(registry)

        val replacement = registry.register(2L, fences.capture(2L))
        replacement.miss?.toString() shouldBeEqualTo "SnapshotCacheMiss(opaque)"
        val retainedMiss = retainedLookup.miss ?: error("Expected retained miss")
        assertFailsWith<IllegalStateException> { registry.claim(retainedMiss) }
    }

    @Test
    fun `simultaneous claim allows exactly one contender`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 1)
        val miss = registry.register(1L, fences.capture(1L)).miss ?: error("Expected registered miss")
        val start = CountDownLatch(1)
        val executor = TrackedExecutor(threadCount = 2)

        try {
            val claimAttempts = List(2) {
                executor.submit {
                    start.await()
                    runCatching { registry.claim(miss) }
                }
            }

            start.countDown()
            val claimResults = claimAttempts.map { it.get(5, TimeUnit.SECONDS) }
            claimResults.count { it.isSuccess } shouldBeEqualTo 1
            val claimFailures = claimResults.mapNotNull { it.exceptionOrNull() }
            claimFailures.size shouldBeEqualTo 1
            claimFailures.single().javaClass shouldBeEqualTo IllegalStateException::class.java
        } finally {
            start.countDown()
            executor.close()
        }
    }

    @Test
    fun `simultaneous prepare allows exactly one contender`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 1)
        val miss = registry.register(1L, fences.capture(1L)).miss ?: error("Expected registered miss")
        val claimed = registry.claim(miss)
        val start = CountDownLatch(1)
        val executor = TrackedExecutor(threadCount = 2)

        try {
            val prepareAttempts = List(2) { contender ->
                executor.submit {
                    start.await()
                    runCatching {
                        claimed.prepare(CacheSnapshot(Payload("loaded-$contender")))
                    }
                }
            }

            start.countDown()
            val prepareResults = prepareAttempts.map { it.get(5, TimeUnit.SECONDS) }
            prepareResults.count { it.isSuccess } shouldBeEqualTo 1
            val prepareFailures = prepareResults.mapNotNull { it.exceptionOrNull() }
            prepareFailures.size shouldBeEqualTo 1
            prepareFailures.single().javaClass shouldBeEqualTo IllegalStateException::class.java
        } finally {
            start.countDown()
            executor.close()
        }
    }

    @Test
    fun `full miss capacity fails before database read`() {
        val fences = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val registry = SnapshotMissCapabilityRegistry<Long, Payload>(maxOutstandingMissTokens = 1)
        val retainedLookup = registry.register(1L, fences.capture(1L))
        val dbReadCount = AtomicInteger()

        assertFailsWith<IllegalStateException> {
            registerMissBeforeDbRead(registry, fences, id = 2L) {
                dbReadCount.incrementAndGet()
            }
        }

        retainedLookup.miss?.toString() shouldBeEqualTo "SnapshotCacheMiss(opaque)"
        dbReadCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `mutation base contract exposes every mutation id`() {
        val mutations: List<SnapshotCacheMutation<Long, Payload>> = listOf(
            SnapshotCacheMutation.Put(1L, CacheSnapshot(Payload("one"))),
            SnapshotCacheMutation.Invalidate(2L),
        )

        mutations.map { it.id } shouldBeEqualTo listOf(1L, 2L)
    }

    @Test
    fun `store identity limits and result models validate caller input`() {
        val limits = SnapshotCacheLimits(
            maxStagedMutations = 10,
            maxParticipatingStores = 2,
            maxStagedWeight = 1_024L,
            localDrainBudget = Duration.ofMillis(50),
        )
        val storeId = SnapshotStoreId("caffeine", "orders:v1")
        val measured = MeasuredInvalidation(1L, 16, "a".repeat(64))
        val encodedBytes: Int = measured.encodedBytes

        limits.maxStagedMutations shouldBeEqualTo 10
        storeId shouldBeEqualTo SnapshotStoreId("caffeine", "orders:v1")
        encodedBytes shouldBeEqualTo 16
        assertFailsWith<IllegalArgumentException> { SnapshotStoreId(" ", "orders:v1") }
        assertFailsWith<IllegalArgumentException> { SnapshotStoreId("caffeine", " ") }
        assertFailsWith<IllegalArgumentException> { SnapshotStoreId("b".repeat(129), "orders:v1") }
        assertFailsWith<IllegalArgumentException> { SnapshotStoreId("caffeine", "n".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { SnapshotCacheLimits(0, 1) }
        assertFailsWith<IllegalArgumentException> { SnapshotCacheLimits(1, 0) }
        assertFailsWith<IllegalArgumentException> { SnapshotCacheLimits(1_000_001, 1) }
        assertFailsWith<IllegalArgumentException> { SnapshotCacheLimits(1, 1_025) }
        assertFailsWith<IllegalArgumentException> { SnapshotCacheLimits(1, 1, maxStagedWeight = 0L) }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheLimits(1, 1, localDrainBudget = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> { MeasuredInvalidation(1L, -1, "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { MeasuredInvalidation(1L, 1, "not-a-sha256") }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheOperationResult(
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.SUCCESS,
                affectedCount = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheOperationResult(
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.FAILED,
                affectedCount = 1,
                exceptionType = " ",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheOperationResult(
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.FAILED,
                affectedCount = 1,
                exceptionType = "e".repeat(513),
            )
        }
    }

    @Test
    fun `serializable store models declare the repository UID`() {
        listOf(
            SnapshotStoreId::class.java,
            SnapshotCacheLimits::class.java,
            SnapshotCacheApplyReport::class.java,
            SnapshotCacheOperationResult::class.java,
        ).forEach { type ->
            ObjectStreamClass.lookup(type).serialVersionUID shouldBeEqualTo 1L
        }
    }

    @Test
    fun `store API exposes one bulk method per phase and reports every outcome`() {
        val store = RecordingStore()
        val snapshots = (1L..4L).map { id ->
            SnapshotCacheMutation.Put(id, CacheSnapshot(Payload("value-$id")))
        }
        val invalidationIds = (5L..8L).toList()
        val deadline = MonotonicSnapshotCacheDeadline(Duration.ofSeconds(1)) { 0L }
        val applyMethods = SnapshotCacheStore::class.java.declaredMethods.filter { it.name.startsWith("apply") }

        val putReport = store.applySnapshots(snapshots, deadline)
        val invalidationReport = store.applyInvalidations(invalidationIds, deadline)

        applyMethods.map { it.name }.sorted() shouldBeEqualTo listOf("applyInvalidations", "applySnapshots")
        applyMethods.all { it.parameterTypes.firstOrNull() == List::class.java }.shouldBeTrue()
        store.snapshotBatches shouldBeEqualTo listOf(snapshots)
        store.invalidationBatches shouldBeEqualTo listOf(invalidationIds)
        putReport.results.map { it.outcome } shouldBeEqualTo PER_INPUT_OUTCOMES
        invalidationReport.results.map { it.outcome } shouldBeEqualTo PER_INPUT_OUTCOMES
        putReport.results.sumOf { it.affectedCount } shouldBeEqualTo snapshots.size
        invalidationReport.results.sumOf { it.affectedCount } shouldBeEqualTo invalidationIds.size
    }

    @Test
    fun `report reconciliation accepts every outcome with an exact long count`() {
        val report = SnapshotCacheApplyReport(
            SnapshotCacheOutcome.entries.mapIndexed { index, outcome ->
                SnapshotCacheOperationResult(
                    operation = SnapshotCacheOperation.PUT,
                    outcome = outcome,
                    affectedCount = if (outcome == SnapshotCacheOutcome.OVERRUN) 0 else index + 1,
                    exceptionType = exceptionType(outcome),
                )
            },
        )

        val expectedCount = report.results.sumOf { it.affectedCount }
        report.requireReconciled(SnapshotCacheOperation.PUT, expectedCount) shouldBeEqualTo report
    }

    @Test
    fun `report reconciliation rejects malformed operation and counts`() {
        val onePut = SnapshotCacheOperationResult(
            SnapshotCacheOperation.PUT,
            SnapshotCacheOutcome.SUCCESS,
            affectedCount = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheApplyReport(listOf(onePut)).requireReconciled(
                SnapshotCacheOperation.PUT,
                expectedCount = -1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheApplyReport(
                listOf(onePut.copy(operation = SnapshotCacheOperation.INVALIDATE)),
            ).requireReconciled(SnapshotCacheOperation.PUT, expectedCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheApplyReport(listOf(onePut)).requireReconciled(
                SnapshotCacheOperation.PUT,
                expectedCount = 2,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheOperationResult(
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.OVERRUN,
                affectedCount = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheApplyReport(listOf(onePut.copy(affectedCount = 2))).requireReconciled(
                SnapshotCacheOperation.PUT,
                expectedCount = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheApplyReport(
                listOf(
                    onePut.copy(affectedCount = Int.MAX_VALUE),
                    onePut.copy(affectedCount = Int.MAX_VALUE),
                ),
            ).requireReconciled(SnapshotCacheOperation.PUT, expectedCount = Int.MAX_VALUE)
        }
    }

    @Test
    fun `shared monotonic deadline expires between bulk entries`() {
        val clock = AtomicLong(0L)
        val sharedDeadline = MonotonicSnapshotCacheDeadline(Duration.ofNanos(5)) {
            clock.addAndGet(3L)
        }
        val store = RecordingStore { _, deadline ->
            if (deadline.isExpired) SnapshotCacheOutcome.NOT_ATTEMPTED else SnapshotCacheOutcome.SUCCESS
        }
        val snapshots = listOf(
            SnapshotCacheMutation.Put(1L, CacheSnapshot(Payload("one"))),
            SnapshotCacheMutation.Put(2L, CacheSnapshot(Payload("two"))),
        )

        val report = store.applySnapshots(snapshots, sharedDeadline)

        store.snapshotBatches shouldBeEqualTo listOf(snapshots)
        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.SUCCESS,
            SnapshotCacheOutcome.NOT_ATTEMPTED,
        )
        sharedDeadline.isExpired.shouldBeTrue()
    }

    @Test
    fun `async invalidation store exposes participant identity and measured report`() {
        val store = RecordingAsyncStore()
        val measured = store.measure(7L)
        val report = store.submitInvalidation(listOf(measured)).toCompletableFuture().join()

        store.storeId shouldBeEqualTo SnapshotStoreId("remote", "orders:v1")
        store.compatibilityFingerprint shouldBeEqualTo "remote:v1"
        measured.id shouldBeEqualTo 7L
        report.results.single().affectedCount shouldBeEqualTo 1
    }

    @Test
    fun `deadline uses injected monotonic nanoseconds`() {
        var now = 100L
        val deadline = MonotonicSnapshotCacheDeadline(Duration.ofNanos(5)) { now }

        deadline.remaining() shouldBeEqualTo Duration.ofNanos(5)
        now = 104L
        deadline.remaining() shouldBeEqualTo Duration.ofNanos(1)
        deadline.isExpired.shouldBeFalse()
        now = 105L
        deadline.remaining() shouldBeEqualTo Duration.ZERO
        deadline.isExpired.shouldBeTrue()
    }

    private class RecordingStore(
        private val outcomeFor: (Int, SnapshotCacheDeadline) -> SnapshotCacheOutcome = { index, _ ->
            PER_INPUT_OUTCOMES[index]
        },
    ) : SnapshotCacheStore<Long, Payload> {
        override val storeId = SnapshotStoreId("local", "orders:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "local:v1"
        override val limits = SnapshotCacheLimits(10, 2)
        override val failureBuffer = snapshotCacheFailureBuffer()
        val snapshotBatches = mutableListOf<List<SnapshotCacheMutation.Put<Long, Payload>>>()
        val invalidationBatches = mutableListOf<List<Long>>()

        override fun claimMiss(miss: SnapshotCacheMiss<Long, Payload>): ClaimedSnapshotMiss<Long, Payload> =
            error("Not used by this store proof")

        override fun applySnapshots(
            snapshots: List<SnapshotCacheMutation.Put<Long, Payload>>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport {
            snapshotBatches += snapshots
            return SnapshotCacheApplyReport(
                snapshots.mapIndexed { index, _ ->
                    val outcome = outcomeFor(index, deadline)
                    SnapshotCacheOperationResult(
                        operation = SnapshotCacheOperation.PUT,
                        outcome = outcome,
                        affectedCount = 1,
                        exceptionType = exceptionType(outcome),
                    )
                },
            ).requireReconciled(SnapshotCacheOperation.PUT, snapshots.size)
        }

        override fun applyInvalidations(
            ids: List<Long>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport {
            invalidationBatches += ids
            return SnapshotCacheApplyReport(
                ids.mapIndexed { index, _ ->
                    val outcome = outcomeFor(index, deadline)
                    SnapshotCacheOperationResult(
                        operation = SnapshotCacheOperation.INVALIDATE,
                        outcome = outcome,
                        affectedCount = 1,
                        exceptionType = exceptionType(outcome),
                    )
                },
            ).requireReconciled(SnapshotCacheOperation.INVALIDATE, ids.size)
        }
    }

    private class RecordingAsyncStore : AsyncSnapshotInvalidationStore<Long> {
        override val storeId = SnapshotStoreId("remote", "orders:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "remote:v1"
        override val limits = SnapshotCacheLimits(10, 2)
        override val failureBuffer = snapshotCacheFailureBuffer()

        override fun measure(id: Long): MeasuredInvalidation<Long> =
            MeasuredInvalidation(id, Long.SIZE_BYTES, "b".repeat(64))

        override fun submitInvalidation(
            batch: List<MeasuredInvalidation<Long>>,
        ): CompletionStage<SnapshotCacheApplyReport> =
            CompletableFuture.completedFuture(
                SnapshotCacheApplyReport(
                    listOf(
                        SnapshotCacheOperationResult(
                            SnapshotCacheOperation.INVALIDATE,
                            SnapshotCacheOutcome.SUCCESS,
                            batch.size,
                        ),
                    ),
                ),
            )
    }

    private data class Payload(val text: String) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class MappingFailure : RuntimeException()

    private fun clearAndEnqueueRegisteredWeakKey(
        registry: SnapshotMissCapabilityRegistry<Long, Payload>,
    ) {
        val capabilitiesField = registry.javaClass.declaredFields.single {
            Map::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val capabilities = capabilitiesField.get(registry) as Map<*, *>
        val weakKey = capabilities.keys.single() as Reference<*>

        weakKey.clear()
        weakKey.enqueue().shouldBeTrue()
    }

    private fun registerMissBeforeDbRead(
        registry: SnapshotMissCapabilityRegistry<Long, Payload>,
        fences: SnapshotLocalFenceRegistry<Long>,
        id: Long,
        dbRead: () -> Unit,
    ): SnapshotCacheLookup<Long, Payload> {
        val lookup = registry.register(id, fences.capture(id))
        dbRead()
        return lookup
    }

    companion object {
        private val PER_INPUT_OUTCOMES = listOf(
            SnapshotCacheOutcome.SUCCESS,
            SnapshotCacheOutcome.FAILED,
            SnapshotCacheOutcome.REJECTED,
            SnapshotCacheOutcome.NOT_ATTEMPTED,
        )

        private fun exceptionType(outcome: SnapshotCacheOutcome): String? =
            if (outcome == SnapshotCacheOutcome.FAILED) IllegalStateException::class.java.name else null
    }

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
}
