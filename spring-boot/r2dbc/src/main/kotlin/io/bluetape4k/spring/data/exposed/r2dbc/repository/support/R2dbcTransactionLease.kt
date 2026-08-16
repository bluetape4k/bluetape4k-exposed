package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import kotlinx.coroutines.CancellationException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** 하나의 outer R2DBC transaction에서 동시 terminal/Flow 수집을 차단하는 lease입니다. */
internal class R2dbcTransactionLease {

    private val acquired = AtomicBoolean(false)

    suspend fun <T> withLease(block: suspend () -> T): T {
        if (!acquired.compareAndSet(false, true)) {
            throw InvalidDataAccessApiUsageException(
                "R2DBC QBE terminals cannot overlap on the same transaction",
            )
        }
        try {
            return block()
        } catch (cancellation: CancellationException) {
            @Suppress("RethrowCaughtException")
            throw cancellation
        } finally {
            acquired.set(false)
        }
    }
}

/** outer transaction identity별 lease를 weak reference로 보관합니다. */
internal object R2dbcTransactionLeaseRegistry {

    private data class Entry(
        val transaction: WeakReference<R2dbcTransaction>,
        val lease: R2dbcTransactionLease,
    )

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun leaseFor(transaction: R2dbcTransaction): R2dbcTransactionLease {
        entries.removeAll { it.transaction.get() == null }
        entries.firstOrNull { it.transaction.get() === transaction }?.let { return it.lease }
        return R2dbcTransactionLease().also { lease ->
            entries += Entry(WeakReference(transaction), lease)
        }
    }
}
