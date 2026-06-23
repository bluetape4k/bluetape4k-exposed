package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.redisson.AbstractRedissonTest
import io.bluetape4k.exposed.redisson.AbstractRedissonTest.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.redisson.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.redisson.domain.UserSchema.UserTable
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.redisson.api.RedissonClient

class RedissonRepositoryCodecSafetyTest: AbstractRedissonTest() {

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
}
