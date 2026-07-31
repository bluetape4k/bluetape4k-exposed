@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull

/** 이 root JDBC transaction이 commit된 뒤 실행할 distributed invalidation을 staging합니다. */
fun <ID : Any> JdbcTransaction.stageInvalidation(
    invalidator: JdbcRedissonSnapshotInvalidator<ID>,
    id: ID,
) {
    check(maxAttempts == 1) {
        "Redisson snapshot invalidation requires a transaction configured for exactly one database attempt."
    }
    stageInvalidationMutation(this, JdbcRedissonSnapshotTransactionBridge, invalidator, id)
}

@InternalSnapshotCacheApi
private object JdbcRedissonSnapshotTransactionBridge : SnapshotTransactionBridge<JdbcTransaction> {
    override fun isRoot(transaction: JdbcTransaction): Boolean = transaction.outerTransaction == null

    override fun isCurrent(transaction: JdbcTransaction): Boolean =
        transaction.transactionManager.currentOrNull() === transaction

    override fun maxAttempts(transaction: JdbcTransaction): Int = transaction.maxAttempts

    override fun registerInterceptor(transaction: JdbcTransaction, interceptor: StatementInterceptor) {
        transaction.registerInterceptor(interceptor)
    }
}
