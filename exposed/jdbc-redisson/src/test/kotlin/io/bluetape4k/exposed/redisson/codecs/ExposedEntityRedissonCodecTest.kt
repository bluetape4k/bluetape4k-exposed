package io.bluetape4k.exposed.redisson.codecs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.redisson.domain.UserSchema
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.entityCache
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.redisson.client.codec.ByteArrayCodec
import org.redisson.client.codec.Codec
import org.redisson.client.codec.StringCodec
import org.redisson.client.handler.State
import org.redisson.codec.CborJacksonCodec

/**
 * NOTE: Exposed Entity 는 Redis 의 Codec 으로는 변환이 불가능하다.
 * NOTE: 그래서 ExposedRedisRepository 는 Entity 가 아닌 data class를 캐시하도록 한다.
 */
@Disabled("Exposed Entity 는 직렬화가 불가합니다")
class ExposedEntityRedissonCodecTest: AbstractExposedTest() {

    companion object: KLogging() {
        private const val REPEAT_SIZE = 10
        private const val METHOD_SOURCE = "getRedissonBinaryCodecs"
    }

    private fun getRedissonBinaryCodecs() = listOf(
        CborJacksonCodec(),
        ByteArrayCodec(),
        StringCodec(),

        RedissonCodecs.Default,

        RedissonCodecs.Kryo5,
        RedissonCodecs.Fory,
        RedissonCodecs.FastFory,
        RedissonCodecs.Jdk,

        RedissonCodecs.Kryo5Composite,
        RedissonCodecs.ForyComposite,
        RedissonCodecs.FastForyComposite,
        RedissonCodecs.JdkComposite,

        RedissonCodecs.SnappyKryo5,
        RedissonCodecs.SnappyFory,
        RedissonCodecs.SnappyJdk,

        RedissonCodecs.SnappyKryo5Composite,
        RedissonCodecs.SnappyForyComposite,
        RedissonCodecs.SnappyFastForyComposite,
        RedissonCodecs.SnappyJdkComposite,

        RedissonCodecs.LZ4Kryo5,
        RedissonCodecs.LZ4Fory,
        RedissonCodecs.LZ4FastFory,
        RedissonCodecs.LZ4Jdk,

        RedissonCodecs.LZ4Kryo5Composite,
        RedissonCodecs.LZ4ForyComposite,
        RedissonCodecs.LZ4FastForyComposite,
        RedissonCodecs.LZ4JdkComposite,

        RedissonCodecs.ZstdKryo5,
        RedissonCodecs.ZstdFory,
        RedissonCodecs.ZstdFastFory,
        RedissonCodecs.ZstdJdk,

        RedissonCodecs.ZstdKryo5Composite,
        RedissonCodecs.ZstdForyComposite,
        RedissonCodecs.ZstdFastForyComposite,
        RedissonCodecs.ZstdJdkComposite,

        RedissonCodecs.GzipKryo5,
        RedissonCodecs.GzipFory,
        RedissonCodecs.GzipFastFory,
        RedissonCodecs.GzipJdk,

        RedissonCodecs.GzipKryo5Composite,
        RedissonCodecs.GzipForyComposite,
        RedissonCodecs.GzipFastForyComposite,
        RedissonCodecs.GzipJdkComposite,
    )

    @ParameterizedTest(name = "codec={0}")
    @MethodSource(METHOD_SOURCE)
    fun `codec for kotlin data class`(codec: Codec) {
        withTables(TestDB.H2, UserSchema.UserTable) {
            val entity = UserSchema.UserEntity.new {
                firstName = Fakers.randomString(1, 10)
                lastName = Fakers.randomString(1, 10)
                email = Fakers.faker.internet().emailAddress()
            }
            entityCache.clear()

            codec.verifyCodec(entity)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Entity<ID>, ID: Any> Codec.verifyCodec(origin: T) {
        val buf = valueEncoder.encode(origin)
        val actual = valueDecoder.decode(buf, State()) as? T
        actual!!.id shouldBeEqualTo origin.id
        // actual shouldBeEqualTo origin
    }
}
