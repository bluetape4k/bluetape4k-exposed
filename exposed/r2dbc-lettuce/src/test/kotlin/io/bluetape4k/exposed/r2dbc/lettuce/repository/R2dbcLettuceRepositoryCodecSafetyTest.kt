package io.bluetape4k.exposed.r2dbc.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.r2dbc.lettuce.AbstractR2dbcLettuceTest
import io.bluetape4k.exposed.r2dbc.lettuce.AbstractR2dbcLettuceTest.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.UserTable
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class R2dbcLettuceRepositoryCodecSafetyTest: AbstractR2dbcLettuceTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `r2dbc repository requires explicit lettuce value codec`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractR2dbcLettuceRepository<Long, UserRecord>(
                client = mockk<RedisClient>(),
                config = LettuceCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: IdTable<Long> = UserTable
                override suspend fun ResultRow.toEntity(): UserRecord = error("not used")
                override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
                override fun extractId(entity: UserRecord): Long = entity.id
            }
        }
    }
}
