package io.bluetape4k.exposed.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserTable
import io.bluetape4k.exposed.lettuce.domain.UserSchema.withSuspendedUserTable
import io.bluetape4k.exposed.lettuce.domain.UserSchema.withUserTable
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.RedisCodec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test

class LettuceRepositoryContractTest: AbstractExposedTest() {

    @Test
    fun `jdbc putAll rejects non-positive batch size before touching cache`() {
        val repository = JdbcProbeRepository(mockk())

        assertFailsWith<IllegalArgumentException> {
            repository.putAll(emptyMap(), batchSize = 0)
        }
    }

    @Test
    fun `suspended jdbc putAll rejects non-positive batch size before touching cache`() = runSuspendIO {
        val repository = SuspendedJdbcProbeRepository(mockk())

        assertFailsWith<IllegalArgumentException> {
            repository.putAll(emptyMap(), batchSize = 0)
        }
    }

    @Test
    fun `jdbc findAll warms cache without invoking database writer`() {
        val repository = JdbcProbeRepository(redisClientWithSyncSetFailure())

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

    @Test
    fun `suspended jdbc findAll propagates cache warming cancellation`() = runSuspendIO {
        val repository = SuspendedJdbcProbeRepository(redisClientWithAsyncSetFailure(CancellationException("cancel warm")))

        withSuspendedUserTable(TestDB.H2) {
            val previousDefault = TransactionManager.defaultDatabase
            try {
                TransactionManager.defaultDatabase = db
                assertFailsWith<CancellationException> {
                    repository.findAll(sortBy = UserTable.id, sortOrder = SortOrder.ASC) { UserTable.id greater 0L }
                }.message shouldBeEqualTo "cancel warm"
            } finally {
                TransactionManager.defaultDatabase = previousDefault
            }
        }
    }

    private class JdbcProbeRepository(
        client: RedisClient,
    ): AbstractJdbcLettuceRepository<Long, UserRecord>(
        client = client,
        valueCodec = ExposedLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
        var updateCount = 0

        override val table: IdTable<Long> = UserTable
        override fun ResultRow.toEntity() = toUserRecord()
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) {
            updateCount++
            this[UserTable.firstName] = entity.firstName
        }
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
            this[UserTable.firstName] = entity.firstName
        }
    }

    private class SuspendedJdbcProbeRepository(
        client: RedisClient,
    ): AbstractSuspendedJdbcLettuceRepository<Long, UserRecord>(
        client = client,
        valueCodec = ExposedLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
        override val table: IdTable<Long> = UserTable
        override fun ResultRow.toEntity() = toUserRecord()
        override fun extractId(entity: UserRecord): Long = entity.id
        override fun UpdateStatement.updateEntity(entity: UserRecord) {
            this[UserTable.firstName] = entity.firstName
        }
        override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
            this[UserTable.firstName] = entity.firstName
        }
    }

    private companion object {
        fun ResultRow.toUserRecord() = UserRecord(
            id = this[UserTable.id].value,
            firstName = this[UserTable.firstName],
            lastName = this[UserTable.lastName],
            email = this[UserTable.email],
            createdAt = this[UserTable.createdAt],
        )

        fun redisClientWithSyncSetFailure(): RedisClient {
            val client = mockk<RedisClient>()
            val connection = mockk<StatefulRedisConnection<String, UserRecord>>()
            val commands = mockk<RedisCommands<String, UserRecord>>()
            every { client.connect(any<RedisCodec<String, UserRecord>>()) } returns connection
            every { connection.sync() } returns commands
            every { connection.setAutoFlushCommands(any()) } returns Unit
            every { connection.flushCommands() } returns Unit
            every { commands.set(any(), any(), any()) } throws IllegalStateException("redis unavailable")
            return client
        }

        fun redisClientWithAsyncSetFailure(failure: RuntimeException): RedisClient {
            val client = mockk<RedisClient>()
            val connection = mockk<StatefulRedisConnection<String, UserRecord>>()
            val commands = mockk<RedisAsyncCommands<String, UserRecord>>()
            every { client.connect(any<RedisCodec<String, UserRecord>>()) } returns connection
            every { connection.async() } returns commands
            every { connection.setAutoFlushCommands(any()) } returns Unit
            every { connection.flushCommands() } returns Unit
            every { commands.set(any(), any(), any()) } throws failure
            return client
        }
    }
}
