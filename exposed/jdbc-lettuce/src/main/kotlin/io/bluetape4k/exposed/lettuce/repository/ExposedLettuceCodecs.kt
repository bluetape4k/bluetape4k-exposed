package io.bluetape4k.exposed.lettuce.repository

import io.bluetape4k.redis.lettuce.codec.LettuceJsonCodecs
import io.lettuce.core.codec.RedisCodec
import java.nio.ByteBuffer

object ExposedLettuceCodecs {

    fun <E: Any> jackson3(valueType: Class<E>): RedisCodec<String, E> =
        LettuceJsonCodecs.jackson3(valueType)

    fun <E: Any> requireExplicit(): RedisCodec<String, E> =
        ExplicitLettuceCodecRequired()

    internal fun requireConfigured(codec: RedisCodec<*, *>) {
        require(codec !is ExplicitLettuceCodecRequired<*>) {
            "Lettuce Redis repositories require an explicit value codec. " +
                    "Pass ExposedLettuceCodecs.jackson3(Entity::class.java) or another reviewed RedisCodec. " +
                    "The inherited LZ4/Fory binary codec is trusted-Redis-only and is no longer selected by default."
        }
    }

    private class ExplicitLettuceCodecRequired<E: Any>: RedisCodec<String, E> {
        private fun fail(): Nothing =
            error("Explicit Lettuce Redis value codec is required.")

        override fun decodeKey(bytes: ByteBuffer?): String = fail()
        override fun decodeValue(bytes: ByteBuffer?): E = fail()
        override fun encodeKey(key: String?): ByteBuffer = fail()
        override fun encodeValue(value: E?): ByteBuffer = fail()
    }
}
