package io.bluetape4k.exposed.r2dbc.redisson.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.r2dbc.redisson.AbstractR2dbcRedissonTest
import io.bluetape4k.exposed.r2dbc.redisson.AbstractR2dbcRedissonTest.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.mockk.clearMocks
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.redisson.api.RedissonClient

class R2dbcRedissonRepositoryCodecSafetyTest: AbstractR2dbcRedissonTest() {

    private val redissonClient = mockk<RedissonClient>()

    @BeforeEach
    fun setUpMocks() {
        clearMocks(redissonClient)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `r2dbc repository rejects redisson binary codecs by default`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractR2dbcRedissonRepository<Long, UserSchema.UserRecord>(
                redissonClient = redissonClient,
                config = RedissonCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: UserSchema.UserTable = UserSchema.UserTable
                override suspend fun ResultRow.toEntity(): UserSchema.UserRecord = error("not used")
                override fun extractId(entity: UserSchema.UserRecord): Long = entity.id
                override fun UpdateStatement.updateEntity(entity: UserSchema.UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserSchema.UserRecord) = Unit
            }
        }
    }
}
