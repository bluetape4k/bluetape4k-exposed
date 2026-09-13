package io.bluetape4k.exposed.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.exposed.lettuce.domain.SuspendedUserRepository
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.lettuce.domain.UserSchema.UserTable
import io.bluetape4k.exposed.lettuce.domain.UserSchema.withSuspendedUserTable
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.RedisCodec
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NearCachePatternInvalidationTest: AbstractJdbcLettuceTest() {

    private companion object: KLogging() {
        fun redisClientWithScanFailure(failure: Throwable): RedisClient {
            val client = mockk<RedisClient>()
            val connection = mockk<StatefulRedisConnection<String, UserRecord>>()
            val commands = mockk<RedisAsyncCommands<String, UserRecord>>()

            every { client.connect(any<RedisCodec<String, UserRecord>>()) } returns connection
            every { connection.async() } returns commands
            every { commands.scan(any<ScanCursor>(), any<ScanArgs>()) } throws failure
            return client
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ID 전체 패턴 무효화가 DB 갱신값을 노출하고 다른 namespace는 보존한다`(nearEnabled: Boolean) = runSuspendIO {
        withSuspendedUserTable(TestDB.H2) {
            val prefix = "pattern-" + Base58.randomString(8)
            val config = LettuceCacheConfig.READ_ONLY.copy(
                keyPrefix = prefix,
                nearCacheName = "$prefix-near",
                nearCacheEnabled = nearEnabled,
            )
            val repository = SuspendedUserRepository(redisClient, config)
            val other = SuspendedUserRepository(
                redisClient, config.copy(
                    keyPrefix = "$prefix-other",
                    nearCacheName = "$prefix-other-near",
                )
            )
            try {
                val id = UserTable.selectAll().first()[UserTable.id].value
                val original = requireNotNull(other.get(id)).email
                listOf("id", "ids", "pattern", "clear").forEach { operation ->
                    val stale = requireNotNull(repository.get(id)).email
                    val fresh = "$operation@updated.example"

                    UserTable.update({ UserTable.id eq id }) { it[email] = fresh }
                    commit()
                    requireNotNull(repository.get(id)).email shouldBeEqualTo stale

                    when (operation) {
                        "id"  -> repository.invalidate(id)
                        "ids" -> repository.invalidateAll(listOf(id))
                        "pattern" -> repository.invalidateByPattern("*", 1) shouldBeEqualTo 1L
                        else  -> repository.clear()
                    }
                    repository.get(id).shouldNotBeNull().email shouldBeEqualTo fresh
                    other.get(id).shouldNotBeNull().email shouldBeEqualTo original
                }
                repository.invalidateByPattern("missing-*", 1) shouldBeEqualTo 0L
            } finally {
                withContext(NonCancellable) {
                    try {
                        repository.clear()
                    } finally {
                        try {
                            other.clear()
                        } finally {
                            try {
                                repository.close()
                            } finally {
                                other.close()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `0 이하 count는 Redis에 접근하기 전에 거부한다`() = runSuspendIO {
        val repository = SuspendedUserRepository(mockk())
        listOf(0, -1).forEach { count ->
            assertFailsWith<IllegalArgumentException> {
                repository.invalidateByPattern("*", count)
            }
        }
    }

    @Test
    fun `backing cache failure는 호출자에게 전파한다`() = runSuspendIO {
        val repository = SuspendedUserRepository(
            redisClientWithScanFailure(IllegalStateException("planned failure"))
        )

        assertFailsWith<IllegalStateException> {
            repository.invalidateByPattern("*", 1)
        }.message shouldBeEqualTo "planned failure"
    }

    @Test
    fun `backing cache cancellation은 호출자에게 전파한다`() = runSuspendIO {
        val repository = SuspendedUserRepository(
            redisClientWithScanFailure(CancellationException("planned cancellation"))
        )

        assertFailsWith<CancellationException> {
            repository.invalidateByPattern("*", 1)
        }.message shouldBeEqualTo "planned cancellation"
    }
}
