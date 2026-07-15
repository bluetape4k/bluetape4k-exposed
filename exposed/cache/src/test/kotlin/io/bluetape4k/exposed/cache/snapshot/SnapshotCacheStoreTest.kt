package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
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
        mutation.localFence shouldBeEqualTo fences.capture(1L)
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
    fun `store identity limits and result models validate caller input`() {
        val limits = SnapshotCacheLimits(
            maxStagedMutations = 10,
            maxParticipatingStores = 2,
            maxStagedWeight = 1_024L,
            localDrainBudget = Duration.ofMillis(50),
        )
        val storeId = SnapshotStoreId("caffeine", "orders:v1")
        val measured = MeasuredInvalidation(1L, 16L, "a".repeat(64))

        limits.maxStagedMutations shouldBeEqualTo 10
        storeId shouldBeEqualTo SnapshotStoreId("caffeine", "orders:v1")
        measured.encodedBytes shouldBeEqualTo 16L
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
        assertFailsWith<IllegalArgumentException> { MeasuredInvalidation(1L, -1L, "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { MeasuredInvalidation(1L, 1L, "not-a-sha256") }
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
    fun `store is called once per phase and reports reconcile every input`() {
        val store = RecordingStore()
        val clock = AtomicLong(0L)
        val sharedDeadline = MonotonicSnapshotCacheDeadline(Duration.ofNanos(5)) {
            clock.addAndGet(3L)
        }
        val snapshots = listOf(
            SnapshotCacheMutation.Put(1L, CacheSnapshot(Payload("one"))),
            SnapshotCacheMutation.Put(2L, CacheSnapshot(Payload("two"))),
        )

        val putReport = store.applySnapshots(snapshots, sharedDeadline)
        val invalidationReport = store.applyInvalidations(
            listOf(1L, 2L),
            MonotonicSnapshotCacheDeadline(Duration.ofSeconds(1)) { 0L },
        )

        store.snapshotCalls.get() shouldBeEqualTo 1
        store.invalidationCalls.get() shouldBeEqualTo 1
        putReport.results.sumOf { it.affectedCount } shouldBeEqualTo snapshots.size
        invalidationReport.results.sumOf { it.affectedCount } shouldBeEqualTo 2
        putReport.results.map { it.outcome } shouldBeEqualTo listOf(
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

    private class RecordingStore : SnapshotCacheStore<Long, Payload> {
        override val storeId = SnapshotStoreId("local", "orders:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "local:v1"
        override val limits = SnapshotCacheLimits(10, 2)
        val snapshotCalls = AtomicInteger()
        val invalidationCalls = AtomicInteger()

        override fun claimMiss(miss: SnapshotCacheMiss<Long, Payload>): ClaimedSnapshotMiss<Long, Payload> =
            error("Not used by this store proof")

        override fun applySnapshots(
            snapshots: List<SnapshotCacheMutation.Put<Long, Payload>>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport {
            snapshotCalls.incrementAndGet()
            return SnapshotCacheApplyReport(
                snapshots.map {
                    SnapshotCacheOperationResult(
                        operation = SnapshotCacheOperation.PUT,
                        outcome = if (deadline.isExpired) {
                            SnapshotCacheOutcome.NOT_ATTEMPTED
                        } else {
                            SnapshotCacheOutcome.SUCCESS
                        },
                        affectedCount = 1,
                    )
                },
            )
        }

        override fun applyInvalidations(
            ids: List<Long>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport {
            invalidationCalls.incrementAndGet()
            return SnapshotCacheApplyReport(
                ids.map {
                    SnapshotCacheOperationResult(
                        operation = SnapshotCacheOperation.INVALIDATE,
                        outcome = if (deadline.isExpired) {
                            SnapshotCacheOutcome.NOT_ATTEMPTED
                        } else {
                            SnapshotCacheOutcome.SUCCESS
                        },
                        affectedCount = 1,
                    )
                },
            )
        }
    }

    private class RecordingAsyncStore : AsyncSnapshotInvalidationStore<Long> {
        override val storeId = SnapshotStoreId("remote", "orders:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "remote:v1"
        override val limits = SnapshotCacheLimits(10, 2)

        override fun measure(id: Long): MeasuredInvalidation<Long> =
            MeasuredInvalidation(id, Long.SIZE_BYTES.toLong(), "b".repeat(64))

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
}
