package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.redisson.repository.ExposedRedissonDelegatingCodec
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.redisson.client.codec.Codec
import org.redisson.client.handler.State
import org.redisson.client.protocol.Decoder
import org.redisson.client.protocol.Encoder

/**
 * Map key에 library 소유의 canonical snapshot identifier encoding을 사용하는 Redisson codec입니다.
 *
 * Delegate는 map value와 map-key가 아닌 encoding만 제공합니다. Repository configuration과
 * snapshot invalidator에 같은 wrapper instance를 전달하여 두 component가 동일한 key byte를
 * 사용하도록 해야 합니다.
 */
sealed interface SnapshotRedissonCodec<ID : Any> : Codec {
    /** Delegate의 serialized value format에 대해 operator가 소유하는 compatibility version입니다. */
    val codecVersion: String
}

/**
 * [delegate]를 canonical map-key encoding으로 감쌉니다.
 *
 * Identifier는 secret, credential, PII가 아닌 surrogate key여야 합니다. Fory, Kryo, JDK object
 * serialization delegate의 trusted-binary 안전성은 각 repository 또는 invalidator consumer가
 * 독립적으로 결정합니다.
 */
fun <ID : Any> snapshotRedissonCodec(
    delegate: Codec,
    codecVersion: String,
    identifierPolicy: SnapshotIdentifierPolicy<ID>,
): SnapshotRedissonCodec<ID> {
    require(CODEC_VERSION_PATTERN.matches(codecVersion)) {
        "codecVersion must match ${CODEC_VERSION_PATTERN.pattern}."
    }
    val canonicalPolicy = identifierPolicy as? CanonicalSnapshotIdentifierPolicy<ID>
        ?: throw IllegalArgumentException("Unsupported snapshot identifier policy implementation.")
    return DefaultSnapshotRedissonCodec(delegate, codecVersion, canonicalPolicy)
}

internal interface SnapshotRedissonCodecInternals {
    val delegateClassName: String
    val canonicalKeyEncodingId: String
    fun encodeIdentifier(value: Any): ByteArray
    fun decodeIdentifier(bytes: ByteArray): Any
}

internal val SnapshotRedissonCodec<*>.snapshotKeyEncodingId: String
    get() = internals().canonicalKeyEncodingId

internal fun SnapshotRedissonCodec<*>.encodeSnapshotIdentifier(identifier: Any): ByteArray =
    internals().encodeIdentifier(identifier)

internal fun SnapshotRedissonCodec<*>.decodeSnapshotIdentifier(bytes: ByteArray): Any =
    internals().decodeIdentifier(bytes)

private fun SnapshotRedissonCodec<*>.internals(): SnapshotRedissonCodecInternals =
    this as? SnapshotRedissonCodecInternals
        ?: throw IllegalArgumentException("Unsupported snapshot Redisson codec implementation.")

private class DefaultSnapshotRedissonCodec<ID : Any>(
    override val delegateCodec: Codec,
    override val codecVersion: String,
    private val identifierPolicy: CanonicalSnapshotIdentifierPolicy<ID>,
) : SnapshotRedissonCodec<ID>, SnapshotRedissonCodecInternals, ExposedRedissonDelegatingCodec {

    override val delegateClassName: String get() = delegateCodec.javaClass.name
    override val canonicalKeyEncodingId: String get() = identifierPolicy.keyEncodingId
    override fun encodeIdentifier(value: Any): ByteArray = identifierPolicy.encodeAny(value)
    override fun decodeIdentifier(bytes: ByteArray): Any = identifierPolicy.decode(bytes)

    private val canonicalMapKeyEncoder = Encoder { value ->
        Unpooled.wrappedBuffer(encodeIdentifier(value))
    }
    private val canonicalMapKeyDecoder = Decoder<Any> { buffer: ByteBuf, _: State? ->
        val bytes = ByteArray(buffer.readableBytes())
        buffer.readBytes(bytes)
        decodeIdentifier(bytes)
    }

    override fun getMapValueDecoder(): Decoder<Any> = delegateCodec.mapValueDecoder
    override fun getMapValueEncoder(): Encoder = delegateCodec.mapValueEncoder
    override fun getMapKeyDecoder(): Decoder<Any> = canonicalMapKeyDecoder
    override fun getMapKeyEncoder(): Encoder = canonicalMapKeyEncoder
    override fun getValueDecoder(): Decoder<Any> = delegateCodec.valueDecoder
    override fun getValueEncoder(): Encoder = delegateCodec.valueEncoder
    override fun getClassLoader(): ClassLoader = delegateCodec.classLoader
}

private val CODEC_VERSION_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
