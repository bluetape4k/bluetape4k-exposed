@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.r2dbc.caffeine.snapshot

import com.github.benmanes.caffeine.cache.Cache
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore
import io.bluetape4k.exposed.cache.snapshot.SnapshotValueSizer
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class R2dbcCaffeineSnapshotCacheTest {

    @Test
    fun `explicit and reified factories preserve caller failure buffer identity`() {
        val explicitBuffer = snapshotCacheFailureBuffer(3)
        val reifiedBuffer = snapshotCacheFailureBuffer(5)

        val explicit = r2dbcCaffeineSnapshotCache(
            Long::class,
            Payload::class,
            config("explicit:v1"),
            failureBuffer = explicitBuffer,
        )
        val reified = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("reified:v1"),
            failureBuffer = reifiedBuffer,
        )

        (explicit.failureBuffer === explicitBuffer) shouldBeEqualTo true
        (reified.failureBuffer === reifiedBuffer) shouldBeEqualTo true
        explicit.storeId.namespace shouldBeEqualTo "explicit:v1"
        reified.storeId.namespace shouldBeEqualTo "reified:v1"
    }

    @Test
    fun `unweighted factory returns opaque misses and enforces outstanding token capacity`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("tokens:v1", maxOutstandingMissTokens = 1),
        )

        val lookup = cache.lookup(7L)

        lookup.snapshot.shouldBeNull()
        lookup.miss.shouldNotBeNull().toString() shouldBeEqualTo "SnapshotCacheMiss(opaque)"
        assertFailsWith<IllegalStateException> { cache.lookup(8L) }
    }

    @Test
    fun `weighted settings require a sizer while unweighted settings do not`() {
        r2dbcCaffeineSnapshotCache<Long, Payload>(config("unweighted:v1"))

        assertFailsWith<IllegalArgumentException> {
            r2dbcCaffeineSnapshotCache<Long, Payload>(
                config("weighted-missing:v1", maximumWeight = 100L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            r2dbcCaffeineSnapshotCache<Long, Payload>(
                config("staged-missing:v1", maxStagedWeight = 100L),
            )
        }

        r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("weighted:v1", maximumWeight = 100L, maxStagedWeight = 200L),
            valueSizer = SnapshotValueSizer { it.value.length.toLong() },
        )
    }

    @Test
    fun `factory rejects negative sizes returned by a value sizer when a miss is prepared`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("negative-weight:v1", maximumWeight = 100L),
            valueSizer = SnapshotValueSizer { -1L },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        val failure = assertFailsWith<IllegalArgumentException> {
            (cache as SnapshotCacheStore<Long, Payload>).claimMiss(miss).prepare(CacheSnapshot(Payload("bad")))
        }

        failure.message?.contains("non-negative") shouldBeEqualTo true
    }

    @Test
    fun `weighted factory accepts independent capacities but rejects values Caffeine cannot represent`() {
        r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("independent-capacity:v1", maximumWeight = Long.MAX_VALUE, maximumSize = 1L),
            valueSizer = SnapshotValueSizer { 1L },
        )
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("unrepresentable-value:v1", maximumWeight = Long.MAX_VALUE, maximumSize = Long.MAX_VALUE),
            valueSizer = SnapshotValueSizer { Int.MAX_VALUE.toLong() + 1L },
        )
        val miss = cache.lookup(1L).miss.shouldNotBeNull()

        assertFailsWith<IllegalArgumentException> {
            (cache as SnapshotCacheStore<Long, Payload>).claimMiss(miss).prepare(CacheSnapshot(Payload("too-large")))
        }
    }

    @Test
    fun `prepared weight is measured once and weighted capacity remains entry bounded`() {
        val sizings = AtomicInteger()
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("weighted-capacity:v1", maximumWeight = 4L, maximumSize = 1L),
            valueSizer = SnapshotValueSizer {
                sizings.incrementAndGet()
                1L
            },
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        val first = store.claimMiss(cache.lookup(1L).miss.shouldNotBeNull()).prepare(CacheSnapshot(Payload("one")))
        val second = store.claimMiss(cache.lookup(2L).miss.shouldNotBeNull()).prepare(CacheSnapshot(Payload("two")))

        store.applySnapshots(listOf(first, second), NeverExpiredDeadline)

        sizings.get() shouldBeEqualTo 2
        listOf(cache.lookup(1L).snapshot, cache.lookup(2L).snapshot).count { it != null } shouldBeEqualTo 1
    }

    @Test
    fun `weighted Caffeine receives the exact conservative value estimate`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("exact-weight:v1", maximumWeight = 100L, maximumSize = 1L),
            valueSizer = SnapshotValueSizer { 7L },
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        val put = store.claimMiss(cache.lookup(1L).miss.shouldNotBeNull())
            .prepare(CacheSnapshot(Payload("seven")))

        store.applySnapshots(listOf(put), NeverExpiredDeadline)

        weightedSize(cache) shouldBeEqualTo 7L
    }

    @Test
    fun `weighted mode enforces maximum size independently from maximum weight`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("weighted-count:v1", maximumWeight = 1_000L, maximumSize = 2L),
            valueSizer = SnapshotValueSizer { 1L },
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        val puts = (1L..3L).map { id ->
            store.claimMiss(cache.lookup(id).miss.shouldNotBeNull()).prepare(CacheSnapshot(Payload("value-$id")))
        }

        store.applySnapshots(puts, NeverExpiredDeadline)

        (1L..3L).count { cache.lookup(it).snapshot != null } shouldBeEqualTo 2
        weightedSize(cache) shouldBeEqualTo 2L
    }

    @Test
    fun `concurrent weighted puts preserve maximum size after quiescent maintenance`() {
        val executor = TrackedExecutor(CONCURRENT_PUTS)

        try {
            repeat(RACE_REPETITIONS) { iteration ->
                val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
                    config(
                        "weighted-concurrent-$iteration:v1",
                        maximumWeight = 1_000L,
                        maximumSize = CONCURRENT_MAXIMUM_SIZE,
                        maxOutstandingMissTokens = CONCURRENT_PUTS,
                    ),
                    valueSizer = SnapshotValueSizer { 1L },
                )
                val store = cache as SnapshotCacheStore<Long, Payload>
                val puts = (0 until CONCURRENT_PUTS).map { id ->
                    val longId = id.toLong()
                    store.claimMiss(cache.lookup(longId).miss.shouldNotBeNull())
                        .prepare(CacheSnapshot(Payload("value-$id")))
                }
                val ready = CountDownLatch(CONCURRENT_PUTS)
                val start = CountDownLatch(1)
                val futures = puts.map { put ->
                    executor.submit {
                        ready.countDown()
                        start.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        store.applySnapshots(listOf(put), NeverExpiredDeadline)
                    }
                }

                ready.await(5, TimeUnit.SECONDS).shouldBeTrue()
                start.countDown()
                futures.forEach { future ->
                    future.get(5, TimeUnit.SECONDS).results.map { it.outcome to it.affectedCount } shouldBeEqualTo
                        listOf(SnapshotCacheOutcome.SUCCESS to 1)
                }

                val caffeine = caffeineCache(cache)
                caffeine.cleanUp()
                (caffeine.estimatedSize() <= CONCURRENT_MAXIMUM_SIZE).shouldBeTrue()
                (caffeine.asMap().size <= CONCURRENT_MAXIMUM_SIZE).shouldBeTrue()
            }
        } finally {
            executor.close()
        }
    }

    @Test
    fun `maximum size and expiry settings are enforced by Caffeine`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config(
                "expiry:v1",
                maximumSize = 1L,
                expireAfterWrite = Duration.ofMillis(2),
                expireAfterAccess = null,
            ),
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        fun put(id: Long) = store.claimMiss(cache.lookup(id).miss.shouldNotBeNull())
            .prepare(CacheSnapshot(Payload(id.toString())))

        store.applySnapshots(listOf(put(1L), put(2L)), NeverExpiredDeadline)
        listOf(cache.lookup(1L).snapshot, cache.lookup(2L).snapshot).count { it != null } shouldBeEqualTo 1

        Thread.sleep(20)
        cache.lookup(2L).snapshot.shouldBeNull()
    }

    @Test
    fun `non-null expire after access expires an inactive snapshot`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config(
                "access-expiry:v1",
                maximumSize = 4L,
                expireAfterWrite = Duration.ofMinutes(1),
                expireAfterAccess = Duration.ofMillis(2),
            ),
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        val put = store.claimMiss(cache.lookup(1L).miss.shouldNotBeNull())
            .prepare(CacheSnapshot(Payload("inactive")))
        store.applySnapshots(listOf(put), NeverExpiredDeadline)

        cache.lookup(1L).snapshot.shouldNotBeNull()
        Thread.sleep(20)

        cache.lookup(1L).snapshot.shouldBeNull()
    }

    @Test
    fun `cooperative deadline reports attempted entry and remaining not attempted entries`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(config("deadline:v1"))
        val store = cache as SnapshotCacheStore<Long, Payload>
        val deadline = ExpireAfterFirstPollDeadline()

        val report = store.applyInvalidations(listOf(1L, 2L), deadline)

        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.SUCCESS,
            SnapshotCacheOutcome.OVERRUN,
            SnapshotCacheOutcome.NOT_ATTEMPTED,
        )
        report.results.sumOf { it.affectedCount } shouldBeEqualTo 2
    }

    @Test
    fun `single successful entry reports zero-count overrun after the operation`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(config("single-overrun:v1"))
        val store = cache as SnapshotCacheStore<Long, Payload>
        val deadline = ExpireAfterFirstPollDeadline()

        val report = store.applyInvalidations(listOf(1L), deadline)

        report.results.map { it.outcome to it.affectedCount } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.SUCCESS to 1,
            SnapshotCacheOutcome.OVERRUN to 0,
        )
        report.results.sumOf { it.affectedCount } shouldBeEqualTo 1
    }

    @Test
    fun `single put reports one overrun when capacity maintenance exhausts the deadline`() {
        val cache = r2dbcCaffeineSnapshotCache<Long, Payload>(
            config("maintenance-overrun:v1", maximumSize = 1L),
        )
        val store = cache as SnapshotCacheStore<Long, Payload>
        fun put(id: Long) = store.claimMiss(cache.lookup(id).miss.shouldNotBeNull())
            .prepare(CacheSnapshot(Payload(id.toString())))
        store.applySnapshots(listOf(put(1L)), NeverExpiredDeadline)
        val deadline = ExpireAfterSecondPollDeadline()

        val report = store.applySnapshots(listOf(put(2L)), deadline)

        report.results.map { it.outcome to it.affectedCount } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.SUCCESS to 1,
            SnapshotCacheOutcome.OVERRUN to 0,
        )
        report.results.count { it.outcome == SnapshotCacheOutcome.OVERRUN } shouldBeEqualTo 1
        report.results.sumOf { it.affectedCount } shouldBeEqualTo 1
        listOf(cache.lookup(1L).snapshot, cache.lookup(2L).snapshot).count { it != null } shouldBeEqualTo 1
        deadline.pollCount shouldBeEqualTo 3
    }

    private fun config(
        namespace: String,
        maximumWeight: Long? = null,
        maxStagedWeight: Long? = null,
        maxOutstandingMissTokens: Int = 16,
        maximumSize: Long = 32,
        expireAfterWrite: Duration = Duration.ofMinutes(1),
        expireAfterAccess: Duration? = Duration.ofSeconds(30),
    ) = CaffeineSnapshotCacheConfig(
        snapshot = SnapshotCacheConfig(namespace, "payload-v1"),
        maximumSize = maximumSize,
        maximumWeight = maximumWeight,
        expireAfterWrite = expireAfterWrite,
        expireAfterAccess = expireAfterAccess,
        maxStagedWeight = maxStagedWeight,
        fenceStripes = 64,
        maxOutstandingMissTokens = maxOutstandingMissTokens,
    )

    private data class Payload(val value: String) : Serializable

    private fun weightedSize(cache: R2dbcCaffeineSnapshotCache<Long, Payload>): Long {
        return caffeineCache(cache).policy().eviction().orElseThrow().weightedSize().orElseThrow()
    }

    private fun caffeineCache(cache: R2dbcCaffeineSnapshotCache<Long, Payload>): Cache<*, *> {
        val field = cache.javaClass.getDeclaredField("cache").apply { trySetAccessible() }
        return field.get(cache) as Cache<*, *>
    }

    private object NeverExpiredDeadline : SnapshotCacheDeadline {
        override fun remaining(): Duration = Duration.ofDays(1)
        override val isExpired: Boolean = false
    }

    private class ExpireAfterFirstPollDeadline : SnapshotCacheDeadline {
        private val polls = AtomicInteger()
        override fun remaining(): Duration = if (isExpired) Duration.ZERO else Duration.ofSeconds(1)
        override val isExpired: Boolean get() = polls.incrementAndGet() > 1
    }

    private class ExpireAfterSecondPollDeadline : SnapshotCacheDeadline {
        private val polls = AtomicInteger()
        val pollCount: Int get() = polls.get()
        override fun remaining(): Duration = if (isExpired) Duration.ZERO else Duration.ofSeconds(1)
        override val isExpired: Boolean get() = polls.incrementAndGet() > 2
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

    companion object {
        private const val RACE_REPETITIONS: Int = 100
        private const val CONCURRENT_PUTS: Int = 8
        private const val CONCURRENT_MAXIMUM_SIZE: Long = 2L
    }
}
