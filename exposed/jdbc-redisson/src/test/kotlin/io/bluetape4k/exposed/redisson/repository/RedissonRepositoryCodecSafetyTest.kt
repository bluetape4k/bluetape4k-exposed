package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.redisson.AbstractRedissonTest
import io.bluetape4k.exposed.redisson.AbstractRedissonTest.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.redisson.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.redisson.domain.UserSchema.UserTable
import io.bluetape4k.exposed.redisson.snapshot.longSnapshotIdentifierPolicy
import io.bluetape4k.exposed.redisson.snapshot.snapshotRedissonCodec
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.Codec
import org.redisson.client.codec.StringCodec
import org.redisson.client.protocol.Decoder
import org.redisson.client.protocol.Encoder
import org.redisson.codec.CompositeCodec
import org.redisson.codec.LZ4Codec
import org.redisson.codec.LZ4CodecV2
import org.redisson.codec.ProtobufCodec
import org.redisson.codec.SnappyCodecV2
import org.redisson.codec.ZStdCodec
import java.util.stream.Stream

class RedissonRepositoryCodecSafetyTest: AbstractRedissonTest() {

    companion object {
        private const val UNSAFE_NESTED_CODECS_METHOD = "unsafeNestedCodecs"
        private const val SAFE_NESTED_CODECS_METHOD = "safeNestedCodecs"

        @JvmStatic
        fun unsafeNestedCodecs(): Stream<Arguments> =
            Stream.of(
                Arguments.of("composite-fory", CompositeCodec(StringCodec(), RedissonCodecs.Fory, StringCodec())),
                Arguments.of("composite-kryo", CompositeCodec(StringCodec(), RedissonCodecs.Kryo5, StringCodec())),
                Arguments.of("composite-jdk", CompositeCodec(StringCodec(), RedissonCodecs.Jdk, StringCodec())),
                Arguments.of("lz4-fory", LZ4Codec(RedissonCodecs.Fory)),
                Arguments.of("lz4-kryo", LZ4Codec(RedissonCodecs.Kryo5)),
                Arguments.of("lz4-jdk", LZ4Codec(RedissonCodecs.Jdk)),
                Arguments.of("zstd-fory", ZStdCodec(RedissonCodecs.Fory)),
                Arguments.of("zstd-kryo", ZStdCodec(RedissonCodecs.Kryo5)),
                Arguments.of("zstd-jdk", ZStdCodec(RedissonCodecs.Jdk)),
                Arguments.of("lz4-v2-default-kryo", LZ4CodecV2()),
                Arguments.of("lz4-v2-fory", LZ4CodecV2(RedissonCodecs.Fory)),
                Arguments.of("lz4-v2-kryo", LZ4CodecV2(RedissonCodecs.Kryo5)),
                Arguments.of("lz4-v2-jdk", LZ4CodecV2(RedissonCodecs.Jdk)),
                Arguments.of("snappy-v2-default-kryo", SnappyCodecV2()),
                Arguments.of("snappy-v2-fory", SnappyCodecV2(RedissonCodecs.Fory)),
                Arguments.of("snappy-v2-kryo", SnappyCodecV2(RedissonCodecs.Kryo5)),
                Arguments.of("snappy-v2-jdk", SnappyCodecV2(RedissonCodecs.Jdk)),
                Arguments.of("protobuf-fory-fallback", ProtobufCodec(String::class.java, RedissonCodecs.Fory)),
                Arguments.of("protobuf-kryo-fallback", ProtobufCodec(String::class.java, RedissonCodecs.Kryo5)),
                Arguments.of("protobuf-jdk-fallback", ProtobufCodec(String::class.java, RedissonCodecs.Jdk)),
            )

        @JvmStatic
        fun safeNestedCodecs(): Stream<Arguments> =
            Stream.of(
                Arguments.of("composite-string", CompositeCodec(StringCodec(), StringCodec(), StringCodec())),
                Arguments.of("lz4-v2-string", LZ4CodecV2(StringCodec())),
                Arguments.of("snappy-v2-string", SnappyCodecV2(StringCodec())),
                Arguments.of("protobuf-string-fallback", ProtobufCodec(String::class.java, StringCodec())),
            )
    }

    @Test
    fun `direct codec safety overload rejects binary codecs unless explicitly trusted`() {
        ExposedRedissonCodecSafety.requireSafe(StringCodec(), trustedBinaryCache = false)
        assertFailsWith<IllegalArgumentException> {
            ExposedRedissonCodecSafety.requireSafe(RedissonCodecs.Jdk, trustedBinaryCache = false)
        }
        ExposedRedissonCodecSafety.requireSafe(RedissonCodecs.Jdk, trustedBinaryCache = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(UNSAFE_NESTED_CODECS_METHOD)
    fun `raw custom Redisson wrappers cannot hide unsafe nested codecs`(name: String, codec: Codec) {
        assertFailsWith<IllegalArgumentException> {
            ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache = false)
        }
        ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(UNSAFE_NESTED_CODECS_METHOD)
    fun `snapshot wrapper cannot hide unsafe nested Redisson codecs`(name: String, codec: Codec) {
        val snapshotCodec = snapshotRedissonCodec(codec, "nested-v1", longSnapshotIdentifierPolicy())

        assertFailsWith<IllegalArgumentException> {
            ExposedRedissonCodecSafety.requireSafe(snapshotCodec, trustedBinaryCache = false)
        }
        ExposedRedissonCodecSafety.requireSafe(snapshotCodec, trustedBinaryCache = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(SAFE_NESTED_CODECS_METHOD)
    fun `safe reviewed custom wrapper codec remains accepted`(name: String, safe: Codec) {
        ExposedRedissonCodecSafety.requireSafe(safe, trustedBinaryCache = false)
        ExposedRedissonCodecSafety.requireSafe(
            snapshotRedissonCodec(safe, "safe-v1", longSnapshotIdentifierPolicy()),
            trustedBinaryCache = false,
        )
    }

    @Test
    fun `delegate traversal terminates safely on an identity cycle`() {
        ExposedRedissonCodecSafety.requireSafe(CyclicDelegatingCodec(), trustedBinaryCache = false)
    }

    @Test
    fun `delegate traversal fails closed when wrapper depth exceeds its bound`() {
        var codec: Codec = StringCodec()
        repeat(65) {
            codec = snapshotRedissonCodec(codec, "depth-v1", longSnapshotIdentifierPolicy())
        }

        assertFailsWith<IllegalArgumentException> {
            ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache = false)
        }
    }

    @Test
    fun `jdbc repository passes the same snapshot codec wrapper to local cached map options`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val client = mockk<RedissonClient>()
        val options = slot<LocalCachedMapOptions<Long, UserRecord?>>()
        every { client.getLocalCachedMap(capture(options)) } returns mockk<RLocalCachedMap<Long, UserRecord?>>()
        val repository = TestJdbcRepository(
            client,
            RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE.copy(name = "snapshot-identity", codec = codec),
        )

        repository.exposeCacheOnlyMap()

        (options.captured.javaClass.getMethod("getCodec").invoke(options.captured) === codec).shouldBeTrue()
        verify(exactly = 1) { client.getLocalCachedMap(any<LocalCachedMapOptions<Long, UserRecord?>>()) }
    }

    @Test
    fun `suspended repository passes the same snapshot codec wrapper to local cached map options`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val client = mockk<RedissonClient>()
        val options = slot<LocalCachedMapOptions<Long, UserRecord?>>()
        every { client.getLocalCachedMap(capture(options)) } returns mockk<RLocalCachedMap<Long, UserRecord?>>()
        val repository = TestSuspendedRepository(
            client,
            RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE.copy(name = "snapshot-identity", codec = codec),
        )

        repository.exposeCacheOnlyMap()

        (options.captured.javaClass.getMethod("getCodec").invoke(options.captured) === codec).shouldBeTrue()
        verify(exactly = 1) { client.getLocalCachedMap(any<LocalCachedMapOptions<Long, UserRecord?>>()) }
    }

    @Test
    fun `jdbc repository independently rejects an unsafe delegate hidden by snapshot wrapper`() {
        val delegate = CompositeCodec(StringCodec(), RedissonCodecs.Kryo5, RedissonCodecs.Jdk)
        val codec = snapshotRedissonCodec(delegate, "binary-v1", longSnapshotIdentifierPolicy())

        assertFailsWith<IllegalArgumentException> {
            TestJdbcRepository(
                mockk<RedissonClient>(),
                RedissonCacheConfig.READ_ONLY.copy(codec = codec),
            )
        }
        TestJdbcRepository(
            mockk<RedissonClient>(),
            RedissonCacheConfig.READ_ONLY.copy(codec = codec),
            trustedBinaryCache = true,
        )
    }

    @Test
    fun `suspended repository independently rejects an unsafe delegate hidden by snapshot wrapper`() {
        val codec = snapshotRedissonCodec(LZ4Codec(RedissonCodecs.Fory), "fory-v1", longSnapshotIdentifierPolicy())

        assertFailsWith<IllegalArgumentException> {
            TestSuspendedRepository(
                mockk<RedissonClient>(),
                RedissonCacheConfig.READ_ONLY.copy(codec = codec),
            )
        }
        TestSuspendedRepository(
            mockk<RedissonClient>(),
            RedissonCacheConfig.READ_ONLY.copy(codec = codec),
            trustedBinaryCache = true,
        )
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `jdbc repository rejects redisson binary codecs by default`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractJdbcRedissonRepository<Long, UserRecord>(
                redissonClient = mockk<RedissonClient>(),
                config = RedissonCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: UserTable = UserTable
                override fun ResultRow.toEntity(): UserRecord = error("not used")
                override fun extractId(entity: UserRecord): Long = entity.id
                override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended jdbc repository rejects redisson binary codecs by default`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractSuspendedJdbcRedissonRepository<Long, UserRecord>(
                redissonClient = mockk<RedissonClient>(),
                config = RedissonCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: UserTable = UserTable
                override fun ResultRow.toEntity(): UserRecord = error("not used")
                override fun extractId(entity: UserRecord): Long = entity.id
                override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
            }
        }
    }

    private class TestJdbcRepository(
        client: RedissonClient,
        config: RedissonCacheConfig,
        trustedBinaryCache: Boolean = false,
    ): AbstractJdbcRedissonRepository<Long, UserRecord>(client, config, trustedBinaryCache) {
        override val table: UserTable = UserTable
        override fun ResultRow.toEntity(): UserRecord = error("not used")
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
        fun exposeCacheOnlyMap() = cacheOnlyMap
    }

    private class TestSuspendedRepository(
        client: RedissonClient,
        config: RedissonCacheConfig,
        trustedBinaryCache: Boolean = false,
    ): AbstractSuspendedJdbcRedissonRepository<Long, UserRecord>(client, config, trustedBinaryCache) {
        override val table: UserTable = UserTable
        override fun ResultRow.toEntity(): UserRecord = error("not used")
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
        fun exposeCacheOnlyMap() = cacheOnlyMap
    }

    private class CyclicDelegatingCodec : ExposedRedissonDelegatingCodec {
        private val safe = StringCodec()
        override val delegateCodec: Codec get() = this
        override fun getMapValueDecoder(): Decoder<Any> = safe.mapValueDecoder
        override fun getMapValueEncoder(): Encoder = safe.mapValueEncoder
        override fun getMapKeyDecoder(): Decoder<Any> = safe.mapKeyDecoder
        override fun getMapKeyEncoder(): Encoder = safe.mapKeyEncoder
        override fun getValueDecoder(): Decoder<Any> = safe.valueDecoder
        override fun getValueEncoder(): Encoder = safe.valueEncoder
        override fun getClassLoader(): ClassLoader = safe.classLoader
    }
}
