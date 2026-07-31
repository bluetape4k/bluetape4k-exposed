package io.bluetape4k.exposed.redisson.snapshot

import java.nio.ByteBuffer
import java.util.UUID

/**
 * 분산 snapshot 무효화 key에 사용하는 canonical scalar identifier 정책입니다.
 *
 * 식별자는 Redis에 노출되는 infrastructure key가 됩니다. 애플리케이션은 secret, credential, PII가 아닌
 * surrogate row identifier를 사용해야 합니다. 민감하거나 composite 또는 String인 domain identifier는
 * 분산 snapshot invalidator에서 사용하기 전에 Long 또는 UUID surrogate로 매핑합니다.
 */
sealed interface SnapshotIdentifierPolicy<ID : Any>

/** canonical signed 8-byte big-endian Long identifier 정책을 반환합니다. */
fun longSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<Long> = LongSnapshotIdentifierPolicy

/** canonical 16-byte big-endian UUID identifier 정책을 반환합니다. */
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
