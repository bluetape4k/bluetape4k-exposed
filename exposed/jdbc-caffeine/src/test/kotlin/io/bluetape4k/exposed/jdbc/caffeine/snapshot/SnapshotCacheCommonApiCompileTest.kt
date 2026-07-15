@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.cache.snapshot.AsyncSnapshotInvalidationStore
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotValueValidator
import io.bluetape4k.exposed.cache.snapshot.ClaimedSnapshotMiss
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.MeasuredInvalidation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLimits
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLookup
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMutation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionBridge
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.stageInvalidationMutation
import io.bluetape4k.exposed.cache.snapshot.stageMappedSnapshotMutation
import io.bluetape4k.exposed.cache.snapshot.stageSnapshotMutation
import org.jetbrains.exposed.v1.core.DatabaseApi
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.TransactionManagerApi
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class SnapshotCacheCommonApiCompileTest {

    @Test
    fun `opted in consumer compiles lookup factories hooks and both invalidation overloads`() {
        val transaction = ConsumerTransaction()
        val bridge = ConsumerBridge()
        val local = ConsumerLocalStore()
        val remote = ConsumerAsyncStore()
        val hit = SnapshotCacheLookup.hit<Long, Payload>(CacheSnapshot(Payload("hit")))
        val firstMiss = SnapshotCacheLookup.miss<Long, Payload>().miss.shouldNotBeNull()
        val secondMiss = SnapshotCacheLookup.miss<Long, Payload>().miss.shouldNotBeNull()

        stageSnapshotMutation(
            transaction,
            bridge,
            local,
            firstMiss,
            CacheSnapshot(Payload("direct")),
            VALIDATOR,
        )
        stageMappedSnapshotMutation(
            transaction,
            bridge,
            local,
            secondMiss,
            "mapped",
            CacheSnapshotMapper { CacheSnapshot(Payload(it)) },
            VALIDATOR,
        )
        stageInvalidationMutation(transaction, bridge, local, 2L)
        stageInvalidationMutation(transaction, bridge, remote, 3L)

        hit.snapshot?.value shouldBeEqualTo Payload("hit")
        bridge.interceptors.size shouldBeEqualTo 1
    }

    @Test
    fun `consumer sees error opt in metadata without backend transaction leakage`() {
        val annotationBytes = InternalSnapshotCacheApi::class.java
            .getResourceAsStream("InternalSnapshotCacheApi.class")
            .shouldNotBeNull()
            .use { it.readBytes().decodeToString() }
        val facade = Class.forName("io.bluetape4k.exposed.cache.snapshot.SnapshotTransactionCoordinatorKt")
        val facadeBytes = facade.getResourceAsStream("SnapshotTransactionCoordinatorKt.class")
            .shouldNotBeNull()
            .use { it.readBytes().decodeToString() }
        val signatures = facade.declaredMethods.joinToString("\n") { it.toGenericString() }

        annotationBytes.contains("RequiresOptIn") shouldBeEqualTo true
        annotationBytes.contains("ERROR") shouldBeEqualTo true
        facadeBytes.contains("InternalSnapshotCacheApi") shouldBeEqualTo true
        signatures.contains("JdbcTransaction").shouldBeFalse()
        signatures.contains("R2dbcTransaction").shouldBeFalse()
        facade.declaredMethods.count { it.name == "stageInvalidationMutation" } shouldBeEqualTo 2
    }

    private class ConsumerBridge : SnapshotTransactionBridge<ConsumerTransaction> {
        val interceptors = mutableListOf<StatementInterceptor>()

        override fun isRoot(transaction: ConsumerTransaction): Boolean = true

        override fun isCurrent(transaction: ConsumerTransaction): Boolean = true

        override fun maxAttempts(transaction: ConsumerTransaction): Int = 1

        override fun registerInterceptor(transaction: ConsumerTransaction, interceptor: StatementInterceptor) {
            interceptors += interceptor
        }
    }

    private class ConsumerTransaction : Transaction() {
        override val db: DatabaseApi
            get() = error("Database is not used by the compile contract")
        override val transactionManager: TransactionManagerApi
            get() = error("Transaction manager is not used by the compile contract")
        override val readOnly: Boolean = false
        override val outerTransaction: Transaction? = null
    }

    private class ConsumerLocalStore : SnapshotCacheStore<Long, Payload> {
        override val storeId = SnapshotStoreId("local", "consumer:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "consumer-local:v1"
        override val limits = SnapshotCacheLimits(8, 2)
        override val failureBuffer = snapshotCacheFailureBuffer(4)
        private var nextId = 0L

        override fun claimMiss(miss: SnapshotCacheMiss<Long, Payload>): ClaimedSnapshotMiss<Long, Payload> {
            val id = nextId++
            return ClaimedSnapshotMiss { snapshot -> SnapshotCacheMutation.Put(id, snapshot) }
        }

        override fun applySnapshots(
            snapshots: List<SnapshotCacheMutation.Put<Long, Payload>>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport = success(SnapshotCacheOperation.PUT, snapshots.size)

        override fun applyInvalidations(
            ids: List<Long>,
            deadline: SnapshotCacheDeadline,
        ): SnapshotCacheApplyReport = success(SnapshotCacheOperation.INVALIDATE, ids.size)
    }

    private class ConsumerAsyncStore : AsyncSnapshotInvalidationStore<Long> {
        override val storeId = SnapshotStoreId("remote", "consumer:v1")
        override val storeInstanceToken: Any = Any()
        override val compatibilityFingerprint: String = "consumer-remote:v1"
        override val limits = SnapshotCacheLimits(8, 2)
        override val failureBuffer = snapshotCacheFailureBuffer(4)

        override fun measure(id: Long): MeasuredInvalidation<Long> =
            MeasuredInvalidation(id, Long.SIZE_BYTES, "c".repeat(64))

        override fun submitInvalidation(
            batch: List<MeasuredInvalidation<Long>>,
        ): CompletionStage<SnapshotCacheApplyReport> =
            CompletableFuture.completedFuture(success(SnapshotCacheOperation.INVALIDATE, batch.size))
    }

    private data class Payload(val value: String) : Serializable

    companion object {
        private val VALIDATOR = CacheSnapshotValueValidator<Payload> {}

        private fun success(operation: SnapshotCacheOperation, count: Int) = SnapshotCacheApplyReport(
            listOf(SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.SUCCESS, count)),
        )
    }
}
