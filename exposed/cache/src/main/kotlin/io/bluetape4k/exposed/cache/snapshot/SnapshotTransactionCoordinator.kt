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

private class SnapshotTransactionInterceptor(
    private val state: SnapshotTransactionState,
    private val nanoTimeSource: () -> Long,
) : StatementInterceptor {
    private val lock = ReentrantLock()
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
    private val lock = ReentrantLock()
    private var lifecycle = SnapshotTransactionLifecycle.OPEN
    private val participants = LinkedHashMap<SnapshotStoreId, ParticipantRecord>()
    private val mutations = LinkedHashMap<MutationKey, BufferedSnapshotMutation>()
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
    val storeInstanceToken: Any,
    val compatibilityFingerprint: String,
    val limits: SnapshotCacheLimits,
    val failureBuffer: SnapshotCacheFailureBuffer,
    val hasLocalParticipant: Boolean,
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
    override val participant: LocalParticipant<ID, V>,
    val put: SnapshotCacheMutation.Put<ID, V>,
) : BufferedSnapshotMutation {
    override val id: ID = put.id
    val estimatedWeight: Long? = put.estimatedWeight
    override val weight: Long = estimatedWeight ?: 0L
}

private data class LocalInvalidationMutation<ID : Any, V : Serializable>(
    override val participant: LocalParticipant<ID, V>,
    override val id: ID,
) : BufferedSnapshotMutation {
    override val weight: Long = 0L
}

private data class AsyncInvalidationMutation<ID : Any>(
    override val participant: AsyncParticipant<ID>,
    val measured: MeasuredInvalidation<ID>,
) : BufferedSnapshotMutation {
    override val id: ID = measured.id
    override val weight: Long = 0L
}

private data class MutationKey(
    val storeId: SnapshotStoreId,
    val id: Any,
)

private data class PendingSnapshotMutations(
    val mutations: List<BufferedSnapshotMutation>,
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
    val participant: SnapshotParticipant,
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
    private val lock = ReentrantLock()
    private val staleTransactions = ReferenceQueue<Transaction>()
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
