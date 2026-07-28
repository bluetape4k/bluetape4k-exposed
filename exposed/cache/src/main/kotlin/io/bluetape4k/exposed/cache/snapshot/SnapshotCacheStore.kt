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
 * Cache hit [snapshot] 또는 opaque cache [miss] 중 하나를 표현합니다.
 *
 * [snapshot]과 [miss] 중 정확히 하나만 `null`이 아닙니다. 인스턴스는 [hit]와 [miss] factory를 통해 생성됩니다.
 *
 * @property snapshot cache hit일 때 반환되는 분리 snapshot입니다. miss일 때는 `null`이며 caller는 이 값을 직접
 * 수정하거나 영속성 상태와 다시 연결하면 안 됩니다.
 * @property miss cache miss일 때 backend adapter가 발급하는 opaque capability입니다. hit일 때는 `null`이며
 * capability 내부의 key/fence 상태는 외부에 노출되지 않습니다.
 */
class SnapshotCacheLookup<ID : Any, V : Serializable> private constructor(
    /** Cache hit일 때의 분리 snapshot입니다. miss이면 `null`입니다. */
    val snapshot: CacheSnapshot<V>?,
    /** Cache miss를 claim하기 위한 opaque capability입니다. hit이면 `null`입니다. */
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
    /** 안정적인 logical store identity입니다. */
    val storeId: SnapshotStoreId

    /** concrete store instance를 구분하는 process-local identity token입니다. */
    val storeInstanceToken: Any

    /** participant collision 검사용 안정 compatibility fingerprint입니다. */
    val compatibilityFingerprint: String

    /** 이 store가 강제하는 staging/drain 안전 한계입니다. */
    val limits: SnapshotCacheLimits

    /** 이 store에 귀속되는 failure를 수신하는 caller-owned bounded failure buffer입니다. */
    val failureBuffer: SnapshotCacheFailureBuffer

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
    /** 안정적인 logical store identity입니다. */
    val storeId: SnapshotStoreId

    /** concrete store instance를 구분하는 process-local identity token입니다. */
    val storeInstanceToken: Any

    /** participant collision 검사용 안정 compatibility fingerprint입니다. */
    val compatibilityFingerprint: String

    /** 이 store가 강제하는 staging/drain 안전 한계입니다. */
    val limits: SnapshotCacheLimits

    /** 이 store에 귀속되는 failure를 수신하는 caller-owned bounded failure buffer입니다. */
    val failureBuffer: SnapshotCacheFailureBuffer

    /** Measures the encoded invalidation payload for [id]. */
    fun measure(id: ID): MeasuredInvalidation<ID>

    /**
     * Submits one measured invalidation [batch].
     *
     * An ordinary exceptional completion is converted into a sanitized failure event. A fatal [Error] completion
     * remains exceptional on the completion-stage chain and is never converted into a failure event. Generic
     * [CompletionStage] semantics do not provide a synchronous caller-thread escape guarantee for asynchronous
     * failures.
     */
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
 * 하나의 논리적 snapshot store를 식별하는 안정적인 identity입니다.
 *
 * @property backend 제한 길이의 non-blank backend 이름입니다. adapter 종류나 저장소 계층을 구분하는 낮은
 * cardinality 값이어야 합니다.
 * @property namespace 제한 길이의 non-blank cache namespace입니다. 정적이고 낮은 cardinality일 때만 metrics tag
 * 후보로 사용할 수 있습니다.
 */
data class SnapshotStoreId(
    /** Backend 종류를 나타내는 안정적인 낮은 cardinality 이름입니다. */
    val backend: String,
    /** 논리 cache namespace입니다. request/user/entity 값 같은 동적 식별자를 포함하지 않아야 합니다. */
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
 * Snapshot-cache store와 transaction coordination이 공유하는 안전 한계입니다.
 *
 * @property maxStagedMutations 한 transaction에서 stage할 수 있는 mutation 최대 개수입니다.
 * @property maxParticipatingStores 한 transaction에 참여할 수 있는 store 최대 개수입니다.
 * @property maxStagedWeight 한 transaction에서 stage할 수 있는 총 추정 weight 상한입니다.
 * @property localDrainBudget commit 후 로컬 drain phase에 부여할 선택적 시간 예산입니다.
 */
data class SnapshotCacheLimits(
    /** transaction staging 단계의 mutation 개수 상한입니다. */
    val maxStagedMutations: Int,
    /** transaction에 동시에 참여할 수 있는 store 개수 상한입니다. */
    val maxParticipatingStores: Int,
    /** staged snapshot payload의 총 추정 weight 상한입니다. 설정하지 않으면 weight 제한을 적용하지 않습니다. */
    val maxStagedWeight: Long? = null,
    /** 로컬 cache backend의 commit 이후 drain 시간 예산입니다. 설정하지 않으면 deadline 없이 drain합니다. */
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
     * [id]에 대한 [snapshot]의 guarded insertion입니다.
 *
     * [localFence]는 process-local concurrency 상태이므로 저장하거나 전송하면 안 됩니다. [estimatedWeight]는 owning
     * adapter가 준비한 non-negative retained-weight 추정치입니다.
 *
     * @property id cache identifier입니다.
     * @property snapshot cache에 삽입할 분리 snapshot입니다.
     * @property localFence 선택적 process-local generation fence입니다.
     * @property estimatedWeight 선택적 retained-weight 추정치입니다.
     */
    data class Put<ID : Any, V : Serializable>(
        override val id: ID,
        /** Cache backend에 삽입할 불변 분리 snapshot입니다. */
        val snapshot: CacheSnapshot<V>,
        /** 동일 process 안에서 miss 관찰 이후 경쟁 write를 막는 local fence입니다. */
        @InternalSnapshotCacheApi val localFence: SnapshotLocalFence<ID>? = null,
        /** transaction limit과 backend eviction 판단에 쓰는 추정 retained weight입니다. */
        @InternalSnapshotCacheApi val estimatedWeight: Long? = null,
    ) : SnapshotCacheMutation<ID, V> {
        init {
            estimatedWeight?.let {
                require(it >= 0L) { "estimatedWeight[$it] must not be negative." }
            }
        }
    }

    /**
     * [id]를 invalidate합니다.
 *
     * @property id invalidate 대상 cache identifier입니다.
     */
    data class Invalidate<ID : Any, V : Serializable>(
        /** invalidate 대상 cache identifier입니다. */
        override val id: ID,
    ) : SnapshotCacheMutation<ID, V>
}

/**
 * 비동기 invalidation 제출 전에 계산한 encoded payload 측정값입니다.
 *
 * @property id invalidate 대상 cache identifier입니다.
 * @property encodedBytes encoded payload 크기입니다. async batching weight 계산에 사용합니다.
 * @property encodedSha256 encoded payload의 lowercase hexadecimal SHA-256 digest입니다. payload 본문 없이 구조적
 * 식별과 진단을 남기기 위한 bounded metadata입니다.
 */
data class MeasuredInvalidation<ID : Any>(
    /** invalidate 대상 cache identifier입니다. */
    val id: ID,
    /** async invalidation payload의 encoded byte 크기입니다. */
    val encodedBytes: Int,
    /** payload 본문을 노출하지 않는 lowercase SHA-256 digest입니다. */
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
 * 하나의 cache phase가 반환하는 적용 보고서입니다.
 *
 * @property results phase input count와 reconcile되어야 하는 operation/outcome별 count 목록입니다.
 */
data class SnapshotCacheApplyReport(
    /** 적용 결과를 operation/outcome 단위로 집계한 목록입니다. */
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
 * 하나의 cache operation/outcome 조합에 대한 집계 결과입니다.
 *
 * @property operation 이 결과가 표현하는 cache operation입니다.
 * @property outcome operation outcome입니다.
 * @property affectedCount 이 결과가 대표하는 phase input 개수입니다. 측정값으로만 사용하고 metrics tag로 사용하지
 * 않아야 합니다.
 * @property exceptionType 실패 operation에 대한 선택적 bounded exception class name입니다.
 */
data class SnapshotCacheOperationResult(
    /** 결과가 집계하는 cache operation입니다. */
    val operation: SnapshotCacheOperation,
    /** operation의 구조적 outcome입니다. */
    val outcome: SnapshotCacheOutcome,
    /** 이 결과가 설명하는 input 개수입니다. high-cardinality tag로 사용하지 않습니다. */
    val affectedCount: Int,
    /** 실패 원인의 안전하게 정제된 JVM exception class name입니다. 없거나 unsafe이면 `null`입니다. */
    val exceptionType: String? = null,
) : Serializable {
    init {
        require(affectedCount >= 0) { "affectedCount[$affectedCount] must not be negative." }
        require(outcome != SnapshotCacheOutcome.OVERRUN || affectedCount == 0) {
            "OVERRUN must have an affectedCount of zero."
        }
        exceptionType?.let(::requireSafeSnapshotCacheExceptionType)
    }

    companion object {
        private const val serialVersionUID: Long = 1L
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

    /** Operation completed, but the cooperative drain deadline expired during that operation. */
    OVERRUN,

    /** Operation was attempted and failed. */
    FAILED,

    /** Operation was skipped after a prior failure or deadline expiry. */
    NOT_ATTEMPTED,

    /** Operation was rejected before backend mutation. */
    REJECTED,
}

/**
 * 단조 시간 기반 snapshot-cache deadline 구현입니다.
 *
 * @param timeout deadline 전체 시간입니다. 양수여야 하며 nanosecond 변환 가능 범위여야 합니다.
 * @param nanoTimeSource 테스트와 deterministic 검증을 위해 주입하는 단조 시간 source입니다.
 */
internal class MonotonicSnapshotCacheDeadline(
    timeout: Duration,
    private val nanoTimeSource: () -> Long = System::nanoTime,
) : SnapshotCacheDeadline {
    /** [timeout]을 nanosecond 단위로 변환한 deadline 폭입니다. */
    private val timeoutNanos: Long
    /** deadline 계산의 기준이 되는 시작 시각입니다. */
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

/**
 * Owns bounded weak-identity miss capabilities for one backend adapter.
 *
 * Registered misses expose neither identifiers nor local fences. Claiming removes the capability atomically and
 * returns a one-shot snapshot preparer.
 */
@InternalSnapshotCacheApi
class SnapshotMissCapabilityRegistry<ID : Any, V : Serializable>(
    maxOutstandingMissTokens: Int,
) {
    private val maxOutstandingMissTokens = maxOutstandingMissTokens.also {
        require(it > 0) { "maxOutstandingMissTokens[$it] must be positive." }
    }
    private val lock = ReentrantLock()
    private val staleMisses = ReferenceQueue<SnapshotCacheMiss<ID, V>>()
    private val capabilities = HashMap<IdentityWeakReference<SnapshotCacheMiss<ID, V>>, MissCapability<ID>>()

    /** Registers an opaque miss bound to [id] and [localFence]. */
    fun register(id: ID, localFence: SnapshotLocalFence<ID>): SnapshotCacheLookup<ID, V> = lock.withLock {
        expungeStaleMisses()
        check(capabilities.size < maxOutstandingMissTokens) {
            "Outstanding snapshot miss token limit[$maxOutstandingMissTokens] is exhausted."
        }

        val miss = SnapshotCacheMiss<ID, V>()
        capabilities[IdentityWeakReference(miss, staleMisses)] = MissCapability(id, localFence)
        SnapshotCacheLookup.registeredMiss(miss)
    }

    /** Atomically consumes [miss] and returns its one-shot guarded insertion preparer. */
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
    /** miss를 관찰한 cache identifier입니다. capability 밖으로 직접 노출하지 않습니다. */
    val id: ID,
    /** miss 관찰 이후 같은 process에서 경쟁 write를 감지하기 위한 local fence입니다. */
    val localFence: SnapshotLocalFence<ID>,
)

@OptIn(InternalSnapshotCacheApi::class)
private class OneShotClaimedSnapshotMiss<ID : Any, V : Serializable>(
    /** claim된 miss가 삽입하려는 cache identifier입니다. */
    private val id: ID,
    /** guarded insertion에 포함할 process-local generation fence입니다. */
    private val localFence: SnapshotLocalFence<ID>,
) : ClaimedSnapshotMiss<ID, V> {
    /** one-shot prepare 상태를 보호하는 lock입니다. */
    private val lock = ReentrantLock()
    /** 아직 [prepare]를 호출할 수 있는지 나타내는 one-shot flag입니다. */
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
    /** referent가 사라진 뒤에도 hash bucket을 안정적으로 찾기 위한 identity hash입니다. */
    private val identityHashCode = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityWeakReference<*>) return false
        val referent = get() ?: return false
        return referent === other.get()
    }
}
