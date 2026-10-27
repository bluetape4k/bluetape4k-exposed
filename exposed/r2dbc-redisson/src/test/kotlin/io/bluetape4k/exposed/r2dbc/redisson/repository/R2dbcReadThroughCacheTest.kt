package io.bluetape4k.exposed.r2dbc.redisson.repository

import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.exposed.r2dbc.redisson.AbstractR2dbcRedissonTest
import io.bluetape4k.exposed.r2dbc.redisson.domain.R2dbcUserCredentialRedissonRepository
import io.bluetape4k.exposed.r2dbc.redisson.domain.R2dbcUserRedissonRepository
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.UserCredentialsRecord
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.UserCredentialsTable
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.UserRecord
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.UserTable
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.withUserCredentialsTable
import io.bluetape4k.exposed.r2dbc.redisson.domain.UserSchema.withUserTable
import io.bluetape4k.exposed.r2dbc.redisson.repository.scenario.R2dbcReadThroughScenario
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.junit.jupiter.api.Nested
import java.util.*
import kotlin.coroutines.CoroutineContext

class R2dbcReadThroughCacheTest {

    companion object: KLoggingChannel()

    // -------------------------------------------------------------------------
    // AutoIncrement Long ID — UserTable
    // -------------------------------------------------------------------------

    abstract class R2dbcAutoIncIdReadThrough:
        AbstractR2dbcRedissonTest(),
        R2dbcReadThroughScenario<Long, UserRecord> {

        companion object: KLoggingChannel()

        override suspend fun withR2dbcEntityTable(
            testDB: TestDB,
            context: CoroutineContext,
            statement: suspend R2dbcTransaction.() -> Unit,
        ) = withUserTable(testDB, context = context, statement = statement)

        override suspend fun getExistingId(): Long = inTopLevelSuspendTransaction {
            UserTable
                .select(UserTable.id)
                .log("ID")
                .first()[UserTable.id]
                .value
        }

        override suspend fun getExistingIds(): List<Long> = inTopLevelSuspendTransaction {
            UserTable
                .select(UserTable.id)
                .map { it[UserTable.id].value }
                .log("ID")
                .toList()
        }

        override suspend fun getNonExistentId(): Long = Long.MIN_VALUE
    }

    @Nested
    inner class R2dbcAutoIncIdReadThroughRemteCache: R2dbcAutoIncIdReadThrough() {
        override val cacheConfig: RedissonCacheConfig = RedissonCacheConfig.READ_ONLY
            .copy(
                name = "r2dbc:read-through:remote:users",
                codec = RedissonCodecs.FastForyComposite
            )
        override val repository: R2dbcRedissonRepository<Long, UserRecord> by lazy {
            R2dbcUserRedissonRepository(redissonClient, config = cacheConfig)
        }
    }

    @Nested
    inner class R2dbcAutoIncIdReadThroughNearCache: R2dbcAutoIncIdReadThrough() {
        override val cacheConfig: RedissonCacheConfig = RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE
            .copy(
                name = "r2dbc:read-through:near:users",
                codec = RedissonCodecs.FastForyComposite
            )
        override val repository: R2dbcRedissonRepository<Long, UserRecord> by lazy {
            R2dbcUserRedissonRepository(redissonClient, config = cacheConfig)
        }
    }

    // -------------------------------------------------------------------------
    // Client-generated UUID ID — UserCredentialsTable
    // -------------------------------------------------------------------------

    abstract class R2dbcClientGeneratedIdReadThrough:
        AbstractR2dbcRedissonTest(),
        R2dbcReadThroughScenario<UUID, UserCredentialsRecord> {
        override suspend fun withR2dbcEntityTable(
            testDB: TestDB,
            context: CoroutineContext,
            statement: suspend R2dbcTransaction.() -> Unit,
        ) = withUserCredentialsTable(testDB, context, statement)

        override suspend fun getExistingId() = inTopLevelSuspendTransaction {
            UserCredentialsTable
                .select(UserCredentialsTable.id)
                .log("ID")
                .first()[UserCredentialsTable.id]
                .value
        }

        override suspend fun getExistingIds() = inTopLevelSuspendTransaction {
            UserCredentialsTable
                .select(UserCredentialsTable.id)
                .map { it[UserCredentialsTable.id].value }
                .log("ID")
                .toList()
        }

        override suspend fun getNonExistentId(): UUID = Uuid.V7.nextId()
    }

    @Nested
    inner class R2dbcClientGeneratedIdReadThroughRemoteCache: R2dbcClientGeneratedIdReadThrough() {
        override val cacheConfig: RedissonCacheConfig = RedissonCacheConfig.READ_ONLY
            .copy(
                name = "r2dbc:read-through:remote:user-credentials",
                codec = RedissonCodecs.FastForyComposite
            )
        override val repository by lazy {
            R2dbcUserCredentialRedissonRepository(redissonClient, config = cacheConfig)
        }
    }

    @Nested
    inner class R2dbcClientGeneratedIdReadThroughNearCache: R2dbcClientGeneratedIdReadThrough() {
        override val cacheConfig: RedissonCacheConfig = RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE
            .copy(
                name = "r2dbc:read-through:remote:user-credentials",
                codec = RedissonCodecs.FastForyComposite
            )
        override val repository by lazy {
            R2dbcUserCredentialRedissonRepository(redissonClient, config = cacheConfig)
        }
    }
}
