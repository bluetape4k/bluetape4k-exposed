package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.Duration

class SnapshotCacheConfigTest {

    @Test
    fun `snapshot config exposes documented defaults`() {
        val config = SnapshotCacheConfig(
            namespace = "orders:v1",
            schemaVersion = "order-v1",
        )

        config.namespace shouldBeEqualTo "orders:v1"
        config.schemaVersion shouldBeEqualTo "order-v1"
        config.maxStagedMutations shouldBeEqualTo 10_000
        config.maxParticipatingStores shouldBeEqualTo 8
    }

    @Test
    fun `snapshot config declares stable serial version UID`() {
        ObjectStreamClass.lookup(SnapshotCacheConfig::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `snapshot config preserves limits through Java serialization`() {
        val config = SnapshotCacheConfig(
            namespace = "orders:v2",
            schemaVersion = "order-v2",
            maxStagedMutations = 123,
            maxParticipatingStores = 4,
        )

        serializeRoundTrip(config) shouldBeEqualTo config
    }

    @Test
    fun `namespace accepts the complete supported syntax boundary`() {
        SnapshotCacheConfig("a:v1", "schema")
        SnapshotCacheConfig("${"a".repeat(63)}:v9", "schema")
        SnapshotCacheConfig("orders:v123", "schema")
    }

    @Test
    fun `namespace rejects unsupported schema syntax`() {
        listOf(
            "Orders:v1",
            "orders",
            "orders:v0",
            "orders:v01",
            "orders:v",
            "orders tenant:v1",
            "1orders:v1",
            "${"a".repeat(64)}:v1",
        ).forEach { namespace ->
            assertFailsWith<IllegalArgumentException> {
                SnapshotCacheConfig(namespace, "schema")
            }
        }
    }

    @Test
    fun `schema version must not be blank`() {
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheConfig("orders:v1", "")
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheConfig("orders:v1", "   ")
        }
    }

    @Test
    fun `snapshot limits are independently positive`() {
        SnapshotCacheConfig("orders:v1", "schema", maxStagedMutations = 1, maxParticipatingStores = 2)

        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheConfig("orders:v1", "schema", maxStagedMutations = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheConfig("orders:v1", "schema", maxParticipatingStores = 0)
        }
    }

    @Test
    fun `caffeine config exposes documented defaults`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")
        val config = CaffeineSnapshotCacheConfig(snapshot)

        config.snapshot shouldBeEqualTo snapshot
        config.maximumSize shouldBeEqualTo 10_000L
        config.maximumWeight.shouldBeNull()
        config.expireAfterWrite shouldBeEqualTo Duration.ofMinutes(10)
        config.expireAfterAccess.shouldBeNull()
        config.maxStagedWeight.shouldBeNull()
        config.localDrainBudget shouldBeEqualTo Duration.ofMillis(250)
        config.fenceStripes shouldBeEqualTo 1_024
        config.maxOutstandingMissTokens shouldBeEqualTo 10_000
    }

    @Test
    fun `caffeine config declares stable serial version UID`() {
        ObjectStreamClass.lookup(CaffeineSnapshotCacheConfig::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `caffeine config preserves weighted limits and durations through Java serialization`() {
        val config = CaffeineSnapshotCacheConfig(
            snapshot = SnapshotCacheConfig("orders:v2", "order-v2"),
            maximumSize = 123L,
            maximumWeight = 456L,
            expireAfterWrite = Duration.ofMinutes(2),
            expireAfterAccess = Duration.ofMinutes(1),
            maxStagedWeight = 789L,
            localDrainBudget = Duration.ofMillis(100),
            fenceStripes = 256,
            maxOutstandingMissTokens = 321,
        )

        serializeRoundTrip(config) shouldBeEqualTo config
    }

    @Test
    fun `caffeine positive bounds are enforced`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")

        assertFailsWith<IllegalArgumentException> { CaffeineSnapshotCacheConfig(snapshot, maximumSize = 0L) }
        assertFailsWith<IllegalArgumentException> { CaffeineSnapshotCacheConfig(snapshot, maximumWeight = 0L) }
        assertFailsWith<IllegalArgumentException> { CaffeineSnapshotCacheConfig(snapshot, maxStagedWeight = 0L) }
        assertFailsWith<IllegalArgumentException> {
            CaffeineSnapshotCacheConfig(snapshot, maxOutstandingMissTokens = 0)
        }
    }

    @Test
    fun `caffeine durations must be positive when configured`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")

        assertFailsWith<IllegalArgumentException> {
            CaffeineSnapshotCacheConfig(snapshot, expireAfterWrite = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            CaffeineSnapshotCacheConfig(snapshot, expireAfterAccess = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            CaffeineSnapshotCacheConfig(snapshot, localDrainBudget = Duration.ZERO)
        }
    }

    @Test
    fun `fence stripes accepts powers of two across the inclusive range`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")

        CaffeineSnapshotCacheConfig(snapshot, fenceStripes = 64)
        CaffeineSnapshotCacheConfig(snapshot, fenceStripes = 1_024)
        CaffeineSnapshotCacheConfig(snapshot, fenceStripes = 65_536)
    }

    @Test
    fun `fence stripes rejects values outside the range and non powers of two`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")

        listOf(63, 65, 1_000, 65_535, 65_537).forEach { fenceStripes ->
            assertFailsWith<IllegalArgumentException> {
                CaffeineSnapshotCacheConfig(snapshot, fenceStripes = fenceStripes)
            }
        }
    }

    @Test
    fun `weighted limits are independent and do not require a sizer at config construction`() {
        val snapshot = SnapshotCacheConfig("orders:v1", "schema")
        val config = CaffeineSnapshotCacheConfig(
            snapshot = snapshot,
            maximumWeight = 100L,
            maxStagedWeight = 200L,
        )

        config.maximumWeight shouldBeEqualTo 100L
        config.maxStagedWeight shouldBeEqualTo 200L
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Serializable> serializeRoundTrip(value: T): T {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ByteArrayInputStream(bytes).use { input ->
            ObjectInputStream(input).use { it.readObject() as T }
        }
    }
}
