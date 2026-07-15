@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import io.bluetape4k.exposed.cache.snapshot.stageMappedSnapshotMutation
import io.bluetape4k.exposed.cache.snapshot.stageSnapshotMutation
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import java.io.Serializable

/** Stages [snapshot] for cache-only publication after this root JDBC transaction commits. */
fun <ID : Any, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V> = stageSnapshotMutation(
    this,
    JdbcSnapshotTransactionBridge,
    cache,
    miss,
    snapshot,
    cache.validator,
)

/** Maps [source] inside this root JDBC transaction and stages the detached result for commit-only publication. */
fun <ID : Any, S, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V> = stageMappedSnapshotMutation(
    this,
    JdbcSnapshotTransactionBridge,
    cache,
    miss,
    source,
    mapper,
    cache.validator,
)

/** Stages cache-only invalidation after this root JDBC transaction commits. */
fun <ID : Any, V : Serializable> JdbcTransaction.stageInvalidation(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    id: ID,
) {
    stageInvalidationMutation(this, JdbcSnapshotTransactionBridge, cache, id)
}

@InternalSnapshotCacheApi
private object JdbcSnapshotTransactionBridge : SnapshotTransactionBridge<JdbcTransaction> {
    override fun isRoot(transaction: JdbcTransaction): Boolean = transaction.outerTransaction == null

    override fun isCurrent(transaction: JdbcTransaction): Boolean =
        transaction.transactionManager.currentOrNull() === transaction

    override fun maxAttempts(transaction: JdbcTransaction): Int = transaction.maxAttempts

    override fun registerInterceptor(transaction: JdbcTransaction, interceptor: StatementInterceptor) {
        transaction.registerInterceptor(interceptor)
    }
}
