package io.bluetape4k.exposed.r2dbc.redisson.repository

import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.redisson.client.codec.Codec

object ExposedR2dbcRedissonCodecSafety {

    fun requireSafe(config: RedissonCacheConfig, trustedBinaryCache: Boolean) {
        if (trustedBinaryCache) return

        require(!config.codec.isTrustedBinaryCodec()) {
            "R2DBC Redisson Redis repositories no longer accept Fory/Kryo/JDK binary codecs by default. " +
                    "Use trustedBinaryCache=true only for private, trusted Redis data, or provide a reviewed custom codec."
        }
    }

    private fun Codec.isTrustedBinaryCodec(): Boolean =
        this in trustedBinaryCodecs ||
                javaClass.name.contains("Fory", ignoreCase = true) ||
                javaClass.name.contains("Kryo", ignoreCase = true) ||
                javaClass.name.contains("SerializationCodec", ignoreCase = true)

    private val trustedBinaryCodecs: Set<Codec>
        get() =
            setOf(
                RedissonCodecs.Default,
                RedissonCodecs.Kryo5,
                RedissonCodecs.Fory,
                RedissonCodecs.Jdk,
                RedissonCodecs.Kryo5Composite,
                RedissonCodecs.ForyComposite,
                RedissonCodecs.JdkComposite,
                RedissonCodecs.GzipKryo5,
                RedissonCodecs.GzipFory,
                RedissonCodecs.GzipJdk,
                RedissonCodecs.GzipKryo5Composite,
                RedissonCodecs.GzipForyComposite,
                RedissonCodecs.GzipJdkComposite,
                RedissonCodecs.FastFory,
                RedissonCodecs.FastForyComposite,
                RedissonCodecs.LZ4FastFory,
                RedissonCodecs.LZ4FastForyComposite,
                RedissonCodecs.ZstdFastFory,
                RedissonCodecs.ZstdFastForyComposite,
                RedissonCodecs.SnappyFastFory,
                RedissonCodecs.SnappyFastForyComposite,
                RedissonCodecs.GzipFastFory,
                RedissonCodecs.GzipFastForyComposite,
                RedissonCodecs.LZ4Kryo5,
                RedissonCodecs.LZ4Fory,
                RedissonCodecs.LZ4Jdk,
                RedissonCodecs.LZ4Kryo5Composite,
                RedissonCodecs.LZ4ForyComposite,
                RedissonCodecs.LZ4JdkComposite,
                RedissonCodecs.SnappyKryo5,
                RedissonCodecs.SnappyFory,
                RedissonCodecs.SnappyJdk,
                RedissonCodecs.SnappyKryo5Composite,
                RedissonCodecs.SnappyForyComposite,
                RedissonCodecs.SnappyJdkComposite,
                RedissonCodecs.ZstdKryo5,
                RedissonCodecs.ZstdFory,
                RedissonCodecs.ZstdJdk,
                RedissonCodecs.ZstdKryo5Composite,
                RedissonCodecs.ZstdForyComposite,
                RedissonCodecs.ZstdJdkComposite,
            )
}
