@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
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
import java.util.concurrent.atomic.AtomicInteger

class JdbcCaffeineSnapshotCacheTest {

    @Test
    fun `explicit and reified factories preserve caller failure buffer identity`() {
        val explicitBuffer = snapshotCacheFailureBuffer(3)
        val reifiedBuffer = snapshotCacheFailureBuffer(5)

        val explicit = jdbcCaffeineSnapshotCache(
            Long::class,
            Payload::class,
            config("explicit:v1"),
            failureBuffer = explicitBuffer,
        )
        val reified = jdbcCaffeineSnapshotCache<Long, Payload>(
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
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
            config("tokens:v1", maxOutstandingMissTokens = 1),
        )

        val lookup = cache.lookup(7L)

        lookup.snapshot.shouldBeNull()
        lookup.miss.shouldNotBeNull().toString() shouldBeEqualTo "SnapshotCacheMiss(opaque)"
        assertFailsWith<IllegalStateException> { cache.lookup(8L) }
    }

    @Test
    fun `weighted settings require a sizer while unweighted settings do not`() {
        jdbcCaffeineSnapshotCache<Long, Payload>(config("unweighted:v1"))

        assertFailsWith<IllegalArgumentException> {
            jdbcCaffeineSnapshotCache<Long, Payload>(
                config("weighted-missing:v1", maximumWeight = 100L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            jdbcCaffeineSnapshotCache<Long, Payload>(
                config("staged-missing:v1", maxStagedWeight = 100L),
            )
        }

        jdbcCaffeineSnapshotCache<Long, Payload>(
            config("weighted:v1", maximumWeight = 100L, maxStagedWeight = 200L),
            valueSizer = SnapshotValueSizer { it.value.length.toLong() },
        )
    }

    @Test
    fun `factory rejects negative sizes returned by a value sizer when a miss is prepared`() {
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
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
    fun `weighted factory rejects capacities and values Caffeine cannot conservatively represent`() {
        assertFailsWith<IllegalArgumentException> {
            jdbcCaffeineSnapshotCache<Long, Payload>(
                config("unrepresentable-capacity:v1", maximumWeight = Long.MAX_VALUE, maximumSize = 1L),
                valueSizer = SnapshotValueSizer { 1L },
            )
        }
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
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
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
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
    fun `maximum size and expiry settings are enforced by Caffeine`() {
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(
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
    fun `cooperative deadline reports attempted entry and remaining not attempted entries`() {
        val cache = jdbcCaffeineSnapshotCache<Long, Payload>(config("deadline:v1"))
        val store = cache as SnapshotCacheStore<Long, Payload>
        val deadline = ExpireAfterFirstPollDeadline()

        val report = store.applyInvalidations(listOf(1L, 2L), deadline)

        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.SUCCESS,
            SnapshotCacheOutcome.NOT_ATTEMPTED,
        )
        report.results.sumOf { it.affectedCount } shouldBeEqualTo 2
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

    private object NeverExpiredDeadline : SnapshotCacheDeadline {
        override fun remaining(): Duration = Duration.ofDays(1)
        override val isExpired: Boolean = false
    }

    private class ExpireAfterFirstPollDeadline : SnapshotCacheDeadline {
        private val polls = AtomicInteger()
        override fun remaining(): Duration = if (isExpired) Duration.ZERO else Duration.ofSeconds(1)
        override val isExpired: Boolean get() = polls.incrementAndGet() > 1
    }
}
