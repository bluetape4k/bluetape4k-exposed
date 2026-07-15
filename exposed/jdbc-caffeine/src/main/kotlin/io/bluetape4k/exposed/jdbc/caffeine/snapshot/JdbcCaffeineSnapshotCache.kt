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
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import java.io.Serializable
import kotlin.math.max
import kotlin.reflect.KClass

/**
 * A cache-only JDBC Caffeine facade for transaction-aware detached snapshots.
 *
 * Instances own no threads or closeable resources. Database access remains entirely caller-owned.
 */
class JdbcCaffeineSnapshotCache<ID : Any, V : Serializable> private constructor(
    idType: KClass<ID>,
    valueType: KClass<V>,
    private val config: CaffeineSnapshotCacheConfig,
    private val valueSizer: SnapshotValueSizer<V>?,
    internal val validator: CacheSnapshotValueValidator<V>,
    /** Caller-owned bounded failure buffer used by this facade. */
    override val failureBuffer: SnapshotCacheFailureBuffer,
) : SnapshotCacheStore<ID, V> {
    private val cache: Cache<ID, StoredSnapshot<V>> = buildCache(config)
    private val fences = SnapshotLocalFenceRegistry<ID>(config.fenceStripes)
    private val misses = SnapshotMissCapabilityRegistry<ID, V>(config.maxOutstandingMissTokens)

    /** Stable logical identity for this cache. */
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
            cache.cleanUp()
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
                    exception.javaClass.name.takeIf { it.length <= 512 },
                )
            }
            results += result
            index++
        }
        return SnapshotCacheApplyReport(results)
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
        val maximumWeight = config.maximumWeight ?: return 1
        val retainedWeight = requireNotNull(estimatedWeight) {
            "A prepared snapshot weight is required for a weighted Caffeine cache."
        }
        val minimumEntryWeight = max(1L, ceilingDivide(maximumWeight, config.maximumSize))
        return max(retainedWeight, minimumEntryWeight).toInt()
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
    config.maximumWeight?.let { maximumWeight ->
        require(ceilingDivide(maximumWeight, config.maximumSize) <= Int.MAX_VALUE.toLong()) {
            "maximumWeight[$maximumWeight] cannot preserve maximumSize[${config.maximumSize}] with Caffeine weights."
        }
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

private fun ceilingDivide(dividend: Long, divisor: Long): Long = 1L + (dividend - 1L) / divisor

private data class StoredSnapshot<V : Serializable>(
    val snapshot: CacheSnapshot<V>,
    val caffeineWeight: Int,
)
