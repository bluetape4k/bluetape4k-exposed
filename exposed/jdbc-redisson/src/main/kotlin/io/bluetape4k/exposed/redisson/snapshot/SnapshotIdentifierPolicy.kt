package io.bluetape4k.exposed.redisson.snapshot

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Canonical scalar identifier policy for distributed snapshot invalidation keys.
 *
 * Identifiers become Redis-visible infrastructure keys. Applications must use non-secret, non-credential,
 * non-PII surrogate row identifiers. Map sensitive, composite, or String domain identifiers to a Long or UUID
 * surrogate before using a distributed snapshot invalidator.
 */
sealed interface SnapshotIdentifierPolicy<ID : Any>

/** Returns the canonical signed, eight-byte, big-endian Long identifier policy. */
fun longSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<Long> = LongSnapshotIdentifierPolicy

/** Returns the canonical sixteen-byte, big-endian UUID identifier policy. */
fun uuidSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<UUID> = UuidSnapshotIdentifierPolicy

internal sealed interface CanonicalSnapshotIdentifierPolicy<ID : Any> : SnapshotIdentifierPolicy<ID> {
    val keyEncodingId: String
    fun encodeAny(value: Any): ByteArray
    fun decode(bytes: ByteArray): ID
}

private data object LongSnapshotIdentifierPolicy : CanonicalSnapshotIdentifierPolicy<Long> {
    override val keyEncodingId: String = "bt4k-long-be-v1"

    override fun encodeAny(value: Any): ByteArray {
        require(value is Long) {
            "The Long snapshot identifier policy accepts only non-sensitive Long surrogate identifiers."
        }
        return ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    }

    override fun decode(bytes: ByteArray): Long {
        require(bytes.size == Long.SIZE_BYTES) {
            "Canonical Long identifier bytes must contain exactly ${Long.SIZE_BYTES} bytes; actual=${bytes.size}."
        }
        return ByteBuffer.wrap(bytes).long
    }
}

private data object UuidSnapshotIdentifierPolicy : CanonicalSnapshotIdentifierPolicy<UUID> {
    override val keyEncodingId: String = "bt4k-uuid-be-v1"

    override fun encodeAny(value: Any): ByteArray {
        require(value is UUID) {
            "The UUID snapshot identifier policy accepts only non-sensitive UUID surrogate identifiers."
        }
        return ByteBuffer.allocate(UUID_BYTES)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()
    }

    override fun decode(bytes: ByteArray): UUID {
        require(bytes.size == UUID_BYTES) {
            "Canonical UUID identifier bytes must contain exactly $UUID_BYTES bytes; actual=${bytes.size}."
        }
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    private const val UUID_BYTES = Long.SIZE_BYTES * 2
}
