package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties

@OptIn(InternalSnapshotCacheApi::class)
class SnapshotLocalFenceRegistryTest {

    @Test
    fun `local fence exposes no construction state or capability operations`() {
        SnapshotLocalFence::class.isData.shouldBeFalse()
        SnapshotLocalFence::class.constructors.none { it.visibility == KVisibility.PUBLIC }.shouldBeTrue()
        SnapshotLocalFence::class.declaredMemberProperties
            .none { it.visibility == KVisibility.PUBLIC }
            .shouldBeTrue()
        SnapshotLocalFence::class.declaredMemberFunctions
            .none { it.visibility == KVisibility.PUBLIC }
            .shouldBeTrue()
        SnapshotLocalFenceRegistry::class.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .sorted() shouldBeEqualTo listOf("capture", "invalidate", "putIfCurrent")
    }

    @Test
    fun `stripe count must be a positive power of two`() {
        SnapshotLocalFenceRegistry<Long>(1)
        SnapshotLocalFenceRegistry<Long>(64)

        listOf(0, -1, 3, 63).forEach { stripeCount ->
            assertFailsWith<IllegalArgumentException> {
                SnapshotLocalFenceRegistry<Long>(stripeCount)
            }
        }
    }

    @Test
    fun `current fill replaces generation and rejects reuse`() {
        val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val fence = registry.capture(1L)
        var cached: String? = null

        registry.putIfCurrent(1L, fence) { cached = "fresh" }.shouldBeTrue()
        cached shouldBeEqualTo "fresh"
        registry.putIfCurrent(1L, fence) { cached = "stale" }.shouldBeFalse()
        cached shouldBeEqualTo "fresh"
    }

    @Test
    fun `foreign owner fence is rejected`() {
        val first = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val second = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        var cached: String? = null

        second.putIfCurrent(1L, first.capture(1L)) { cached = "foreign" }.shouldBeFalse()
        cached.shouldBeNull()
    }

    @Test
    fun `copied put cannot reuse a fence for another id on the same stripe`() {
        val registry = SnapshotLocalFenceRegistry<CollidingId>(stripeCount = 1)
        val first = CollidingId("first")
        val second = CollidingId("second")
        val firstFence = registry.capture(first)
        val original = SnapshotCacheMutation.Put(first, CacheSnapshot("first"), firstFence)
        val copied = original.copy(id = second)
        var cached: CollidingId? = null

        val copiedFence = copied.localFence ?: error("Expected copied local fence")
        registry.putIfCurrent(copied.id, copiedFence) { cached = copied.id }.shouldBeFalse()
        cached.shouldBeNull()
        val originalFence = original.localFence ?: error("Expected original local fence")
        registry.putIfCurrent(original.id, originalFence) { cached = original.id }.shouldBeTrue()
        cached shouldBeEqualTo first
    }

    @Test
    fun `captured id uses stable equality rather than identity`() {
        val registry = SnapshotLocalFenceRegistry<CollidingId>(stripeCount = 1)
        val capturedId = CollidingId("same")
        val equivalentId = CollidingId("same")
        val fence = registry.capture(capturedId)
        var cached: CollidingId? = null

        registry.putIfCurrent(equivalentId, fence) { cached = equivalentId }.shouldBeTrue()
        cached shouldBeEqualTo capturedId
    }

    @Test
    fun `simultaneous same id duplicate allows exactly one contender`() {
        val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val fence = registry.capture(1L)
        val start = CountDownLatch(1)
        val applied = AtomicInteger()
        val executor = TrackedExecutor(threadCount = 2)

        try {
            val attempts = List(2) {
                executor.submit {
                    start.await()
                    registry.putIfCurrent(1L, fence) { applied.incrementAndGet() }
                }
            }

            start.countDown()
            attempts.map { it.get(5, TimeUnit.SECONDS) }.count { it } shouldBeEqualTo 1
            applied.get() shouldBeEqualTo 1
        } finally {
            start.countDown()
            executor.close()
        }
    }

    @Test
    fun `put action failure releases lock after advancing generation`() {
        val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val staleFence = registry.capture(1L)
        var applied = false
        val executor = TrackedExecutor(threadCount = 1)

        try {
            assertFailsWith<MutationFailure> {
                registry.putIfCurrent(1L, staleFence) { throw MutationFailure() }
            }

            registry.putIfCurrent(1L, staleFence) { applied = true }.shouldBeFalse()
            executor.submit {
                val freshFence = registry.capture(1L)
                registry.putIfCurrent(1L, freshFence) { applied = true }
            }.get(5, TimeUnit.SECONDS).shouldBeTrue()
            applied.shouldBeTrue()
        } finally {
            executor.close()
        }
    }

    @Test
    fun `invalidation action failure releases lock after advancing generation`() {
        val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
        val staleFence = registry.capture(1L)
        var applied = false
        val executor = TrackedExecutor(threadCount = 1)

        try {
            assertFailsWith<MutationFailure> {
                registry.invalidate(1L) { throw MutationFailure() }
            }

            registry.putIfCurrent(1L, staleFence) { applied = true }.shouldBeFalse()
            executor.submit {
                registry.invalidate(1L) { applied = true }
            }.get(5, TimeUnit.SECONDS)
            applied.shouldBeTrue()
        } finally {
            executor.close()
        }
    }

    @Test
    fun `miss then concurrent invalidation rejects old fill in one hundred controlled races`() {
        val executor = TrackedExecutor(threadCount = 2)
        try {
            repeat(100) { round ->
                val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
                val cache = mutableMapOf<Long, String>()
                val fence = registry.capture(1L)
                val start = CountDownLatch(1)
                val invalidated = CountDownLatch(1)

                val fill = executor.submit {
                    start.await()
                    invalidated.await()
                    registry.putIfCurrent(1L, fence) { cache[1L] = "stale-$round" }
                }
                val invalidate = executor.submit {
                    start.await()
                    registry.invalidate(1L) { cache.remove(1L) }
                    invalidated.countDown()
                }

                start.countDown()
                invalidate.get(5, TimeUnit.SECONDS)
                fill.get(5, TimeUnit.SECONDS).shouldBeFalse()
                cache[1L].shouldBeNull()
            }
        } finally {
            executor.close()
        }
    }

    @Test
    fun `operations on unrelated stripes proceed while another stripe is held`() {
        val registry = SnapshotLocalFenceRegistry<Int>(stripeCount = 2)
        val heldFence = registry.capture(0)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unrelatedCompleted = CountDownLatch(1)
        val executor = TrackedExecutor(threadCount = 2)
        try {
            val holding = executor.submit<Boolean> {
                registry.putIfCurrent(0, heldFence) {
                    held.countDown()
                    release.await()
                }
            }
            held.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val unrelated = executor.submit {
                registry.invalidate(1) { unrelatedCompleted.countDown() }
            }

            unrelatedCompleted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            release.countDown()
            holding.get(5, TimeUnit.SECONDS).shouldBeTrue()
            unrelated.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `forced stripe collision may reject safe fill but never admits stale value`() {
        val registry = SnapshotLocalFenceRegistry<CollidingId>(stripeCount = 1)
        val first = CollidingId("first")
        val second = CollidingId("second")
        val cache = mutableMapOf<CollidingId, String>()
        val firstFence = registry.capture(first)

        registry.invalidate(second) { cache.remove(second) }
        registry.putIfCurrent(first, firstFence) { cache[first] = "stale" }.shouldBeFalse()

        cache[first].shouldBeNull()
    }

    private class CollidingId(private val value: String) {
        override fun hashCode(): Int = 0

        override fun equals(other: Any?): Boolean = other is CollidingId && value == other.value
    }

    private class MutationFailure : RuntimeException()

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
