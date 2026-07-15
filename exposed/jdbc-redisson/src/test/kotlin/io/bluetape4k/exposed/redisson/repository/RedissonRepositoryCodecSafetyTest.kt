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
import org.junit.jupiter.params.provider.MethodSource
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.StringCodec

class RedissonRepositoryCodecSafetyTest: AbstractRedissonTest() {

    @Test
    fun `direct codec safety overload rejects binary codecs unless explicitly trusted`() {
        ExposedRedissonCodecSafety.requireSafe(StringCodec(), trustedBinaryCache = false)
        assertFailsWith<IllegalArgumentException> {
            ExposedRedissonCodecSafety.requireSafe(RedissonCodecs.Jdk, trustedBinaryCache = false)
        }
        ExposedRedissonCodecSafety.requireSafe(RedissonCodecs.Jdk, trustedBinaryCache = true)
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
    ): AbstractJdbcRedissonRepository<Long, UserRecord>(client, config) {
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
    ): AbstractSuspendedJdbcRedissonRepository<Long, UserRecord>(client, config) {
        override val table: UserTable = UserTable
        override fun ResultRow.toEntity(): UserRecord = error("not used")
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
        fun exposeCacheOnlyMap() = cacheOnlyMap
    }
}
