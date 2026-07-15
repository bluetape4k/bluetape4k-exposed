package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(InternalSnapshotCacheApi::class)
class SnapshotLocalFenceRegistryTest {

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
    fun `miss then concurrent invalidation rejects old fill in one hundred controlled races`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val registry = SnapshotLocalFenceRegistry<Long>(stripeCount = 2)
                val cache = mutableMapOf<Long, String>()
                val fence = registry.capture(1L)
                val start = CountDownLatch(1)
                val invalidated = CountDownLatch(1)

                val fill = executor.submit<Boolean> {
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
            executor.shutdownNow()
        }
    }

    @Test
    fun `operations on unrelated stripes proceed while another stripe is held`() {
        val registry = SnapshotLocalFenceRegistry<Int>(stripeCount = 2)
        val heldFence = registry.capture(0)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unrelatedCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
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
            executor.shutdownNow()
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
}
