package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.redisson.client.codec.Codec
import org.redisson.codec.CompositeCodec
import org.redisson.codec.LZ4Codec
import org.redisson.codec.ZStdCodec
import java.lang.reflect.InaccessibleObjectException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

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

    private fun Codec.isTrustedBinaryCodec(): Boolean {
        val pending = ArrayDeque<Codec>().apply { add(this@isTrustedBinaryCodec) }
        val visited = Collections.newSetFromMap(IdentityHashMap<Codec, Boolean>())
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (visited.size > MAX_CODEC_GRAPH_NODES) return true
            if (current.isDirectTrustedBinaryCodec()) return true
            val children = current.childCodecsOrNull() ?: return true
            children.forEach(pending::addLast)
        }
        return false
    }

    private fun Codec.isDirectTrustedBinaryCodec(): Boolean =
        this in trustedBinaryCodecs ||
                javaClass.name.contains("Fory", ignoreCase = true) ||
                javaClass.name.contains("Kryo", ignoreCase = true) ||
                javaClass.name.contains("SerializationCodec", ignoreCase = true)

    private fun Codec.childCodecsOrNull(): List<Codec>? =
        when (this) {
            is ExposedRedissonDelegatingCodec -> listOf(delegateCodec)
            is CompositeCodec -> readCodecFields(
                codec = this,
                declaringClass = CompositeCodec::class.java,
                fieldNames = arrayOf("mapKeyCodec", "mapValueCodec", "valueCodec"),
            )
            is LZ4Codec -> readCodecFields(
                codec = this,
                declaringClass = LZ4Codec::class.java,
                fieldNames = arrayOf("innerCodec"),
            )
            is ZStdCodec -> readCodecFields(
                codec = this,
                declaringClass = ZStdCodec::class.java,
                fieldNames = arrayOf("innerCodec"),
            )
            else -> emptyList()
        }

    private fun readCodecFields(
        codec: Codec,
        declaringClass: Class<*>,
        fieldNames: Array<String>,
    ): List<Codec>? {
        val children = ArrayList<Codec>(fieldNames.size)
        return try {
            fieldNames.forEach { fieldName ->
                val field = declaringClass.getDeclaredField(fieldName)
                if (!field.trySetAccessible()) return null
                when (val child = field[codec]) {
                    null -> Unit
                    is Codec -> children += child
                    else -> return null
                }
            }
            children
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: InaccessibleObjectException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

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

    private const val MAX_CODEC_GRAPH_NODES = 64
}

/** Internal seam that lets each consumer revalidate the preserved raw delegate with its own trust authority. */
internal interface ExposedRedissonDelegatingCodec : Codec {
    val delegateCodec: Codec
}
