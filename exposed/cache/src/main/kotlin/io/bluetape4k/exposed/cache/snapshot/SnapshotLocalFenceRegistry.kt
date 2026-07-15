@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-local generation fence captured by a cache lookup.
 *
 * The capability exposes no captured identifier, stripe, or generation state. It must not be persisted,
 * serialized, copied, or transported outside the owning store.
 */
@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor(
    private val ownerToken: Any,
    private val stripe: Int,
    private val capturedId: ID,
    private val generationToken: Any,
) {
    internal fun isCurrentFor(
        ownerToken: Any,
        stripe: Int,
        id: ID,
        generationToken: Any,
    ): Boolean =
        this.ownerToken === ownerToken &&
            this.stripe == stripe &&
            capturedId == id &&
            this.generationToken === generationToken
}

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

    fun capture(id: ID): SnapshotLocalFence<ID> {
        val stripeIndex = stripeIndex(id)
        val stripe = stripes[stripeIndex]
        return stripe.lock.withLock {
            SnapshotLocalFence(ownerToken, stripeIndex, id, stripe.generationToken)
        }
    }

    fun putIfCurrent(id: ID, fence: SnapshotLocalFence<ID>, mutation: () -> Unit): Boolean {
        val stripeIndex = stripeIndex(id)
        val stripe = stripes[stripeIndex]
        return stripe.lock.withLock {
            if (!fence.isCurrentFor(ownerToken, stripeIndex, id, stripe.generationToken)) {
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
