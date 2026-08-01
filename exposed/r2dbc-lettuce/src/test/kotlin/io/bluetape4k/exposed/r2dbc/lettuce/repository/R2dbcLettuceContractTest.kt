package io.bluetape4k.exposed.r2dbc.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.UserTable
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.toUserRecord
import io.bluetape4k.exposed.r2dbc.lettuce.domain.UserSchema.withUserTable
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.RedisCodec
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.Test

class R2dbcLettuceContractTest: AbstractExposedR2dbcTest() {

    @Test
    fun `r2dbc putAll rejects non-positive batch size before touching cache`() = runSuspendIO {
        val repository = ProbeRepository(mockk())

        assertFailsWith<IllegalArgumentException> {
            repository.putAll(emptyMap(), batchSize = 0)
        }
    }

    @Test
    fun `r2dbc findAll warms cache without invoking database writer`() = runSuspendIO {
        val repository = ProbeRepository(redisClientWithAsyncSetFailure())

        withUserTable(TestDB.H2) {
            val previousDefault = TransactionManager.defaultDatabase
            try {
                TransactionManager.defaultDatabase = db
                repository.findAll(sortBy = UserTable.id, sortOrder = SortOrder.ASC) { UserTable.id greater 0L }
            } finally {
                TransactionManager.defaultDatabase = previousDefault
            }
        }

        repository.updateCount shouldBeEqualTo 0
    }

    private class ProbeRepository(
        client: RedisClient,
    ): AbstractR2dbcLettuceRepository<Long, UserRecord>(
        client = client,
        valueCodec = ExposedR2dbcLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
        var updateCount = 0

        override val table: IdTable<Long> = UserTable
        override suspend fun ResultRow.toEntity(): UserRecord = toUserRecord()
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) {
            updateCount++
            this[UserTable.firstName] = entity.firstName
        }
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
            this[UserTable.firstName] = entity.firstName
        }
    }

    private companion object {
        fun redisClientWithAsyncSetFailure(): RedisClient {
            val client = mockk<RedisClient>()
            val connection = mockk<StatefulRedisConnection<String, UserRecord>>()
            val commands = mockk<RedisAsyncCommands<String, UserRecord>>()
            every { client.connect(any<RedisCodec<String, UserRecord>>()) } returns connection
            every { connection.async() } returns commands
            every { connection.setAutoFlushCommands(any()) } returns Unit
            every { connection.flushCommands() } returns Unit
            every { commands.set(any(), any(), any()) } throws IllegalStateException("redis unavailable")
            return client
        }
    }
}
