@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.cache.snapshot

import java.io.Serializable
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Marks snapshot-cache adapter contracts that are reserved for internal integration.
 *
 * These contracts may change while transaction coordination and backend adapters are completed.
 */
@RequiresOptIn(
    message = "This snapshot-cache contract is internal and may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class InternalSnapshotCacheApi

/**
 * Represents either a cached [snapshot] or an opaque cache [miss].
 *
 * Exactly one of [snapshot] and [miss] is non-null. Instances are created through [hit] and [miss].
 *
 * @property snapshot cached detached snapshot, or `null` for a miss
 * @property miss opaque miss capability, or `null` for a hit
 */
class SnapshotCacheLookup<ID : Any, V : Serializable> private constructor(
    /** Cached detached snapshot, or `null` for a miss. */
    val snapshot: CacheSnapshot<V>?,
    /** Opaque miss capability, or `null` for a hit. */
    val miss: SnapshotCacheMiss<ID, V>?,
) {
    init {
        require((snapshot == null) xor (miss == null)) {
            "Snapshot cache lookup must contain exactly one snapshot or miss."
        }
    }

    /** Creates hit and miss lookup values. */
    companion object {
        /**
         * Creates a lookup containing [snapshot].
         */
        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> hit(snapshot: CacheSnapshot<V>): SnapshotCacheLookup<ID, V> =
            SnapshotCacheLookup(snapshot, null)

        /**
         * Creates an unregistered opaque miss.
         *
         * Backend adapters use their internal capability registry for claimable misses.
         */
        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> miss(): SnapshotCacheLookup<ID, V> =
            SnapshotCacheLookup(null, SnapshotCacheMiss())

        internal fun <ID : Any, V : Serializable> registeredMiss(
            miss: SnapshotCacheMiss<ID, V>,
        ): SnapshotCacheLookup<ID, V> = SnapshotCacheLookup(null, miss)
    }
}

/**
 * Opaque capability representing a single observed cache miss.
 *
 * The capability intentionally exposes neither the key nor local concurrency-fence state and is not serializable.
 */
class SnapshotCacheMiss<ID : Any, V : Serializable> internal constructor() {
    override fun toString(): String = "SnapshotCacheMiss(opaque)"
}

/**
 * A claimed miss that can prepare one guarded snapshot insertion.
 */
@InternalSnapshotCacheApi
fun interface ClaimedSnapshotMiss<ID : Any, V : Serializable> {
    /**
     * Prepares a guarded insertion for [snapshot].
     *
     * Each claimed miss accepts exactly one invocation.
     */
    fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V>
}

/**
 * Synchronous snapshot-cache backend contract.
 */
@InternalSnapshotCacheApi
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    /** Stable logical store identity. */
    val storeId: SnapshotStoreId

    /** Process-local identity token for this concrete store instance. */
    val storeInstanceToken: Any

    /** Stable compatibility fingerprint for participant collision checks. */
    val compatibilityFingerprint: String

    /** Safety limits enforced by this store. */
    val limits: SnapshotCacheLimits

    /**
     * Atomically consumes [miss] and returns its one-shot guarded insertion preparer.
     */
    @InternalSnapshotCacheApi
    fun claimMiss(miss: SnapshotCacheMiss<ID, V>): ClaimedSnapshotMiss<ID, V>

    /**
     * Applies [snapshots] as one store invocation while observing the shared [deadline].
     */
    fun applySnapshots(
        snapshots: List<SnapshotCacheMutation.Put<ID, V>>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport

    /**
     * Applies invalidations for [ids] as one store invocation while observing the shared [deadline].
     */
    fun applyInvalidations(ids: List<ID>, deadline: SnapshotCacheDeadline): SnapshotCacheApplyReport
}

/**
 * Asynchronous invalidation backend contract.
 */
@InternalSnapshotCacheApi
interface AsyncSnapshotInvalidationStore<ID : Any> {
    /** Stable logical store identity. */
    val storeId: SnapshotStoreId

    /** Process-local identity token for this concrete store instance. */
    val storeInstanceToken: Any

    /** Stable compatibility fingerprint for participant collision checks. */
    val compatibilityFingerprint: String

    /** Safety limits enforced by this store. */
    val limits: SnapshotCacheLimits

    /** Measures the encoded invalidation payload for [id]. */
    fun measure(id: ID): MeasuredInvalidation<ID>

    /** Submits one measured invalidation [batch]. */
    fun submitInvalidation(batch: List<MeasuredInvalidation<ID>>): CompletionStage<SnapshotCacheApplyReport>
}

/**
 * Shared monotonic deadline observed by all entries in one cache phase.
 */
interface SnapshotCacheDeadline {
    /** Returns the non-negative remaining duration. */
    fun remaining(): Duration

    /** Whether this deadline has expired. */
    val isExpired: Boolean
}

/**
 * Stable identity for one logical snapshot store.
 *
 * @property backend bounded non-blank backend name
 * @property namespace bounded non-blank cache namespace
 */
data class SnapshotStoreId(
    val backend: String,
    val namespace: String,
) : Serializable {
    init {
        require(backend.isNotBlank()) { "backend must not be blank." }
        require(namespace.isNotBlank()) { "namespace must not be blank." }
        require(backend.length <= MAX_BACKEND_LENGTH) {
            "backend length[${backend.length}] must not exceed $MAX_BACKEND_LENGTH."
        }
        require(namespace.length <= MAX_NAMESPACE_LENGTH) {
            "namespace length[${namespace.length}] must not exceed $MAX_NAMESPACE_LENGTH."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_BACKEND_LENGTH: Int = 128
        private const val MAX_NAMESPACE_LENGTH: Int = 512
    }
}

/**
 * Safety limits shared by snapshot-cache stores and transaction coordination.
 *
 * @property maxStagedMutations maximum mutations staged by one transaction
 * @property maxParticipatingStores maximum stores participating in one transaction
 * @property maxStagedWeight optional maximum total staged weight
 * @property localDrainBudget optional local post-transaction drain budget
 */
data class SnapshotCacheLimits(
    val maxStagedMutations: Int,
    val maxParticipatingStores: Int,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration? = null,
) : Serializable {
    init {
        require(maxStagedMutations > 0) { "maxStagedMutations[$maxStagedMutations] must be positive." }
        require(maxParticipatingStores > 0) {
            "maxParticipatingStores[$maxParticipatingStores] must be positive."
        }
        require(maxStagedMutations <= MAX_STAGED_MUTATIONS) {
            "maxStagedMutations[$maxStagedMutations] must not exceed $MAX_STAGED_MUTATIONS."
        }
        require(maxParticipatingStores <= MAX_PARTICIPATING_STORES) {
            "maxParticipatingStores[$maxParticipatingStores] must not exceed $MAX_PARTICIPATING_STORES."
        }
        maxStagedWeight?.let {
            require(it > 0L) { "maxStagedWeight[$it] must be positive when set." }
        }
        localDrainBudget?.let {
            require(it > Duration.ZERO) { "localDrainBudget[$it] must be positive when set." }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_STAGED_MUTATIONS: Int = 1_000_000
        private const val MAX_PARTICIPATING_STORES: Int = 1_024
    }
}

/**
 * Mutation prepared for a snapshot-cache phase.
 */
sealed interface SnapshotCacheMutation<ID : Any, V : Serializable> {
    /** Cache identifier affected by this mutation. */
    val id: ID

    /**
     * Guarded insertion of [snapshot] for [id].
     *
     * [localFence] is process-local concurrency state and must not be persisted or transported.
     *
     * @property id cache identifier
     * @property snapshot detached snapshot to insert
     * @property localFence optional process-local generation fence
     */
    data class Put<ID : Any, V : Serializable>(
        override val id: ID,
        val snapshot: CacheSnapshot<V>,
        @InternalSnapshotCacheApi val localFence: SnapshotLocalFence<ID>? = null,
    ) : SnapshotCacheMutation<ID, V>

    /**
     * Invalidates [id].
     *
     * @property id cache identifier
     */
    data class Invalidate<ID : Any, V : Serializable>(
        override val id: ID,
    ) : SnapshotCacheMutation<ID, V>
}

/**
 * Encoded invalidation measurement used before asynchronous submission.
 *
 * @property id invalidated cache identifier
 * @property encodedBytes encoded payload size in bytes
 * @property encodedSha256 lowercase hexadecimal SHA-256 digest of the encoded payload
 */
data class MeasuredInvalidation<ID : Any>(
    val id: ID,
    val encodedBytes: Int,
    val encodedSha256: String,
) {
    init {
        require(encodedBytes >= 0) { "encodedBytes[$encodedBytes] must not be negative." }
        require(SHA_256_PATTERN.matches(encodedSha256)) {
            "encodedSha256 must be a 64-character lowercase hexadecimal SHA-256 digest."
        }
    }

    companion object {
        private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Report returned by one cache phase.
 *
 * @property results per-outcome operation counts that reconcile to the phase input count
 */
data class SnapshotCacheApplyReport(
    val results: List<SnapshotCacheOperationResult>,
) : Serializable {
    /**
     * Requires this report to account exactly for one bulk [operation] input boundary.
     *
     * @return this validated report
     * @throws IllegalArgumentException when an operation differs or counts do not reconcile
     */
    @InternalSnapshotCacheApi
    fun requireReconciled(
        operation: SnapshotCacheOperation,
        expectedCount: Int,
    ): SnapshotCacheApplyReport {
        require(expectedCount >= 0) { "expectedCount[$expectedCount] must not be negative." }
        require(results.all { it.operation == operation }) {
            "Every result operation must be $operation."
        }
        val affectedCount = results.sumOf { it.affectedCount.toLong() }
        require(affectedCount == expectedCount.toLong()) {
            "Affected count[$affectedCount] must equal expectedCount[$expectedCount]."
        }
        return this
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Counted result for one cache operation and outcome.
 *
 * @property operation cache operation represented by this result
 * @property outcome operation outcome
 * @property affectedCount number of phase inputs represented by this result
 * @property exceptionType optional bounded exception class name for failed operations
 */
data class SnapshotCacheOperationResult(
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
) : Serializable {
    init {
        require(affectedCount >= 0) { "affectedCount[$affectedCount] must not be negative." }
        exceptionType?.let {
            require(it.isNotBlank()) { "exceptionType must not be blank when set." }
            require(it.length <= MAX_EXCEPTION_TYPE_LENGTH) {
                "exceptionType length[${it.length}] must not exceed $MAX_EXCEPTION_TYPE_LENGTH."
            }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MAX_EXCEPTION_TYPE_LENGTH: Int = 512
    }
}

/** Cache operation represented in an apply report. */
enum class SnapshotCacheOperation {
    /** Cache lookup. */
    GET,

    /** Snapshot insertion. */
    PUT,

    /** Cache invalidation. */
    INVALIDATE,
}

/** Outcome represented in an apply report. */
enum class SnapshotCacheOutcome {
    /** Operation completed successfully. */
    SUCCESS,

    /** Operation was attempted and failed. */
    FAILED,

    /** Operation was skipped after a prior failure or deadline expiry. */
    NOT_ATTEMPTED,

    /** Operation was rejected before backend mutation. */
    REJECTED,
}

internal class MonotonicSnapshotCacheDeadline(
    timeout: Duration,
    private val nanoTimeSource: () -> Long = System::nanoTime,
) : SnapshotCacheDeadline {
    private val timeoutNanos: Long
    private val startedAtNanos: Long

    init {
        require(timeout > Duration.ZERO) { "timeout[$timeout] must be positive." }
        timeoutNanos = try {
            timeout.toNanos()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("timeout[$timeout] is too large for a nanosecond deadline.", e)
        }
        startedAtNanos = nanoTimeSource()
    }

    override fun remaining(): Duration {
        val elapsedNanos = nanoTimeSource() - startedAtNanos
        val remainingNanos = timeoutNanos - elapsedNanos
        return if (remainingNanos > 0L) Duration.ofNanos(remainingNanos) else Duration.ZERO
    }

    override val isExpired: Boolean
        get() = remaining().isZero
}

@OptIn(InternalSnapshotCacheApi::class)
internal class SnapshotMissCapabilityRegistry<ID : Any, V : Serializable>(
    maxOutstandingMissTokens: Int,
) {
    private val maxOutstandingMissTokens = maxOutstandingMissTokens.also {
        require(it > 0) { "maxOutstandingMissTokens[$it] must be positive." }
    }
    private val lock = ReentrantLock()
    private val staleMisses = ReferenceQueue<SnapshotCacheMiss<ID, V>>()
    private val capabilities = HashMap<IdentityWeakReference<SnapshotCacheMiss<ID, V>>, MissCapability<ID>>()

    fun register(id: ID, localFence: SnapshotLocalFence<ID>): SnapshotCacheLookup<ID, V> = lock.withLock {
        expungeStaleMisses()
        check(capabilities.size < maxOutstandingMissTokens) {
            "Outstanding snapshot miss token limit[$maxOutstandingMissTokens] is exhausted."
        }

        val miss = SnapshotCacheMiss<ID, V>()
        capabilities[IdentityWeakReference(miss, staleMisses)] = MissCapability(id, localFence)
        SnapshotCacheLookup.registeredMiss(miss)
    }

    fun claim(miss: SnapshotCacheMiss<ID, V>): ClaimedSnapshotMiss<ID, V> {
        val capability = lock.withLock {
            expungeStaleMisses()
            capabilities.remove(IdentityWeakReference(miss))
        }
        checkNotNull(capability) { "Snapshot cache miss is foreign, stale, or already claimed." }
        return OneShotClaimedSnapshotMiss(capability.id, capability.localFence)
    }

    @Suppress("UNCHECKED_CAST")
    private fun expungeStaleMisses() {
        while (true) {
            val stale = staleMisses.poll() as? IdentityWeakReference<SnapshotCacheMiss<ID, V>> ?: return
            capabilities.remove(stale)
        }
    }
}

private data class MissCapability<ID : Any>(
    val id: ID,
    val localFence: SnapshotLocalFence<ID>,
)

@OptIn(InternalSnapshotCacheApi::class)
private class OneShotClaimedSnapshotMiss<ID : Any, V : Serializable>(
    private val id: ID,
    private val localFence: SnapshotLocalFence<ID>,
) : ClaimedSnapshotMiss<ID, V> {
    private val lock = ReentrantLock()
    private var available = true

    override fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V> = lock.withLock {
        check(available) { "Claimed snapshot miss has already been prepared." }
        available = false
        SnapshotCacheMutation.Put(id, snapshot, localFence)
    }
}

private class IdentityWeakReference<T : Any>(
    referent: T,
    queue: ReferenceQueue<T>? = null,
) : WeakReference<T>(referent, queue) {
    private val identityHashCode = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakReference<*>) return false
        val referent = get() ?: return false
        return referent === other.get()
    }
}
