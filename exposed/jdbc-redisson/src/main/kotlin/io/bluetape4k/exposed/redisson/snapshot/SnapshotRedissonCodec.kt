package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.redisson.repository.ExposedRedissonDelegatingCodec
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.redisson.client.codec.Codec
import org.redisson.client.handler.State
import org.redisson.client.protocol.Decoder
import org.redisson.client.protocol.Encoder

/**
 * Redisson codec whose map keys use a library-owned canonical snapshot identifier encoding.
 *
 * The delegate supplies map values and non-map-key encoding only. The same wrapper instance must be passed to the
 * existing repository configuration and the snapshot invalidator so both components use identical key bytes.
 */
sealed interface SnapshotRedissonCodec<ID : Any> : Codec {
    /** Operator-owned compatibility version for the delegate's serialized value format. */
    val codecVersion: String
}

/**
 * Wraps [delegate] with canonical map-key encoding.
 *
 * Identifiers must be non-secret, non-credential, non-PII surrogate keys. Fory, Kryo, and JDK object serialization
 * delegates remain subject to each repository or invalidator consumer's independent trusted-binary safety decision.
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
