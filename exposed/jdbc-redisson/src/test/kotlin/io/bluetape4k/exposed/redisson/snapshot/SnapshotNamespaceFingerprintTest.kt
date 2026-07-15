package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.StringCodec
import java.io.Serializable

class SnapshotNamespaceFingerprintTest {

    @Test
    fun `fingerprint hashes the sorted canonical UTF-8 allowlist`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())

        val canonical = canonicalSnapshotNamespaceFingerprintInput(
            backend = "redisson",
            namespace = "orders-snapshot:v1",
            keyRawClass = Long::class.java,
            snapshotRawClass = Payload::class.java,
            schemaVersion = "orders-v3",
            codec = codec,
            synchronizationStrategy = LocalCachedMapOptions.SyncStrategy.INVALIDATE,
        )
        val fieldLines = canonical.lineSequence().drop(1).filter(String::isNotBlank).toList()

        canonical.startsWith("bt4k-snapshot-fingerprint/v1\n").shouldBeTrue()
        fieldLines.map { it.substringBefore('=') } shouldBeEqualTo fieldLines.map { it.substringBefore('=') }.sorted()
        canonical.toByteArray(Charsets.UTF_8).decodeToString() shouldBeEqualTo canonical
        snapshotNamespaceFingerprint(
            backend = "redisson",
            namespace = "orders-snapshot:v1",
            keyRawClass = Long::class.java,
            snapshotRawClass = Payload::class.java,
            schemaVersion = "orders-v3",
            codec = codec,
            synchronizationStrategy = LocalCachedMapOptions.SyncStrategy.INVALIDATE,
        ) shouldBeEqualTo "1cfbbab127edfdcb742df1ee7828dec403696fb602dc447f525397990f9db7df"
    }

    @Test
    fun `fingerprint excludes connection credentials tuning and arbitrary toString output`() {
        MaliciousPayload.toStringCalls = 0
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", uuidSnapshotIdentifierPolicy())
        val baseline = fingerprint(codec, MaliciousPayload::class.java)
        val first = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig("orders-snapshot:v1", "orders-v3"),
            nearCacheMaximumSize = 1,
            maxEncodedKeyBytes = 16,
            maxBatchEncodedKeyBytes = 32,
            maxCommitEncodedKeyBytes = 64,
            maxOutstandingChunks = 1,
            maxOutstandingEncodedBytes = 64,
        )
        val second = first.copy(
            nearCacheMaximumSize = 99_999,
            maxOutstandingChunks = 999,
            maxOutstandingEncodedBytes = 999_999,
        )

        fingerprint(codec, MaliciousPayload::class.java) shouldBeEqualTo baseline
        first.snapshot.namespace shouldBeEqualTo second.snapshot.namespace
        MaliciousPayload.toStringCalls shouldBeEqualTo 0
        baseline.contains("redis://").shouldBeFalse()
        baseline.contains("username").shouldBeFalse()
        baseline.contains("credential").shouldBeFalse()
        baseline.contains("secret").shouldBeFalse()
    }

    @Test
    fun `each compatibility field changes the fingerprint`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val baseline = fingerprint(codec, Payload::class.java)
        val variants = listOf(
            snapshotNamespaceFingerprint("other", "orders-snapshot:v1", Long::class.java, Payload::class.java, "orders-v3", codec, LocalCachedMapOptions.SyncStrategy.INVALIDATE),
            snapshotNamespaceFingerprint("redisson", "other-snapshot:v1", Long::class.java, Payload::class.java, "orders-v3", codec, LocalCachedMapOptions.SyncStrategy.INVALIDATE),
            snapshotNamespaceFingerprint("redisson", "orders-snapshot:v1", Class.forName("java.lang.Long"), Payload::class.java, "orders-v3", codec, LocalCachedMapOptions.SyncStrategy.INVALIDATE),
            snapshotNamespaceFingerprint("redisson", "orders-snapshot:v1", Long::class.java, OtherPayload::class.java, "orders-v3", codec, LocalCachedMapOptions.SyncStrategy.INVALIDATE),
            snapshotNamespaceFingerprint("redisson", "orders-snapshot:v1", Long::class.java, Payload::class.java, "orders-v4", codec, LocalCachedMapOptions.SyncStrategy.INVALIDATE),
            fingerprint(snapshotRedissonCodec(StringCodec(), "json-v2", longSnapshotIdentifierPolicy()), Payload::class.java),
            fingerprint(snapshotRedissonCodec(StringCodec(), "json-v1", uuidSnapshotIdentifierPolicy()), Payload::class.java),
        )

        variants.all { it != baseline }.shouldBeTrue()
    }

    private fun fingerprint(codec: SnapshotRedissonCodec<*>, snapshotType: Class<*>): String =
        snapshotNamespaceFingerprint(
            backend = "redisson",
            namespace = "orders-snapshot:v1",
            keyRawClass = Long::class.java,
            snapshotRawClass = snapshotType,
            schemaVersion = "orders-v3",
            codec = codec,
            synchronizationStrategy = LocalCachedMapOptions.SyncStrategy.INVALIDATE,
        )

    private data class Payload(val value: String) : Serializable
    private data class OtherPayload(val value: String) : Serializable

    private class MaliciousPayload private constructor() {
        override fun toString(): String {
            toStringCalls++
            return "redis://username:credential@host/password=secret"
        }

        companion object {
            var toStringCalls: Int = 0
        }
    }
}
