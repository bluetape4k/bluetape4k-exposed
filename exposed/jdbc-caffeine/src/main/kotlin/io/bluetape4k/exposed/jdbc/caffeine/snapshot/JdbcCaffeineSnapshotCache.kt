@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.jdbc.caffeine.snapshot

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotValueValidator
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.ClaimedSnapshotMiss
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheDeadline
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLimits
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLookup
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMiss
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheMutation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheStore
import io.bluetape4k.exposed.cache.snapshot.SnapshotLocalFenceRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotMissCapabilityRegistry
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.SnapshotValueSizer
import io.bluetape4k.exposed.cache.snapshot.rejectDirectEntitySnapshotValues
import io.bluetape4k.exposed.cache.snapshot.sanitizeSnapshotCacheExceptionType
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import java.io.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * A cache-only JDBC Caffeine facade for transaction-aware detached snapshots.
 *
 * Instances own no threads or closeable resources. Database access remains entirely caller-owned.
 */
class JdbcCaffeineSnapshotCache<ID : Any, V : Serializable> private constructor(
    /** Cache identifier의 runtime type token입니다. compatibility fingerprint 계산에 사용합니다. */
    idType: KClass<ID>,
    /** 분리 snapshot 값의 runtime type token입니다. compatibility fingerprint 계산에 사용합니다. */
    valueType: KClass<V>,
    /** Caffeine backend와 snapshot coordination의 immutable 설정입니다. */
    private val config: CaffeineSnapshotCacheConfig,
    /** Retained weight 제한을 적용할 때 사용하는 caller-provided estimator입니다. */
    private val valueSizer: SnapshotValueSizer<V>?,
    /** Cache admission 전에 분리 snapshot 값을 검사하는 validator입니다. */
    internal val validator: CacheSnapshotValueValidator<V>,
    /** 이 facade가 failure event를 기록하는 caller-owned bounded buffer입니다. */
    override val failureBuffer: SnapshotCacheFailureBuffer,
) : SnapshotCacheStore<ID, V> {
    private val cache: Cache<ID, StoredSnapshot<V>> = buildCache(config)
    private val fences = SnapshotLocalFenceRegistry<ID>(config.fenceStripes)
    private val misses = SnapshotMissCapabilityRegistry<ID, V>(config.maxOutstandingMissTokens)
    private val maintenanceLock = ReentrantLock()

    /** 이 cache의 안정적인 logical identity입니다. */
    override val storeId: SnapshotStoreId = SnapshotStoreId(BACKEND, config.snapshot.namespace)

    @InternalSnapshotCacheApi
    override val storeInstanceToken: Any = Any()

    @InternalSnapshotCacheApi
    override val compatibilityFingerprint: String = listOf(
        VERSION,
        idType.qualifiedName ?: idType.toString(),
        valueType.qualifiedName ?: valueType.toString(),
        config.snapshot.schemaVersion,
        config.maximumSize,
        config.maximumWeight,
        config.expireAfterWrite,
        config.expireAfterAccess,
        config.fenceStripes,
    ).joinToString("|")

    @InternalSnapshotCacheApi
    override val limits: SnapshotCacheLimits = SnapshotCacheLimits(
        maxStagedMutations = config.snapshot.maxStagedMutations,
        maxParticipatingStores = config.snapshot.maxParticipatingStores,
        maxStagedWeight = config.maxStagedWeight,
        localDrainBudget = config.localDrainBudget,
    )

    /**
     * Returns the current cached snapshot or an opaque one-shot miss capability for [id].
     *
     * A miss is captured before any caller database work and is rejected if a newer local mutation advances its
     * generation before commit.
     */
    fun lookup(id: ID): SnapshotCacheLookup<ID, V> {
        val fence = fences.capture(id)
        val snapshot = cache.getIfPresent(id)?.snapshot
        return snapshot?.let { SnapshotCacheLookup.hit(it) } ?: misses.register(id, fence)
    }

    @InternalSnapshotCacheApi
    override fun claimMiss(miss: SnapshotCacheMiss<ID, V>): ClaimedSnapshotMiss<ID, V> {
        val claimed = misses.claim(miss)
        return ClaimedSnapshotMiss { snapshot ->
            val estimatedWeight = valueSizer?.estimatedRetainedBytes(snapshot.value)?.also {
                require(it >= 0L) { "Snapshot value size[$it] must be non-negative." }
                if (config.maximumWeight != null) {
                    require(it <= Int.MAX_VALUE.toLong()) {
                        "Snapshot value size[$it] exceeds Caffeine's per-entry weight limit."
                    }
                }
            }
            claimed.prepare(snapshot).copy(estimatedWeight = estimatedWeight)
        }
    }

    @InternalSnapshotCacheApi
    override fun applySnapshots(
        snapshots: List<SnapshotCacheMutation.Put<ID, V>>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport {
        val report = applyEntries(snapshots, SnapshotCacheOperation.PUT, deadline) { put ->
            val fence = requireNotNull(put.localFence) { "A local snapshot PUT requires its opaque fence." }
            if (fences.putIfCurrent(put.id, fence) {
                    cache.put(put.id, StoredSnapshot(put.snapshot, caffeineWeight(put.estimatedWeight)))
                }
            ) {
                SnapshotCacheOutcome.SUCCESS
            } else {
                SnapshotCacheOutcome.REJECTED
            }
        }
        if (report.results.any { it.outcome == SnapshotCacheOutcome.SUCCESS }) {
            maintainCapacity()
            if (report.results.none { it.outcome == SnapshotCacheOutcome.OVERRUN } && deadline.isExpired) {
                return SnapshotCacheApplyReport(
                    report.results + SnapshotCacheOperationResult(
                        SnapshotCacheOperation.PUT,
                        SnapshotCacheOutcome.OVERRUN,
                        0,
                    ),
                )
            }
        }
        return report
    }

    @InternalSnapshotCacheApi
    override fun applyInvalidations(
        ids: List<ID>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport = applyEntries(ids, SnapshotCacheOperation.INVALIDATE, deadline) { id ->
        fences.invalidate(id) { cache.invalidate(id) }
        SnapshotCacheOutcome.SUCCESS
    }

    private inline fun <T> applyEntries(
        entries: List<T>,
        operation: SnapshotCacheOperation,
        deadline: SnapshotCacheDeadline,
        apply: (T) -> SnapshotCacheOutcome,
    ): SnapshotCacheApplyReport {
        val results = ArrayList<SnapshotCacheOperationResult>()
        var index = 0
        while (index < entries.size) {
            if (deadline.isExpired) {
                results += SnapshotCacheOperationResult(
                    operation,
                    SnapshotCacheOutcome.NOT_ATTEMPTED,
                    entries.size - index,
                )
                break
            }
            val result = try {
                SnapshotCacheOperationResult(operation, apply(entries[index]), 1)
            } catch (exception: Exception) {
                SnapshotCacheOperationResult(
                    operation,
                    SnapshotCacheOutcome.FAILED,
                    1,
                    sanitizeSnapshotCacheExceptionType(exception.javaClass.name),
                )
            }
            results += result
            index++
            if (deadline.isExpired) {
                results += SnapshotCacheOperationResult(operation, SnapshotCacheOutcome.OVERRUN, 0)
            }
        }
        return SnapshotCacheApplyReport(results)
    }

    private fun maintainCapacity() = maintenanceLock.withLock {
        cache.cleanUp()
        val eviction = cache.policy().eviction().orElseThrow()
        while (cache.estimatedSize() > config.maximumSize) {
            val overflow = (cache.estimatedSize() - config.maximumSize)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val coldest = eviction.coldest(overflow)
            check(coldest.isNotEmpty()) { "Caffeine did not expose entries required for maximumSize enforcement." }
            cache.invalidateAll(coldest.keys)
            cache.cleanUp()
        }
    }

    companion object {
        private const val BACKEND = "caffeine-jdbc"
        private const val VERSION = "jdbc-caffeine-snapshot-v1"

        internal fun <ID : Any, V : Serializable> create(
            idType: KClass<ID>,
            valueType: KClass<V>,
            config: CaffeineSnapshotCacheConfig,
            valueSizer: SnapshotValueSizer<V>?,
            validator: CacheSnapshotValueValidator<V>,
            failureBuffer: SnapshotCacheFailureBuffer,
        ): JdbcCaffeineSnapshotCache<ID, V> =
            JdbcCaffeineSnapshotCache(idType, valueType, config, valueSizer, validator, failureBuffer)
    }

    private fun caffeineWeight(estimatedWeight: Long?): Int {
        if (config.maximumWeight == null) return 1
        val retainedWeight = requireNotNull(estimatedWeight) {
            "A prepared snapshot weight is required for a weighted Caffeine cache."
        }
        return retainedWeight.toInt()
    }
}

/** Creates a cache-only JDBC Caffeine snapshot facade using explicit runtime type tokens. */
fun <ID : Any, V : Serializable> jdbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V> {
    require(valueSizer != null || config.maximumWeight == null && config.maxStagedWeight == null) {
        "A SnapshotValueSizer is required when maximumWeight or maxStagedWeight is configured."
    }
    return JdbcCaffeineSnapshotCache.create(idType, valueType, config, valueSizer, validator, failureBuffer)
}

/** Creates a cache-only JDBC Caffeine snapshot facade using reified runtime type tokens. */
inline fun <reified ID : Any, reified V : Serializable> jdbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V> =
    jdbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

private fun <ID : Any, V : Serializable> buildCache(
    config: CaffeineSnapshotCacheConfig,
): Cache<ID, StoredSnapshot<V>> {
    val base = Caffeine.newBuilder()
        .expireAfterWrite(config.expireAfterWrite)
        .apply { config.expireAfterAccess?.let(::expireAfterAccess) }
    config.maximumWeight?.let { maximumWeight ->
        return base.weigher(Weigher<ID, StoredSnapshot<V>> { _, snapshot -> snapshot.caffeineWeight })
            .maximumWeight(maximumWeight)
            .build()
    }
    return base.maximumSize(config.maximumSize).build()
}

private data class StoredSnapshot<V : Serializable>(
    /** Caffeine entry에 보관되는 분리 snapshot입니다. */
    val snapshot: CacheSnapshot<V>,
    /** Caffeine eviction 계산에 사용하는 entry weight입니다. */
    val caffeineWeight: Int,
)
