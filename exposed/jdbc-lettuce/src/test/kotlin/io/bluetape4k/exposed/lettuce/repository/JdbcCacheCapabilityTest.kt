package io.bluetape4k.exposed.lettuce.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.cache.CacheMode
import io.bluetape4k.exposed.lettuce.domain.ItemRepository
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class JdbcCacheCapabilityTest {

    @Test
    fun `지원하지 않는 near cache 설정은 Redis 연결 전에 거부한다`() {
        val client = mockk<RedisClient>()
        listOf(
            LettuceCacheConfig.READ_ONLY_WITH_NEAR_CACHE,
            LettuceCacheConfig.READ_WRITE_THROUGH_WITH_NEAR_CACHE,
            LettuceCacheConfig.WRITE_BEHIND_WITH_NEAR_CACHE,
        ).forEach { config ->
            val failure = assertFailsWith<IllegalArgumentException> { ItemRepository(client, config) }
            failure.message shouldBeEqualTo
                "AbstractJdbcLettuceRepository does not support nearCacheEnabled; " +
                "use AbstractSuspendedJdbcLettuceRepository"
        }
        verify { client wasNot Called }
    }

    @Test
    fun `지원하는 preset은 연결 없이 REMOTE capability를 보고한다`() {
        val client = mockk<RedisClient>()
        listOf(
            LettuceCacheConfig.READ_ONLY,
            LettuceCacheConfig.READ_WRITE_THROUGH,
            LettuceCacheConfig.WRITE_BEHIND
        ).forEach { config ->
            ItemRepository(client, config).cacheMode shouldBeEqualTo CacheMode.REMOTE
        }
        verify { client wasNot Called }
    }
}
