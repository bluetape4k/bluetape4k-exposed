@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.r2dbc.caffeine.snapshot

import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import io.bluetape4k.exposed.cache.snapshot.stageMappedSnapshotMutation
import io.bluetape4k.exposed.cache.snapshot.stageSnapshotMutation
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.currentOrNull
import java.io.Serializable

/** 이 루트 R2DBC 트랜잭션이 커밋된 뒤 캐시 전용으로 게시할 [snapshot]을 준비합니다. */
fun <ID : Any, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V> = stageSnapshotMutation(
    this,
    R2dbcSnapshotTransactionBridge,
    cache,
    miss,
    snapshot,
    cache.validator,
)

/** 이 루트 R2DBC 트랜잭션 안에서 [source]를 매핑하고 커밋 후에만 게시할 분리 결과를 준비합니다. */
fun <ID : Any, S, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V> = stageMappedSnapshotMutation(
    this,
    R2dbcSnapshotTransactionBridge,
    cache,
    miss,
    source,
    mapper,
    cache.validator,
)

/** 이 루트 R2DBC 트랜잭션이 커밋된 뒤 수행할 캐시 전용 무효화를 준비합니다. */
fun <ID : Any, V : Serializable> R2dbcTransaction.stageInvalidation(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    id: ID,
) {
    stageInvalidationMutation(this, R2dbcSnapshotTransactionBridge, cache, id)
}

@InternalSnapshotCacheApi
private object R2dbcSnapshotTransactionBridge : SnapshotTransactionBridge<R2dbcTransaction> {
    override fun isRoot(transaction: R2dbcTransaction): Boolean = transaction.outerTransaction == null

    override fun isCurrent(transaction: R2dbcTransaction): Boolean =
        transaction.transactionManager.currentOrNull() === transaction

    override fun maxAttempts(transaction: R2dbcTransaction): Int = transaction.maxAttempts

    override fun registerInterceptor(transaction: R2dbcTransaction, interceptor: StatementInterceptor) {
        transaction.registerInterceptor(interceptor)
    }
}
