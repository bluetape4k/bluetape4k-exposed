@file:OptIn(InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.r2dbc.caffeine.snapshot

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
 * 트랜잭션을 인식하는 분리 스냅샷을 위한 캐시 전용 R2DBC Caffeine 파사드입니다.
 *
 * 인스턴스는 스레드나 닫아야 할 리소스를 소유하지 않습니다.
 * 데이터베이스 접근의 생명주기는 전적으로 호출자가 관리합니다.
 */
class R2dbcCaffeineSnapshotCache<ID : Any, V : Serializable> private constructor(
    idType: KClass<ID>,
    valueType: KClass<V>,
    private val config: CaffeineSnapshotCacheConfig,
    private val valueSizer: SnapshotValueSizer<V>?,
    internal val validator: CacheSnapshotValueValidator<V>,
/** 이 파사드가 사용하는 호출자 소유의 용량 제한 실패 버퍼입니다. */
    override val failureBuffer: SnapshotCacheFailureBuffer,
) : SnapshotCacheStore<ID, V> {
    private val cache: Cache<ID, StoredSnapshot<V>> = buildCache(config)
    private val fences = SnapshotLocalFenceRegistry<ID>(config.fenceStripes)
    private val misses = SnapshotMissCapabilityRegistry<ID, V>(config.maxOutstandingMissTokens)
    private val maintenanceLock = ReentrantLock()

/** 이 캐시의 안정적인 논리 식별자입니다. */
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
     * [id]의 현재 캐시 스냅샷 또는 불투명한 일회성 miss 권한을 반환합니다.
     *
     * miss는 호출자의 데이터베이스 작업 전에 포착됩니다.
     * 커밋 전에 더 최신 로컬 변경이 세대를 전진시키면 해당 miss를 거부합니다.
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
        private const val BACKEND = "caffeine-r2dbc"
        private const val VERSION = "r2dbc-caffeine-snapshot-v1"

        internal fun <ID : Any, V : Serializable> create(
            idType: KClass<ID>,
            valueType: KClass<V>,
            config: CaffeineSnapshotCacheConfig,
            valueSizer: SnapshotValueSizer<V>?,
            validator: CacheSnapshotValueValidator<V>,
            failureBuffer: SnapshotCacheFailureBuffer,
        ): R2dbcCaffeineSnapshotCache<ID, V> =
            R2dbcCaffeineSnapshotCache(idType, valueType, config, valueSizer, validator, failureBuffer)
    }

    private fun caffeineWeight(estimatedWeight: Long?): Int {
        if (config.maximumWeight == null) return 1
        val retainedWeight = requireNotNull(estimatedWeight) {
            "A prepared snapshot weight is required for a weighted Caffeine cache."
        }
        return retainedWeight.toInt()
    }
}

/** 명시적 런타임 타입 토큰으로 캐시 전용 R2DBC Caffeine 스냅샷 파사드를 생성합니다. */
fun <ID : Any, V : Serializable> r2dbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V> {
    require(valueSizer != null || config.maximumWeight == null && config.maxStagedWeight == null) {
        "A SnapshotValueSizer is required when maximumWeight or maxStagedWeight is configured."
    }
    return R2dbcCaffeineSnapshotCache.create(idType, valueType, config, valueSizer, validator, failureBuffer)
}

/** 구체화된 런타임 타입 토큰으로 캐시 전용 R2DBC Caffeine 스냅샷 파사드를 생성합니다. */
inline fun <reified ID : Any, reified V : Serializable> r2dbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V> =
    r2dbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

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
    val snapshot: CacheSnapshot<V>,
    val caffeineWeight: Int,
)
