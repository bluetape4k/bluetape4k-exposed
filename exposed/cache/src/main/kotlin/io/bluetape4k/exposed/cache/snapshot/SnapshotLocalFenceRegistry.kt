@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-local generation fence captured by a cache lookup.
 *
 * Tokens are compared by identity and must not be persisted, serialized, or transported outside the owning store.
 *
 * @property ownerToken identity of the local fence registry
 * @property stripe fixed stripe index selected for the cache identifier
 * @property generationToken identity of the generation observed by the lookup
 */
@InternalSnapshotCacheApi
data class SnapshotLocalFence(
    val ownerToken: Any,
    val stripe: Int,
    val generationToken: Any,
)

@OptIn(InternalSnapshotCacheApi::class)
internal class SnapshotLocalFenceRegistry<ID : Any>(
    stripeCount: Int,
) {
    private val ownerToken = Any()
    private val stripeMask: Int
    private val stripes: Array<Stripe>

    init {
        require(stripeCount > 0) { "stripeCount[$stripeCount] must be positive." }
        require(stripeCount.isPowerOfTwo()) { "stripeCount[$stripeCount] must be a power of two." }
        stripeMask = stripeCount - 1
        stripes = Array(stripeCount) { Stripe() }
    }

    fun capture(id: ID): SnapshotLocalFence {
        val stripeIndex = stripeIndex(id)
        val stripe = stripes[stripeIndex]
        return stripe.lock.withLock {
            SnapshotLocalFence(ownerToken, stripeIndex, stripe.generationToken)
        }
    }

    fun putIfCurrent(id: ID, fence: SnapshotLocalFence, mutation: () -> Unit): Boolean {
        val stripeIndex = stripeIndex(id)
        if (fence.ownerToken !== ownerToken || fence.stripe != stripeIndex) return false

        val stripe = stripes[stripeIndex]
        return stripe.lock.withLock {
            if (fence.generationToken !== stripe.generationToken) {
                false
            } else {
                stripe.generationToken = Any()
                mutation()
                true
            }
        }
    }

    fun invalidate(id: ID, mutation: () -> Unit) {
        val stripe = stripes[stripeIndex(id)]
        stripe.lock.withLock {
            stripe.generationToken = Any()
            mutation()
        }
    }

    private fun stripeIndex(id: ID): Int {
        val hash = id.hashCode()
        return (hash xor (hash ushr HASH_SPREAD_SHIFT)) and stripeMask
    }

    private class Stripe {
        val lock = ReentrantLock()
        var generationToken: Any = Any()
    }

    companion object {
        private const val HASH_SPREAD_SHIFT: Int = 16
    }
}

private fun Int.isPowerOfTwo(): Boolean = this and (this - 1) == 0
