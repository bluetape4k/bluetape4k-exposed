package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.redisson.client.codec.Codec

/** Shared fail-fast policy for Redisson codecs that can deserialize executable object graphs. */
object ExposedRedissonCodecSafety {

    /** Validates the codec carried by a repository [config]. */
    fun requireSafe(config: RedissonCacheConfig, trustedBinaryCache: Boolean) {
        requireSafe(config.codec, trustedBinaryCache)
    }

    /**
     * Rejects Fory, Kryo, and JDK object serialization codecs unless the caller explicitly trusts every cache writer
     * and payload in an isolated Redis data set.
     */
    fun requireSafe(codec: Codec, trustedBinaryCache: Boolean) {
        if (trustedBinaryCache) return

        require(!codec.isTrustedBinaryCodec()) {
            "Redisson Redis repositories no longer accept Fory/Kryo/JDK binary codecs by default. " +
                    "Use trustedBinaryCache=true only for private, trusted Redis data, or provide a reviewed custom codec."
        }
    }

    private fun Codec.isTrustedBinaryCodec(): Boolean =
        this in trustedBinaryCodecs ||
                javaClass.name.contains("Fory", ignoreCase = true) ||
                javaClass.name.contains("Kryo", ignoreCase = true) ||
                javaClass.name.contains("SerializationCodec", ignoreCase = true) ||
                (this as? ExposedRedissonDelegatingCodec)?.delegateCodec?.isTrustedBinaryCodec() == true

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

/** Internal seam that lets each consumer revalidate the preserved raw delegate with its own trust authority. */
internal interface ExposedRedissonDelegatingCodec : Codec {
    val delegateCodec: Codec
}
