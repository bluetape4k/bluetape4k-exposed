package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.redisson.repository.ExposedRedissonCodecSafety
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.StringCodec
import java.time.Duration
import java.util.UUID

class SnapshotRedissonCodecTest {

    @Test
    fun `Long identifiers use signed eight-byte big-endian canonical vectors`() {
        val codec = snapshotRedissonCodec(
            delegate = StringCodec(),
            codecVersion = "json-v1",
            identifierPolicy = longSnapshotIdentifierPolicy(),
        )

        encodeMapKey(codec, 0L).toHex() shouldBeEqualTo "0000000000000000"
        encodeMapKey(codec, 1L).toHex() shouldBeEqualTo "0000000000000001"
        encodeMapKey(codec, -1L).toHex() shouldBeEqualTo "ffffffffffffffff"
        decodeMapKey(codec, hex("8000000000000000")) shouldBeEqualTo Long.MIN_VALUE
    }

    @Test
    fun `UUID identifiers use sixteen-byte MSB then LSB big-endian canonical vector`() {
        val codec = snapshotRedissonCodec(
            delegate = StringCodec(),
            codecVersion = "json-v1",
            identifierPolicy = uuidSnapshotIdentifierPolicy(),
        )
        val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

        encodeMapKey(codec, id).toHex() shouldBeEqualTo "00112233445566778899aabbccddeeff"
        decodeMapKey(codec, hex("00112233445566778899aabbccddeeff")) shouldBeEqualTo id
    }

    @Test
    fun `canonical decoders reject every non-exact byte length`() {
        val longCodec = snapshotRedissonCodec(StringCodec(), "v1", longSnapshotIdentifierPolicy())
        val uuidCodec = snapshotRedissonCodec(StringCodec(), "v1", uuidSnapshotIdentifierPolicy())

        listOf(0, 7, 9, 15, 17).forEach { length ->
            assertFailsWith<IllegalArgumentException> {
                if (length < 10) decodeMapKey(longCodec, ByteArray(length))
                else decodeMapKey(uuidCodec, ByteArray(length))
            }
        }
    }

    @Test
    fun `scalar policy rejects String and unsupported runtime identifiers before delegate codec`() {
        @Suppress("UNCHECKED_CAST")
        val policy = longSnapshotIdentifierPolicy() as SnapshotIdentifierPolicy<Any>
        val codec = snapshotRedissonCodec(StringCodec(), "v1", policy)

        assertFailsWith<IllegalArgumentException> { encodeMapKey(codec, "secret-token") }
        assertFailsWith<IllegalArgumentException> { encodeMapKey(codec, listOf(1L)) }
    }

    @Test
    fun `delegate supplies value and non-map-key codec behavior`() {
        val delegate = StringCodec()
        val codec = snapshotRedissonCodec(delegate, "json-v1", longSnapshotIdentifierPolicy())

        (codec.mapValueEncoder === delegate.mapValueEncoder).shouldBeTrue()
        (codec.mapValueDecoder === delegate.mapValueDecoder).shouldBeTrue()
        (codec.valueEncoder === delegate.valueEncoder).shouldBeTrue()
        (codec.valueDecoder === delegate.valueDecoder).shouldBeTrue()
        (codec.classLoader === delegate.classLoader).shouldBeTrue()
        (codec.mapKeyEncoder === delegate.mapKeyEncoder).shouldBeFalse()
        (codec.mapKeyDecoder === delegate.mapKeyDecoder).shouldBeFalse()
    }

    @Test
    fun `internal identifier encoding is identical to the wrapper map-key encoding`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())

        codec.encodeSnapshotIdentifier(-42L).toHex() shouldBeEqualTo encodeMapKey(codec, -42L).toHex()
        codec.decodeSnapshotIdentifier(codec.encodeSnapshotIdentifier(-42L)) shouldBeEqualTo -42L
        codec.snapshotKeyEncodingId shouldBeEqualTo "bt4k-long-be-v1"
    }

    @Test
    fun `codec version accepts only the bounded compatibility token`() {
        listOf("v1", "json.codec_1-2", "A".repeat(64)).forEach { version ->
            snapshotRedissonCodec(StringCodec(), version, longSnapshotIdentifierPolicy()).codecVersion shouldBeEqualTo version
        }
        listOf("", " ", "v/1", "v:1", "A".repeat(65)).forEach { version ->
            assertFailsWith<IllegalArgumentException> {
                snapshotRedissonCodec(StringCodec(), version, longSnapshotIdentifierPolicy())
            }
        }
    }

    @Test
    fun `binary delegate trust is decided independently at each consumer boundary`() {
        listOf(RedissonCodecs.Fory, RedissonCodecs.Kryo5, RedissonCodecs.Jdk).forEach { delegate ->
            val codec = snapshotRedissonCodec(delegate, "v1", longSnapshotIdentifierPolicy())

            assertFailsWith<IllegalArgumentException> {
                ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache = false)
            }
            ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache = true)
        }
    }

    @Test
    fun `invalidator configuration revalidates wrapper delegate with its own trust authority`() {
        val codec = snapshotRedissonCodec(RedissonCodecs.Kryo5, "kryo-v1", longSnapshotIdentifierPolicy())
        val config = JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig())

        assertFailsWith<IllegalArgumentException> { config.requireSafeCodec(codec) }
        config.copy(trustedBinaryCache = true).requireSafeCodec(codec)
    }

    @Test
    fun `invalidator configuration defaults are safe and exact`() {
        val config = JdbcRedissonSnapshotInvalidatorConfig(snapshot = snapshotConfig())

        config.nearCacheMaximumSize shouldBeEqualTo 10_000
        config.maxEncodedKeyBytes shouldBeEqualTo 4 * 1024
        config.maxBatchEncodedKeyBytes shouldBeEqualTo 64 * 1024
        config.maxCommitEncodedKeyBytes shouldBeEqualTo 256 * 1024
        config.maxOutstandingChunks shouldBeEqualTo 64
        config.maxOutstandingEncodedBytes shouldBeEqualTo 4L * 1024 * 1024
        config.namespaceVerificationTimeout shouldBeEqualTo Duration.ofSeconds(2)
        config.multiNode.shouldBeTrue()
        config.synchronizationStrategy shouldBeEqualTo LocalCachedMapOptions.SyncStrategy.INVALIDATE
        config.reconnectionStrategy shouldBeEqualTo LocalCachedMapOptions.ReconnectionStrategy.CLEAR
        config.trustedBinaryCache.shouldBeFalse()
    }

    @Test
    fun `invalidator configuration rejects every non-positive cap and timeout`() {
        val valid = JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig())

        listOf(0, -1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { valid.copy(nearCacheMaximumSize = invalid) }
            assertFailsWith<IllegalArgumentException> { valid.copy(maxEncodedKeyBytes = invalid) }
            assertFailsWith<IllegalArgumentException> { valid.copy(maxBatchEncodedKeyBytes = invalid) }
            assertFailsWith<IllegalArgumentException> { valid.copy(maxCommitEncodedKeyBytes = invalid) }
            assertFailsWith<IllegalArgumentException> { valid.copy(maxOutstandingChunks = invalid) }
            assertFailsWith<IllegalArgumentException> { valid.copy(maxOutstandingEncodedBytes = invalid.toLong()) }
        }
        listOf(Duration.ZERO, Duration.ofNanos(-1)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { valid.copy(namespaceVerificationTimeout = invalid) }
        }
    }

    @Test
    fun `invalidator configuration rejects a timeout that cannot be represented in nanoseconds`() {
        val valid = JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig())

        assertFailsWith<IllegalArgumentException> {
            valid.copy(namespaceVerificationTimeout = Duration.ofSeconds(Long.MAX_VALUE))
        }
    }

    @Test
    fun `invalidator configuration accepts the positive lower bound for every cap and timeout`() {
        val config = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = snapshotConfig(),
            nearCacheMaximumSize = 1,
            maxEncodedKeyBytes = 1,
            maxBatchEncodedKeyBytes = 1,
            maxCommitEncodedKeyBytes = 1,
            maxOutstandingChunks = 1,
            maxOutstandingEncodedBytes = 1,
            namespaceVerificationTimeout = Duration.ofNanos(1),
        )

        config.nearCacheMaximumSize shouldBeEqualTo 1
        config.maxEncodedKeyBytes shouldBeEqualTo 1
        config.maxBatchEncodedKeyBytes shouldBeEqualTo 1
        config.maxCommitEncodedKeyBytes shouldBeEqualTo 1
        config.maxOutstandingChunks shouldBeEqualTo 1
        config.maxOutstandingEncodedBytes shouldBeEqualTo 1L
        config.namespaceVerificationTimeout shouldBeEqualTo Duration.ofNanos(1)
    }

    @Test
    fun `batch encoded key cap cannot exceed commit encoded key cap`() {
        assertFailsWith<IllegalArgumentException> {
            JdbcRedissonSnapshotInvalidatorConfig(
                snapshot = snapshotConfig(),
                maxBatchEncodedKeyBytes = 257,
                maxCommitEncodedKeyBytes = 256,
            )
        }
    }

    @ParameterizedTest
    @EnumSource(LocalCachedMapOptions.SyncStrategy::class)
    fun `synchronization strategy permits invalidation and only single-node none`(
        strategy: LocalCachedMapOptions.SyncStrategy,
    ) {
        when (strategy) {
            LocalCachedMapOptions.SyncStrategy.INVALIDATE ->
                JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig(), synchronizationStrategy = strategy)

            LocalCachedMapOptions.SyncStrategy.NONE -> {
                assertFailsWith<IllegalArgumentException> {
                    JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig(), synchronizationStrategy = strategy)
                }
                JdbcRedissonSnapshotInvalidatorConfig(
                    snapshot = snapshotConfig(),
                    multiNode = false,
                    synchronizationStrategy = strategy,
                )
            }

            LocalCachedMapOptions.SyncStrategy.UPDATE ->
                assertFailsWith<IllegalArgumentException> {
                    JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig(), synchronizationStrategy = strategy)
                }
        }
    }

    @ParameterizedTest
    @EnumSource(LocalCachedMapOptions.ReconnectionStrategy::class)
    fun `reconnection strategy permits clear only`(strategy: LocalCachedMapOptions.ReconnectionStrategy) {
        if (strategy == LocalCachedMapOptions.ReconnectionStrategy.CLEAR) {
            JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig(), reconnectionStrategy = strategy)
        } else {
            assertFailsWith<IllegalArgumentException> {
                JdbcRedissonSnapshotInvalidatorConfig(snapshotConfig(), reconnectionStrategy = strategy)
            }
        }
    }

    private fun snapshotConfig() = SnapshotCacheConfig("orders-snapshot:v1", "orders-v1")

    private fun encodeMapKey(codec: SnapshotRedissonCodec<*>, value: Any): ByteArray {
        val buffer = codec.mapKeyEncoder.encode(value)
        return try {
            ByteArray(buffer.readableBytes()).also(buffer::readBytes)
        } finally {
            buffer.release()
        }
    }

    private fun decodeMapKey(codec: SnapshotRedissonCodec<*>, bytes: ByteArray): Any {
        val buffer = io.netty.buffer.Unpooled.wrappedBuffer(bytes)
        return try {
            codec.mapKeyDecoder.decode(buffer, null)
        } finally {
            buffer.release()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
