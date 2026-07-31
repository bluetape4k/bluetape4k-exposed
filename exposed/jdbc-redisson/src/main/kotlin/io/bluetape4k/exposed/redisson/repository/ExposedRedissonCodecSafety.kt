package io.bluetape4k.exposed.redisson.repository

import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.redisson.client.codec.Codec
import org.redisson.codec.CompositeCodec
import org.redisson.codec.LZ4Codec
import org.redisson.codec.LZ4CodecV2
import org.redisson.codec.ProtobufCodec
import org.redisson.codec.SnappyCodecV2
import org.redisson.codec.ZStdCodec
import java.lang.reflect.InaccessibleObjectException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** 실행 가능한 객체 그래프를 역직렬화할 수 있는 Redisson codec에 공통으로 적용하는 fail-fast 정책입니다. */
object ExposedRedissonCodecSafety {

/** 저장소 [config]에 포함된 codec을 검증합니다. */
    fun requireSafe(config: RedissonCacheConfig, trustedBinaryCache: Boolean) {
        requireSafe(config.codec, trustedBinaryCache)
    }

    /**
     * 호출자가 격리된 Redis 데이터 세트의 모든 cache writer와 payload를 명시적으로 신뢰하지 않으면
     * Fory, Kryo, JDK 객체 직렬화 codec을 거부합니다.
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
            is LZ4CodecV2 -> readCodecFields(
                codec = this,
                declaringClass = LZ4CodecV2::class.java,
                fieldNames = arrayOf("innerCodec"),
            )
            is SnappyCodecV2 -> readCodecFields(
                codec = this,
                declaringClass = SnappyCodecV2::class.java,
                fieldNames = arrayOf("innerCodec"),
            )
            is ProtobufCodec -> readCodecFields(
                codec = this,
                declaringClass = ProtobufCodec::class.java,
                fieldNames = arrayOf("blacklistCodec"),
            )
            // BaseEventCodec and MapCacheEventCodec are Redisson event decoders whose map-value encoder is unsupported;
            // they cannot serve as a repository map-value codec and are intentionally outside this consumer gate.
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

/** 각 소비자가 보존된 raw delegate를 자체 신뢰 권한으로 다시 검증할 수 있게 하는 내부 경계입니다. */
internal interface ExposedRedissonDelegatingCodec : Codec {
    val delegateCodec: Codec
}
