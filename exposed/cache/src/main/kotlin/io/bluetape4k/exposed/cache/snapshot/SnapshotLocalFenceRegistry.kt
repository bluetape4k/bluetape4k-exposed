@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Cache lookup 시점에 capture한 process-local generation fence입니다.
 *
 * 이 capability는 capture된 identifier, stripe, generation state를 노출하지 않습니다. owning store 밖으로
 * persist, serialize, copy, transport하면 안 됩니다.
 *
 * @param ownerToken fence를 발급한 registry instance를 식별하는 process-local token입니다.
 * @param stripe identifier hash가 매핑된 stripe index입니다.
 * @param capturedId lookup 시점에 capture한 cache identifier입니다.
 * @param generationToken lookup 시점의 stripe generation token입니다.
 */
@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor(
    /** fence를 발급한 registry instance의 process-local owner token입니다. */
    private val ownerToken: Any,
    /** cache identifier가 배정된 stripe index입니다. */
    private val stripe: Int,
    /** lookup 시점에 capture한 cache identifier입니다. */
    private val capturedId: ID,
    /** lookup 시점의 stripe generation token입니다. */
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

/**
 * 하나의 backend adapter가 사용할 고정 크기 process-local generation fence를 소유합니다.
 *
 * adapter module은 이 opt-in SPI로 lookup 순서를 capture하고 generation advance와 cache mutation을 직렬화합니다.
 * fence 내부 상태는 adapter와 consumer에게 opaque하게 유지됩니다.
 *
 * @param stripeCount local fence를 분산할 stripe 개수입니다. 양수인 2의 거듭제곱이어야 합니다.
 */
@InternalSnapshotCacheApi
class SnapshotLocalFenceRegistry<ID : Any>(
    stripeCount: Int,
) {
    /** 이 registry가 발급한 fence인지 확인하는 process-local owner token입니다. */
    private val ownerToken = Any()
    /** hash를 stripe index로 접기 위한 bit mask입니다. */
    private val stripeMask: Int
    /** identifier별 generation을 분산 관리하는 stripe 배열입니다. */
    private val stripes: Array<Stripe>

    init {
        require(stripeCount > 0) { "stripeCount[$stripeCount] must be positive." }
        require(stripeCount.isPowerOfTwo()) { "stripeCount[$stripeCount] must be a power of two." }
        stripeMask = stripeCount - 1
        stripes = Array(stripeCount) { Stripe() }
    }

    /** Captures the current generation fence for [id]. */
    fun capture(id: ID): SnapshotLocalFence<ID> {
        val stripeIndex = stripeIndex(id)
        val stripe = stripes[stripeIndex]
        return stripe.lock.withLock {
            SnapshotLocalFence(ownerToken, stripeIndex, id, stripe.generationToken)
        }
    }

    /** Applies [mutation] only when [fence] is still current for [id]. */
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

    /** Advances the generation for [id] and applies [mutation] under the same stripe lock. */
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
        /** stripe 안의 generation token advance와 mutation을 직렬화하는 lock입니다. */
        val lock = ReentrantLock()
        /** 이 stripe의 현재 generation을 나타내는 opaque process-local token입니다. */
        var generationToken: Any = Any()
    }

    companion object {
        private const val HASH_SPREAD_SHIFT: Int = 16
    }
}

private fun Int.isPowerOfTwo(): Boolean = this and (this - 1) == 0
