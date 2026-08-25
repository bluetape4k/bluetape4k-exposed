package io.bluetape4k.exposed.r2dbc.redisson.repository

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.r2dbc.redisson.AbstractR2dbcRedissonTest
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import java.io.Serializable

/** repository close가 loader/writer lifecycle child까지 종료하는지 검증합니다. */
class R2dbcRedissonRepositoryLifecycleTest: AbstractR2dbcRedissonTest() {

    private data class TestEntity(val id: Long): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private object TestTable: LongIdTable("r2dbc_redisson_lifecycle_test")

    @Test
    fun `repository closeAndJoin은 loader와 writer lifecycle child를 종료한다`() = runSuspendIO {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = object: AbstractR2dbcRedissonRepository<Long, TestEntity>(
                redissonClient = mockk<RedissonClient>(),
                config = RedissonCacheConfig.READ_WRITE_THROUGH.copy(name = "r2dbc-redisson:lifecycle-test"),
                trustedBinaryCache = true,
                scope = parentScope,
            ) {
                override val table: LongIdTable = TestTable

                override suspend fun ResultRow.toEntity(): TestEntity = TestEntity(this[TestTable.id].value)

                override fun extractId(entity: TestEntity): Long = entity.id

                override fun UpdateStatement.updateEntity(entity: TestEntity) = Unit

                override fun BatchInsertStatement.insertEntity(entity: TestEntity) = Unit

                fun initializeAdapters() {
                    r2dbcEntityMapLoader
                    r2dbcEntityMapWriter
                }
            }

            repository.initializeAdapters()
            repository.closeAndJoin()

            parentScope.coroutineContext[Job]?.children?.toList().orEmpty().any { it.isActive }.shouldBeFalse()
        } finally {
            parentScope.cancel()
        }
    }
}
