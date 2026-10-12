package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.Duration
import java.util.Base64

@OptIn(InternalSnapshotCacheApi::class)
class SnapshotSerializationTest {

    @Test
    fun `deserialization rejects invalid snapshot store identifiers`() {
        assertInvalidStream(corrupted(SnapshotStoreId("caffeine", "orders:v1"), "backend", ""))
        assertInvalidStream(corrupted(SnapshotStoreId("caffeine", "orders:v1"), "backend", "b".repeat(129)))
        assertInvalidStream(corrupted(SnapshotStoreId("caffeine", "orders:v1"), "namespace", ""))
        assertInvalidStream(corrupted(SnapshotStoreId("caffeine", "orders:v1"), "namespace", "n".repeat(513)))
    }

    @Test
    fun `deserialization rejects invalid snapshot limits`() {
        assertInvalidStream(corrupted(SnapshotCacheLimits(1, 1), "maxStagedMutations", -1))
        assertInvalidStream(corrupted(SnapshotCacheLimits(1, 1), "maxParticipatingStores", -1))
        assertInvalidStream(corrupted(SnapshotCacheLimits(1, 1), "maxStagedWeight", -1L))
        assertInvalidStream(corrupted(SnapshotCacheLimits(1, 1), "localDrainBudget", Duration.ZERO))
    }

    @Test
    fun `deserialization rejects invalid snapshot configuration limits`() {
        assertInvalidStream(corrupted(SnapshotCacheConfig("orders:v1", "schema"), "namespace", ""))
        assertInvalidStream(
            corrupted(
                SnapshotCacheConfig("orders:v1", "schema"),
                "namespace",
                "n".repeat(64) + ":v1",
            ),
        )
        assertInvalidStream(corrupted(SnapshotCacheConfig("orders:v1", "schema"), "schemaVersion", " "))
        assertInvalidStream(corrupted(SnapshotCacheConfig("orders:v1", "schema"), "maxStagedMutations", -1))
        assertInvalidStream(corrupted(SnapshotCacheConfig("orders:v1", "schema"), "maxParticipatingStores", -1))
    }

    @Test
    fun `deserialization rejects invalid caffeine configuration limits`() {
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "maximumSize",
                -1L,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "expireAfterAccess",
                Duration.ZERO,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "maxStagedWeight",
                -1L,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "maximumWeight",
                -1L,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "expireAfterWrite",
                Duration.ZERO,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "fenceStripes",
                63,
            ),
        )
        assertInvalidStream(
            corrupted(
                CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema")),
                "maxOutstandingMissTokens",
                -1,
            ),
        )
    }

    @Test
    fun `deserialization rejects invalid measured invalidation metadata`() {
        assertInvalidStream(corrupted(MeasuredInvalidation(1L, 0, "a".repeat(64)), "encodedBytes", -1))
        assertInvalidStream(corrupted(MeasuredInvalidation(1L, 0, "a".repeat(64)), "encodedSha256", "not-a-sha256"))
    }

    @Test
    fun `deserialization rejects invalid operation result counts and metadata`() {
        val result = SnapshotCacheOperationResult(SnapshotCacheOperation.PUT, SnapshotCacheOutcome.SUCCESS, 1)

        assertInvalidStream(corrupted(result, "affectedCount", -1))
        assertInvalidStream(
            corrupted(
                SnapshotCacheOperationResult(SnapshotCacheOperation.PUT, SnapshotCacheOutcome.OVERRUN, 0),
                "affectedCount",
                1,
            ),
        )
        assertInvalidStream(corrupted(result, "exceptionType", " "))
    }

    @Test
    fun `deserialized reports do not expose a mutable result list`() {
        val report = SnapshotCacheApplyReport(
            listOf(SnapshotCacheOperationResult(SnapshotCacheOperation.PUT, SnapshotCacheOutcome.SUCCESS, 1)),
        )

        val restored = deserialize<SnapshotCacheApplyReport>(serialize(report))

        restored.results shouldBeEqualTo report.results
        restored.results shouldHaveSize 1
        @Suppress("UNCHECKED_CAST")
        val mutableResults = restored.results as MutableList<SnapshotCacheOperationResult>
        assertFailsWith<UnsupportedOperationException> {
            mutableResults += SnapshotCacheOperationResult(
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.SUCCESS,
                1,
            )
        }
    }

    @Test
    fun `serializable snapshot models retain stable UIDs and valid Java streams`() {
        val result = SnapshotCacheOperationResult(SnapshotCacheOperation.PUT, SnapshotCacheOutcome.SUCCESS, 1)
        val models = listOf<Serializable>(
            CacheSnapshot(Payload("cached"), "r-1"),
            SnapshotCacheConfig("orders:v1", "schema-v1"),
            CaffeineSnapshotCacheConfig(SnapshotCacheConfig("orders:v1", "schema-v1")),
            SnapshotStoreId("caffeine", "orders:v1"),
            SnapshotCacheLimits(1, 1, maxStagedWeight = 1L, localDrainBudget = Duration.ofMillis(1)),
            MeasuredInvalidation(1L, 0, "a".repeat(64)),
            SnapshotCacheApplyReport(listOf(result)),
            result,
        )

        models.forEach { model ->
            ObjectStreamClass.lookup(model.javaClass).serialVersionUID shouldBeEqualTo 1L
            deserialize<Any>(serialize(model)) shouldBeEqualTo model
        }
    }

    @Test
    fun `runtime snapshot capabilities remain non serializable`() {
        listOf(
            SnapshotCacheLookup::class.java,
            SnapshotCacheMiss::class.java,
            SnapshotLocalFence::class.java,
            SnapshotMissCapabilityRegistry::class.java,
        ).forEach { type ->
            Serializable::class.java.isAssignableFrom(type).shouldBeFalse()
        }
    }

    @Test
    fun `valid legacy snapshot model streams remain readable`() {
        val models = deserialize<List<Serializable>>(readFixture("legacy-snapshot-models.base64"))

        models shouldHaveSize 8
        models[0] shouldBeEqualTo SnapshotStoreId("caffeine", "orders:v1")
        models[1] shouldBeEqualTo SnapshotCacheLimits(
            maxStagedMutations = 100,
            maxParticipatingStores = 4,
            maxStagedWeight = 50_000L,
            localDrainBudget = Duration.ofMillis(250),
        )
        models[2] shouldBeEqualTo SnapshotCacheConfig(
            namespace = "orders:v1",
            schemaVersion = "order-v1",
            maxStagedMutations = 100,
            maxParticipatingStores = 4,
        )
        models[3] shouldBeEqualTo CaffeineSnapshotCacheConfig(
            snapshot = models[2] as SnapshotCacheConfig,
            maximumSize = 1_000L,
            maximumWeight = 10_000L,
            expireAfterWrite = Duration.ofMinutes(10),
            maxStagedWeight = 50_000L,
            localDrainBudget = Duration.ofMillis(250),
            fenceStripes = 1_024,
            maxOutstandingMissTokens = 10_000,
        )
        models[4] shouldBeEqualTo MeasuredInvalidation(1L, 256, "a".repeat(64))
        models[5] shouldBeEqualTo SnapshotCacheOperationResult(
            operation = SnapshotCacheOperation.PUT,
            outcome = SnapshotCacheOutcome.SUCCESS,
            affectedCount = 1,
        )
        models[6] shouldBeEqualTo SnapshotCacheApplyReport(listOf(models[5] as SnapshotCacheOperationResult))
        models[7] shouldBeEqualTo CacheSnapshot("legacy-value", "rev-1")
    }

    @Test
    fun `legacy report streams detach mutable result list aliases`() {
        val values = deserialize<List<Any>>(readFixture("legacy-snapshot-report-alias.base64"))
        val report = values[0] as SnapshotCacheApplyReport
        @Suppress("UNCHECKED_CAST")
        val sourceResults = values[1] as MutableList<SnapshotCacheOperationResult>

        report.results shouldBeEqualTo sourceResults
        (report.results === sourceResults).shouldBeFalse()
        sourceResults += SnapshotCacheOperationResult(
            operation = SnapshotCacheOperation.PUT,
            outcome = SnapshotCacheOutcome.SUCCESS,
            affectedCount = 1,
        )
        report.results shouldHaveSize 1
        @Suppress("UNCHECKED_CAST")
        val restoredResults = report.results as MutableList<SnapshotCacheOperationResult>
        assertFailsWith<UnsupportedOperationException> {
            restoredResults += SnapshotCacheOperationResult(
                operation = SnapshotCacheOperation.PUT,
                outcome = SnapshotCacheOutcome.SUCCESS,
                affectedCount = 1,
            )
        }
    }

    private fun assertInvalidStream(bytes: ByteArray) {
        assertFailsWith<IllegalArgumentException> {
            deserialize<Any>(bytes)
        }
    }

    private fun <T: Serializable> corrupted(value: T, fieldName: String, replacement: Any?): ByteArray {
        value.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(value, replacement)
        }
        return serialize(value)
    }

    private fun serialize(value: Serializable): ByteArray = ByteArrayOutputStream().use { output ->
        ObjectOutputStream(output).use { it.writeObject(value) }
        output.toByteArray()
    }

    private fun readFixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/$name")) {
        "Missing serialization fixture: $name"
    }.use { input ->
        Base64.getDecoder().decode(input.readBytes().decodeToString().trim())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> deserialize(bytes: ByteArray): T = ByteArrayInputStream(bytes).use { input ->
        ObjectInputStream(input).use { it.readObject() as T }
    }

    private data class Payload(val text: String): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
