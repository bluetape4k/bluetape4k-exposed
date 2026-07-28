@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import java.io.Serializable
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adapts a concrete Exposed transaction implementation to common snapshot-cache coordination.
 *
 * Implementations must report the current physical root transaction and register the interceptor on that same
 * boundary. Snapshot fills are admitted only when [maxAttempts] returns one. The common coordinator boundary starts
 * when its own interceptor receives `beforeCommit` or `beforeRollback`; an interceptor registered earlier may still
 * stage work before that point because Exposed keeps the transaction current throughout ordered callbacks.
 */
@InternalSnapshotCacheApi
interface SnapshotTransactionBridge<TX : Transaction> {
    /** Returns whether [transaction] is the physical root boundary. */
    fun isRoot(transaction: TX): Boolean

    /** Returns whether [transaction] is still the adapter's current transaction. */
    fun isCurrent(transaction: TX): Boolean

    /** Returns the configured maximum database attempts for [transaction]. */
    fun maxAttempts(transaction: TX): Int

    /** Registers [interceptor] on [transaction]. */
    fun registerInterceptor(transaction: TX, interceptor: StatementInterceptor)
}

/** Stages one validated snapshot insertion for the current root transaction. */
@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V> = DEFAULT_SNAPSHOT_TRANSACTION_COORDINATOR.stageSnapshot(
    transaction,
    bridge,
    store,
    miss,
    snapshot,
    validator,
)

/** Maps and stages one validated snapshot insertion for the current root transaction. */
@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, S, V : Serializable> stageMappedSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V> = DEFAULT_SNAPSHOT_TRANSACTION_COORDINATOR.stageMappedSnapshot(
    transaction,
    bridge,
    store,
    miss,
    source,
    mapper,
    validator,
)

/** Stages one asynchronous invalidation for the current root transaction. */
@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: AsyncSnapshotInvalidationStore<ID>,
    id: ID,
) {
    DEFAULT_SNAPSHOT_TRANSACTION_COORDINATOR.stageInvalidation(transaction, bridge, store, id)
}

/** Stages one local invalidation for the current root transaction. */
@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    id: ID,
) {
    DEFAULT_SNAPSHOT_TRANSACTION_COORDINATOR.stageInvalidation(transaction, bridge, store, id)
}

/**
 * Exposed root transaction에 snapshot-cache mutation을 stage하고 commit/rollback 경계에서 drain을 조정합니다.
 *
 * @param nanoTimeSource commit 이후 local drain deadline을 계산할 때 사용하는 단조 시간 source입니다.
 */
internal class SnapshotTransactionCoordinator(
    private val nanoTimeSource: () -> Long = System::nanoTime,
) {
    fun <TX : Transaction, ID : Any, V : Serializable> stageSnapshot(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
        store: SnapshotCacheStore<ID, V>,
        miss: SnapshotCacheMiss<ID, V>,
        snapshot: CacheSnapshot<V>,
        validator: CacheSnapshotValueValidator<V>,
    ): CacheSnapshot<V> {
        requireOpenRoot(transaction, bridge, requireSingleAttempt = true)
        val state = stateFor(transaction, bridge)
        val participant = LocalParticipant(store)
        state.requireCompatibleOpen(participant)
        val claimed = store.claimMiss(miss)
        validator.validate(snapshot.value)
        val mutation = claimed.prepare(snapshot)
        state.stage(participant, LocalPutMutation(participant, mutation))
        return snapshot
    }

    fun <TX : Transaction, ID : Any, S, V : Serializable> stageMappedSnapshot(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
        store: SnapshotCacheStore<ID, V>,
        miss: SnapshotCacheMiss<ID, V>,
        source: S,
        mapper: CacheSnapshotMapper<S, V>,
        validator: CacheSnapshotValueValidator<V>,
    ): CacheSnapshot<V> {
        requireOpenRoot(transaction, bridge, requireSingleAttempt = true)
        val state = stateFor(transaction, bridge)
        val participant = LocalParticipant(store)
        state.requireCompatibleOpen(participant)
        val claimed = store.claimMiss(miss)
        val snapshot = mapper.toSnapshot(source)
        validator.validate(snapshot.value)
        val mutation = claimed.prepare(snapshot)
        state.stage(participant, LocalPutMutation(participant, mutation))
        return snapshot
    }

    fun <TX : Transaction, ID : Any> stageInvalidation(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
        store: AsyncSnapshotInvalidationStore<ID>,
        id: ID,
    ) {
        requireOpenRoot(transaction, bridge)
        val state = stateFor(transaction, bridge)
        val participant = AsyncParticipant(store)
        state.requireCompatibleOpen(participant)
        val measured = store.measure(id)
        state.stage(participant, AsyncInvalidationMutation(participant, measured))
    }

    fun <TX : Transaction, ID : Any, V : Serializable> stageInvalidation(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
        store: SnapshotCacheStore<ID, V>,
        id: ID,
    ) {
        requireOpenRoot(transaction, bridge)
        val state = stateFor(transaction, bridge)
        val participant = LocalParticipant(store)
        state.requireCompatibleOpen(participant)
        state.stage(participant, LocalInvalidationMutation(participant, id))
    }

    private fun <TX : Transaction> stateFor(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
    ): SnapshotTransactionState {
        return TERMINAL_TRANSACTIONS.createStateIfOpen(transaction) {
            transaction.getUserData(SNAPSHOT_TRANSACTION_STATE_KEY)?.also {
                it.requireOpen()
                return@createStateIfOpen it
            }

            val state = SnapshotTransactionState()
            val interceptor = SnapshotTransactionInterceptor(state, nanoTimeSource)
            transaction.putUserData(SNAPSHOT_TRANSACTION_STATE_KEY, state)
            try {
                bridge.registerInterceptor(transaction, interceptor)
            } catch (failure: Throwable) {
                transaction.removeUserData(SNAPSHOT_TRANSACTION_STATE_KEY)
                throw failure
            }
            state
        }
    }

    private fun <TX : Transaction> requireOpenRoot(
        transaction: TX,
        bridge: SnapshotTransactionBridge<TX>,
        requireSingleAttempt: Boolean = false,
    ) {
        check(bridge.isCurrent(transaction)) { "Snapshot mutation requires the current Exposed transaction." }
        check(bridge.isRoot(transaction)) { "Snapshot mutation requires the physical root Exposed transaction." }
        if (requireSingleAttempt) {
            check(bridge.maxAttempts(transaction) == 1) {
                "Snapshot fills require a transaction configured for exactly one database attempt."
            }
        }
    }
}

/**
 * Exposed statement lifecycle callback을 snapshot transaction state transition으로 연결합니다.
 *
 * @param state transaction user-data에 저장된 staging 상태입니다.
 * @param nanoTimeSource commit 후 drain deadline 계산에 사용할 단조 시간 source입니다.
 */
private class SnapshotTransactionInterceptor(
    /** 이 interceptor가 관리하는 snapshot transaction state입니다. */
    private val state: SnapshotTransactionState,
    /** commit 후 drain phase deadline 계산용 단조 시간 source입니다. */
    private val nanoTimeSource: () -> Long,
) : StatementInterceptor {
    /** Exposed callback 중복/순서 경합에서 [pending]과 [state] 전이를 직렬화합니다. */
    private val lock = ReentrantLock()
    /** `beforeCommit`에서 확정해 `afterCommit`에서 drain할 mutation 묶음입니다. */
    private var pending = PendingSnapshotMutations.EMPTY

    override fun beforeCommit(transaction: Transaction) {
        lock.withLock {
            if (pending !== PendingSnapshotMutations.EMPTY) return
            TERMINAL_TRANSACTIONS.markTerminal(transaction)
            pending = state.beginCommit()
        }
    }

    override fun beforeRollback(transaction: Transaction) {
        lock.withLock {
            TERMINAL_TRANSACTIONS.markTerminal(transaction)
            state.rollback()
            pending = PendingSnapshotMutations.EMPTY
        }
    }

    override fun afterCommit(transaction: Transaction) {
        val committed = lock.withLock {
            TERMINAL_TRANSACTIONS.markTerminal(transaction)
            val captured = pending
            pending = PendingSnapshotMutations.EMPTY
            state.finish()
            transaction.removeUserData(SNAPSHOT_TRANSACTION_STATE_KEY)
            captured
        }
        drain(committed, nanoTimeSource)
    }

    override fun afterRollback(transaction: Transaction) {
        lock.withLock {
            TERMINAL_TRANSACTIONS.markTerminal(transaction)
            state.rollback()
            pending = PendingSnapshotMutations.EMPTY
            transaction.removeUserData(SNAPSHOT_TRANSACTION_STATE_KEY)
        }
    }
}

private class SnapshotTransactionState {
    /** staging 상태와 lifecycle 전이를 보호하는 transaction-local lock입니다. */
    private val lock = ReentrantLock()
    /** 현재 transaction 경계의 staging lifecycle입니다. */
    private var lifecycle = SnapshotTransactionLifecycle.OPEN
    /** store identity별 참여자 호환성/limit/failure-buffer 소유권 기록입니다. */
    private val participants = LinkedHashMap<SnapshotStoreId, ParticipantRecord>()
    /** store/id별 최종 mutation을 보관합니다. 같은 key의 mutation은 마지막 값으로 대체됩니다. */
    private val mutations = LinkedHashMap<MutationKey, BufferedSnapshotMutation>()
    /** staged mutation들의 추정 weight 합계입니다. replacement 시 이전 weight를 차감합니다. */
    private var totalWeight = 0L

    fun requireOpen() = lock.withLock {
        check(lifecycle == SnapshotTransactionLifecycle.OPEN) { "Snapshot transaction boundary has already started." }
    }

    fun requireCompatibleOpen(participant: SnapshotParticipant) = lock.withLock {
        requireOpenLocked()
        participants[participant.storeId]?.requireCompatible(participant)
    }

    fun stage(participant: SnapshotParticipant, mutation: BufferedSnapshotMutation) = lock.withLock {
        requireOpenLocked()
        val existingParticipant = participants[participant.storeId]
        existingParticipant?.requireCompatible(participant)
        val candidateParticipant = existingParticipant?.mergedWith(participant) ?: ParticipantRecord(participant)
        val candidateParticipants = LinkedHashMap(participants).apply {
            put(participant.storeId, candidateParticipant)
        }
        val key = MutationKey(participant.storeId, mutation.id)
        val previous = mutations[key]
        val candidateWeight = replacementWeight(previous, mutation)
        val effectiveLimits = candidateParticipants.values.map { it.limits }.reduce(SnapshotCacheLimits::minimum)
        val candidateCount = mutations.size + if (previous == null) 1 else 0

        check(candidateCount <= effectiveLimits.maxStagedMutations) {
            "Staged snapshot mutation limit[${effectiveLimits.maxStagedMutations}] would be exceeded."
        }
        check(candidateParticipants.size <= effectiveLimits.maxParticipatingStores) {
            "Participating snapshot store limit[${effectiveLimits.maxParticipatingStores}] would be exceeded."
        }
        effectiveLimits.maxStagedWeight?.let { limit ->
            val hasUnknownRetainedWeight = mutations.any { (existingKey, existingMutation) ->
                existingKey != key &&
                        existingMutation is LocalPutMutation<*, *> &&
                        existingMutation.estimatedWeight == null
            }
            val candidateHasUnknownWeight = mutation is LocalPutMutation<*, *> && mutation.estimatedWeight == null
            check(!hasUnknownRetainedWeight && !candidateHasUnknownWeight) {
                "A prepared snapshot weight is required when maxStagedWeight is configured."
            }
            check(candidateWeight <= limit) { "Staged snapshot weight[$candidateWeight] would exceed limit[$limit]." }
        }

        participants.clear()
        participants.putAll(candidateParticipants)
        mutations[key] = mutation
        totalWeight = candidateWeight
    }

    fun beginCommit(): PendingSnapshotMutations = lock.withLock {
        if (lifecycle != SnapshotTransactionLifecycle.OPEN) return PendingSnapshotMutations.EMPTY
        lifecycle = SnapshotTransactionLifecycle.BOUNDARY_STARTED
        val localBudget = participants.values
            .filter { it.hasLocalParticipant }
            .mapNotNull { it.limits.localDrainBudget }
            .minOrNull()
        val pending = PendingSnapshotMutations(mutations.values.toList(), localBudget)
        mutations.clear()
        participants.clear()
        totalWeight = 0L
        pending
    }

    fun rollback() = lock.withLock {
        lifecycle = SnapshotTransactionLifecycle.TERMINAL
        mutations.clear()
        participants.clear()
        totalWeight = 0L
    }

    fun finish() = lock.withLock {
        lifecycle = SnapshotTransactionLifecycle.TERMINAL
        mutations.clear()
        participants.clear()
        totalWeight = 0L
    }

    private fun requireOpenLocked() {
        check(lifecycle == SnapshotTransactionLifecycle.OPEN) { "Snapshot transaction boundary has already started." }
    }

    private fun replacementWeight(
        previous: BufferedSnapshotMutation?,
        candidate: BufferedSnapshotMutation,
    ): Long {
        val retained = totalWeight - (previous?.weight ?: 0L)
        return try {
            Math.addExact(retained, candidate.weight)
        } catch (failure: ArithmeticException) {
            throw IllegalStateException("Staged snapshot weight overflow.", failure)
        }
    }
}

private enum class SnapshotTransactionLifecycle {
    OPEN,
    BOUNDARY_STARTED,
    TERMINAL,
}

private data class ParticipantRecord(
    /** 동일 logical store identity에 대해 같은 concrete store instance인지 확인하는 process-local token입니다. */
    val storeInstanceToken: Any,
    /** adapter 호환성 충돌을 감지하기 위한 안정 fingerprint입니다. */
    val compatibilityFingerprint: String,
    /** 참여 store들의 effective limit 계산에 쓰이는 안전 한계입니다. */
    val limits: SnapshotCacheLimits,
    /** 이 store에 귀속된 failure event를 받을 caller-owned buffer입니다. */
    val failureBuffer: SnapshotCacheFailureBuffer,
    /** 이 identity가 local snapshot store participant로 참여했는지 여부입니다. */
    val hasLocalParticipant: Boolean,
    /** 이 identity가 async invalidation participant로 참여했는지 여부입니다. */
    val hasAsyncParticipant: Boolean,
) {
    constructor(participant: SnapshotParticipant) : this(
        participant.storeInstanceToken,
        participant.compatibilityFingerprint,
        participant.limits,
        participant.failureBuffer,
        participant is LocalParticipant<*, *>,
        participant is AsyncParticipant<*>,
    )

    fun requireCompatible(participant: SnapshotParticipant) {
        check(storeInstanceToken === participant.storeInstanceToken) {
            "Snapshot store identity collision for ${participant.storeId}."
        }
        check(compatibilityFingerprint == participant.compatibilityFingerprint) {
            "Snapshot store compatibility collision for ${participant.storeId}."
        }
        check(failureBuffer === participant.failureBuffer) {
            "Snapshot store failure-buffer ownership collision for ${participant.storeId}."
        }
    }

    fun mergedWith(participant: SnapshotParticipant): ParticipantRecord = copy(
        limits = limits.minimum(participant.limits),
        hasLocalParticipant = hasLocalParticipant || participant is LocalParticipant<*, *>,
        hasAsyncParticipant = hasAsyncParticipant || participant is AsyncParticipant<*>,
    )
}

private sealed interface SnapshotParticipant {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    val failureBuffer: SnapshotCacheFailureBuffer
}

private class LocalParticipant<ID : Any, V : Serializable>(
    /** local put/invalidation을 실제로 적용할 synchronous snapshot store입니다. */
    val store: SnapshotCacheStore<ID, V>,
) : SnapshotParticipant {
    override val storeId: SnapshotStoreId = store.storeId
    override val storeInstanceToken: Any = store.storeInstanceToken
    override val compatibilityFingerprint: String = store.compatibilityFingerprint
    override val limits: SnapshotCacheLimits = store.limits
    override val failureBuffer: SnapshotCacheFailureBuffer = store.failureBuffer

    @Suppress("UNCHECKED_CAST")
    fun applyInvalidations(mutations: List<BufferedSnapshotMutation>, deadline: SnapshotCacheDeadline) =
        store.applyInvalidations(mutations.map { it.id as ID }, deadline)

    @Suppress("UNCHECKED_CAST")
    fun applySnapshots(mutations: List<BufferedSnapshotMutation>, deadline: SnapshotCacheDeadline) =
        store.applySnapshots(mutations.map { (it as LocalPutMutation<ID, V>).put }, deadline)
}

private class AsyncParticipant<ID : Any>(
    /** async invalidation을 제출할 backend store입니다. */
    val store: AsyncSnapshotInvalidationStore<ID>,
) : SnapshotParticipant {
    override val storeId: SnapshotStoreId = store.storeId
    override val storeInstanceToken: Any = store.storeInstanceToken
    override val compatibilityFingerprint: String = store.compatibilityFingerprint
    override val limits: SnapshotCacheLimits = store.limits
    override val failureBuffer: SnapshotCacheFailureBuffer = store.failureBuffer

    @Suppress("UNCHECKED_CAST")
    fun submit(mutations: List<BufferedSnapshotMutation>) =
        store.submitInvalidation(mutations.map { (it as AsyncInvalidationMutation<ID>).measured })
}

private sealed interface BufferedSnapshotMutation {
    val participant: SnapshotParticipant
    val id: Any
    val weight: Long
}

private data class LocalPutMutation<ID : Any, V : Serializable>(
    /** mutation을 drain할 local participant입니다. */
    override val participant: LocalParticipant<ID, V>,
    /** claim된 miss에서 준비된 guarded put mutation입니다. */
    val put: SnapshotCacheMutation.Put<ID, V>,
) : BufferedSnapshotMutation {
    override val id: ID = put.id
    /** adapter가 계산한 retained weight 추정치입니다. 없으면 weight 제한 계산에서 0으로 취급합니다. */
    val estimatedWeight: Long? = put.estimatedWeight
    override val weight: Long = estimatedWeight ?: 0L
}

private data class LocalInvalidationMutation<ID : Any, V : Serializable>(
    /** invalidation을 drain할 local participant입니다. */
    override val participant: LocalParticipant<ID, V>,
    /** local cache에서 invalidate할 cache identifier입니다. */
    override val id: ID,
) : BufferedSnapshotMutation {
    override val weight: Long = 0L
}

private data class AsyncInvalidationMutation<ID : Any>(
    /** invalidation을 submit할 async participant입니다. */
    override val participant: AsyncParticipant<ID>,
    /** backend 제출 전에 측정된 encoded invalidation payload입니다. */
    val measured: MeasuredInvalidation<ID>,
) : BufferedSnapshotMutation {
    override val id: ID = measured.id
    override val weight: Long = measured.encodedBytes.toLong()
}

private data class MutationKey(
    /** mutation을 소유한 logical store identity입니다. */
    val storeId: SnapshotStoreId,
    /** store 안에서 mutation이 대상으로 삼는 cache identifier입니다. */
    val id: Any,
)

private data class PendingSnapshotMutations(
    /** commit 이후 drain할 최종 mutation 목록입니다. */
    val mutations: List<BufferedSnapshotMutation>,
    /** local participant drain에 적용할 최소 시간 예산입니다. 없으면 deadline 없이 drain합니다. */
    val localDrainBudget: Duration?,
) {
    companion object {
        val EMPTY = PendingSnapshotMutations(emptyList(), null)
    }
}

private fun drain(
    pending: PendingSnapshotMutations,
    nanoTimeSource: () -> Long,
) {
    if (pending.mutations.isEmpty()) return

    drainAsyncInvalidations(pending.mutations.filterIsInstance<AsyncInvalidationMutation<*>>())

    val localMutations = pending.mutations.filter { it.participant is LocalParticipant<*, *> }
    if (localMutations.isEmpty()) return
    val deadline = pending.localDrainBudget?.let { MonotonicSnapshotCacheDeadline(it, nanoTimeSource) }
        ?: NeverExpiringSnapshotCacheDeadline
    drainLocalPhase(
        mutations = localMutations.filterIsInstance<LocalInvalidationMutation<*, *>>(),
        operation = SnapshotCacheOperation.INVALIDATE,
        deadline = deadline,
    ) { participant, batch -> participant.applyInvalidations(batch, deadline) }
    drainLocalPhase(
        mutations = localMutations.filterIsInstance<LocalPutMutation<*, *>>(),
        operation = SnapshotCacheOperation.PUT,
        deadline = deadline,
    ) { participant, batch -> participant.applySnapshots(batch, deadline) }
}

private fun drainAsyncInvalidations(
    mutations: List<AsyncInvalidationMutation<*>>,
) {
    mutations.groupByStore().values.forEach { group ->
        val participant = group.participant as AsyncParticipant<*>
        try {
            val completion = participant.submit(group.mutations)
            observeAsyncCompletion(
                completion = completion,
                storeId = participant.storeId,
                failureBuffer = participant.failureBuffer,
                expectedCount = group.mutations.size,
            )
        } catch (exception: Exception) {
            participant.failureBuffer.recordFailure(
                failureFromException(
                    participant.storeId,
                    SnapshotCacheOperation.INVALIDATE,
                    group.mutations.size,
                    exception,
                ),
            )
        }
    }
}

/**
 * Attaches non-blocking structural failure observation and returns its dependent completion stage.
 *
 * Fatal errors remain exceptional on the returned stage and are never converted into failure-buffer events. This
 * function cannot make an asynchronously completed error escape synchronously from the original caller thread.
 */
internal fun observeAsyncCompletion(
    completion: CompletionStage<SnapshotCacheApplyReport>,
    storeId: SnapshotStoreId,
    failureBuffer: SnapshotCacheFailureBuffer,
    expectedCount: Int,
): CompletionStage<SnapshotCacheApplyReport> =
    completion.whenComplete { report, throwable ->
        val completionFailure = throwable?.unwrapCompletionFailure()
        if (completionFailure != null) {
            if (completionFailure is Exception) {
                failureBuffer.recordFailure(
                    failureFromException(
                        storeId,
                        SnapshotCacheOperation.INVALIDATE,
                        expectedCount,
                        completionFailure,
                    ),
                )
            }
            return@whenComplete
        }

        try {
            val reconciled = requireNotNull(report) { "Asynchronous invalidation completed without a report." }
                .requireReconciled(SnapshotCacheOperation.INVALIDATE, expectedCount)
            reconciled.results.filter { it.outcome != SnapshotCacheOutcome.SUCCESS }.forEach { result ->
                failureBuffer.recordFailure(
                    SnapshotCacheFailure(
                        storeId,
                        result.operation,
                        result.outcome,
                        result.affectedCount,
                        result.exceptionType,
                    ),
                )
            }
        } catch (exception: Exception) {
            failureBuffer.recordFailure(
                failureFromException(
                    storeId,
                    SnapshotCacheOperation.INVALIDATE,
                    expectedCount,
                    exception,
                ),
            )
        }
    }

private tailrec fun Throwable.unwrapCompletionFailure(): Throwable = when (this) {
    is CompletionException, is ExecutionException -> cause?.unwrapCompletionFailure() ?: this
    else -> this
}

private inline fun drainLocalPhase(
    mutations: List<BufferedSnapshotMutation>,
    operation: SnapshotCacheOperation,
    deadline: SnapshotCacheDeadline,
    apply: (LocalParticipant<*, *>, List<BufferedSnapshotMutation>) -> SnapshotCacheApplyReport,
) {
    mutations.groupByStore().values.forEach { group ->
        val participant = group.participant as LocalParticipant<*, *>
        if (deadline.isExpired) {
            participant.failureBuffer.recordFailure(
                SnapshotCacheFailure(
                    participant.storeId,
                    operation,
                    SnapshotCacheOutcome.NOT_ATTEMPTED,
                    group.mutations.size,
                ),
            )
            return@forEach
        }
        try {
            val report = apply(participant, group.mutations).requireReconciled(operation, group.mutations.size)
            report.results.filter { it.outcome != SnapshotCacheOutcome.SUCCESS }.forEach { result ->
                participant.failureBuffer.recordFailure(
                    SnapshotCacheFailure(
                        participant.storeId,
                        result.operation,
                        result.outcome,
                        result.affectedCount,
                        result.exceptionType,
                    ),
                )
            }
        } catch (exception: Exception) {
            participant.failureBuffer.recordFailure(
                failureFromException(participant.storeId, operation, group.mutations.size, exception),
            )
        }
    }
}

private data class StoreMutationGroup(
    /** 같은 store identity로 묶인 mutation을 처리할 participant입니다. */
    val participant: SnapshotParticipant,
    /** 같은 store identity에 속하는 mutation 목록입니다. */
    val mutations: MutableList<BufferedSnapshotMutation>,
)

private fun List<BufferedSnapshotMutation>.groupByStore(): LinkedHashMap<SnapshotStoreId, StoreMutationGroup> {
    val grouped = LinkedHashMap<SnapshotStoreId, StoreMutationGroup>()
    forEach { mutation ->
        grouped.getOrPut(mutation.participant.storeId) {
            StoreMutationGroup(mutation.participant, mutableListOf())
        }.mutations += mutation
    }
    return grouped
}

private object NeverExpiringSnapshotCacheDeadline : SnapshotCacheDeadline {
    override fun remaining(): Duration = Duration.ofNanos(Long.MAX_VALUE)
    override val isExpired: Boolean = false
}

private fun SnapshotCacheLimits.minimum(other: SnapshotCacheLimits): SnapshotCacheLimits = SnapshotCacheLimits(
    maxStagedMutations = minOf(maxStagedMutations, other.maxStagedMutations),
    maxParticipatingStores = minOf(maxParticipatingStores, other.maxParticipatingStores),
    maxStagedWeight = listOfNotNull(maxStagedWeight, other.maxStagedWeight).minOrNull(),
    localDrainBudget = listOfNotNull(localDrainBudget, other.localDrainBudget).minOrNull(),
)

private class WeakIdentityTerminalTransactions {
    /** weak-reference set과 reference queue 정리를 직렬화합니다. */
    private val lock = ReentrantLock()
    /** GC된 terminal transaction reference를 수거하는 queue입니다. */
    private val staleTransactions = ReferenceQueue<Transaction>()
    /** 이미 terminal 경계에 도달한 root transaction들의 weak identity set입니다. */
    private val terminalTransactions = HashSet<IdentityWeakTransactionReference>()

    fun <T> createStateIfOpen(transaction: Transaction, block: () -> T): T = lock.withLock {
        expungeStaleTransactions()
        check(!terminalTransactions.contains(IdentityWeakTransactionReference(transaction))) {
            "Snapshot transaction boundary has already completed."
        }
        block()
    }

    fun markTerminal(transaction: Transaction) = lock.withLock {
        expungeStaleTransactions()
        terminalTransactions += IdentityWeakTransactionReference(transaction, staleTransactions)
    }

    @Suppress("UNCHECKED_CAST")
    private fun expungeStaleTransactions() {
        while (true) {
            val stale = staleTransactions.poll() as? IdentityWeakTransactionReference ?: return
            terminalTransactions.remove(stale)
        }
    }
}

private class IdentityWeakTransactionReference(
    transaction: Transaction,
    queue: ReferenceQueue<Transaction>? = null,
) : WeakReference<Transaction>(transaction, queue) {
    /** referent가 GC된 뒤에도 hash set 위치를 유지하기 위한 identity hash입니다. */
    private val identityHashCode = System.identityHashCode(transaction)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakTransactionReference) return false
        val transaction = get() ?: return false
        return transaction === other.get()
    }
}

private val SNAPSHOT_TRANSACTION_STATE_KEY = Key<SnapshotTransactionState>()
private val TERMINAL_TRANSACTIONS = WeakIdentityTerminalTransactions()
private val DEFAULT_SNAPSHOT_TRANSACTION_COORDINATOR = SnapshotTransactionCoordinator()
