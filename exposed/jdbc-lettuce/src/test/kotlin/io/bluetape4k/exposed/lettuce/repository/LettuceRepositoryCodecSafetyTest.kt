package io.bluetape4k.exposed.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest.Companion.ENABLE_DIALECTS_METHOD
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserTable
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class LettuceRepositoryCodecSafetyTest: AbstractJdbcLettuceTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `jdbc repository requires explicit lettuce value codec`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractJdbcLettuceRepository<Long, UserRecord>(
                client = mockk<RedisClient>(),
                config = LettuceCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: IdTable<Long> = UserTable
                override fun ResultRow.toEntity(): UserRecord = error("not used")
                override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
                override fun extractId(entity: UserRecord): Long = entity.id
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspended jdbc repository requires explicit lettuce value codec`(testDB: TestDB) {
        assertFailsWith<IllegalArgumentException> {
            object: AbstractSuspendedJdbcLettuceRepository<Long, UserRecord>(
                client = mockk<RedisClient>(),
                config = LettuceCacheConfig.READ_WRITE_THROUGH
            ) {
                override val table: IdTable<Long> = UserTable
                override fun ResultRow.toEntity(): UserRecord = error("not used")
                override fun UpdateStatement.updateEntity(entity: UserRecord) = Unit
                override fun BatchInsertStatement.insertEntity(entity: UserRecord) = Unit
                override fun extractId(entity: UserRecord): Long = entity.id
            }
        }
    }
}
