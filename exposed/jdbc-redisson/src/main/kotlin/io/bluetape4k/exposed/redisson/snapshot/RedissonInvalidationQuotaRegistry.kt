package io.bluetape4k.exposed.redisson.snapshot

import kotlinx.atomicfu.atomic
import org.redisson.api.RedissonClient
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Structural health for invalidation work admitted by one Redisson client identity.
 *
 * The report intentionally contains no identifiers, Redis endpoints, credentials, or command payloads.
 *
 * @property maxOutstandingChunks configured maximum number of admitted chunks
 * @property outstandingChunks currently admitted chunks whose completion has not released its lease
 * @property maxOutstandingEncodedBytes configured maximum canonical identifier bytes held by admitted chunks
 * @property outstandingEncodedBytes canonical identifier bytes currently held by admitted chunks
 * @property rejectedChunks total chunks rejected by the bounded admission quota
 * @property saturated whether either current outstanding value has reached its configured maximum
 */
data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

/** Owns one weak-identity quota per caller-owned [RedissonClient]. */
internal class RedissonInvalidationQuotaRegistry {
    private val lock = ReentrantLock()
    private val staleClients = ReferenceQueue<RedissonClient>()
    private val quotas = HashMap<RedissonClientIdentityWeakReference, RedissonInvalidationQuota>()

    /**
     * Returns the quota pinned to [redissonClient], creating it on the first valid lookup.
     *
     * Later lookups for the exact same client identity must use the originally pinned limits.
     */
    fun quotaFor(
        redissonClient: RedissonClient,
        maxOutstandingChunks: Int,
        maxOutstandingEncodedBytes: Long,
    ): RedissonInvalidationQuota {
        require(maxOutstandingChunks > 0) {
            "maxOutstandingChunks[$maxOutstandingChunks] must be positive."
        }
        require(maxOutstandingEncodedBytes > 0L) {
            "maxOutstandingEncodedBytes[$maxOutstandingEncodedBytes] must be positive."
        }

        return lock.withLock {
            expungeStaleClients()
            val lookup = RedissonClientIdentityWeakReference(redissonClient)
            val existing = quotas[lookup]
            if (existing != null) {
                existing.requireMatchingLimits(maxOutstandingChunks, maxOutstandingEncodedBytes)
                existing
            } else {
                RedissonInvalidationQuota(maxOutstandingChunks, maxOutstandingEncodedBytes).also { created ->
                    quotas[RedissonClientIdentityWeakReference(redissonClient, staleClients)] = created
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun expungeStaleClients() {
        while (true) {
            val stale = staleClients.poll() as? RedissonClientIdentityWeakReference ?: return
            quotas.remove(stale)
        }
    }
}

/** Bounded chunk-and-byte quota shared by invalidators using one client identity. */
internal class RedissonInvalidationQuota(
    private val maxOutstandingChunks: Int,
    private val maxOutstandingEncodedBytes: Long,
) {
    private val lock = ReentrantLock()
    private var outstandingChunks = 0
    private var outstandingEncodedBytes = 0L
    private var rejectedChunks = 0L

    /** Atomically admits one chunk and its actual canonical encoded byte count. */
    fun tryAdmit(encodedBytes: Long): RedissonInvalidationQuotaLease? {
        require(encodedBytes > 0L) { "encodedBytes[$encodedBytes] must be positive." }

        return lock.withLock {
            val chunkCapacityAvailable = outstandingChunks < maxOutstandingChunks
            val byteCapacityAvailable = encodedBytes <= maxOutstandingEncodedBytes - outstandingEncodedBytes
            if (!chunkCapacityAvailable || !byteCapacityAvailable) {
                if (rejectedChunks < Long.MAX_VALUE) {
                    rejectedChunks++
                }
                null
            } else {
                outstandingChunks++
                outstandingEncodedBytes += encodedBytes
                RedissonInvalidationQuotaLease(this, encodedBytes)
            }
        }
    }

    /** Returns a payload-free snapshot of the current admission state. */
    fun health(): SnapshotInvalidationQuotaHealth = lock.withLock {
        SnapshotInvalidationQuotaHealth(
            maxOutstandingChunks = maxOutstandingChunks,
            outstandingChunks = outstandingChunks,
            maxOutstandingEncodedBytes = maxOutstandingEncodedBytes,
            outstandingEncodedBytes = outstandingEncodedBytes,
            rejectedChunks = rejectedChunks,
            saturated = outstandingChunks >= maxOutstandingChunks ||
                    outstandingEncodedBytes >= maxOutstandingEncodedBytes,
        )
    }

    fun requireMatchingLimits(maxOutstandingChunks: Int, maxOutstandingEncodedBytes: Long) {
        require(
            this.maxOutstandingChunks == maxOutstandingChunks &&
                    this.maxOutstandingEncodedBytes == maxOutstandingEncodedBytes,
        ) {
            "Redisson client invalidation quota limits must match the first registered limits."
        }
    }

    fun release(encodedBytes: Long) = lock.withLock {
        check(outstandingChunks > 0) { "Invalidation quota chunk count cannot become negative." }
        check(outstandingEncodedBytes >= encodedBytes) {
            "Invalidation quota encoded-byte count cannot become negative."
        }
        outstandingChunks--
        outstandingEncodedBytes -= encodedBytes
    }
}

/** Exactly-once ownership token for one admitted invalidation chunk. */
internal class RedissonInvalidationQuotaLease(
    private val quota: RedissonInvalidationQuota,
    private val encodedBytes: Long,
) {
    private val released = atomic(false)

    /** Releases the admitted counts once; duplicate completion notifications are harmless. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            quota.release(encodedBytes)
        }
    }
}

private class RedissonClientIdentityWeakReference(
    redissonClient: RedissonClient,
    queue: ReferenceQueue<RedissonClient>? = null,
) : WeakReference<RedissonClient>(redissonClient, queue) {
    private val identityHashCode = System.identityHashCode(redissonClient)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RedissonClientIdentityWeakReference) return false
        val client = get() ?: return false
        return client === other.get()
    }
}
