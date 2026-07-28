package io.bluetape4k.exposed.cache.snapshot

import java.io.Serializable

/**
 * Snapshot cache가 저장하는 분리된 값 봉투입니다.
 *
 * 봉투의 참조는 불변입니다. 호출자는 영속성 상태를 트랜잭션 범위 밖의 값으로 매핑하고,
 * cache 계약이 요구하는 경우 전체 값 그래프가 깊은 불변성을 갖도록 보장해야 합니다.
 *
 * @property value 트랜잭션 범위의 영속성 상태에서 분리된 직렬화 가능 값입니다. DAO entity나 요청 상태를
 * 직접 보관하지 않고 cache backend가 안전하게 보유할 수 있는 DTO/값 객체여야 합니다.
 * @property revision 선택적인 애플리케이션 정의 일관성 revision입니다. optimistic consistency, schema
 * evolution, stale-read 판별에 사용할 수 있으며, cache 계층은 값을 해석하지 않습니다.
 */
data class CacheSnapshot<V : Serializable>(
    /** Cache backend에 저장될 실제 분리 값입니다. */
    val value: V,
    /** 호출자가 정의한 consistency/version metadata이며, 없으면 `null`입니다. */
    val revision: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Maps a source object to a detached cache snapshot.
 *
 * Implementations must copy persistence state into an immutable serializable value instead of retaining
 * transaction-scoped entities or request state.
 */
fun interface CacheSnapshotMapper<S, V : Serializable> {
    /**
     * Creates a detached snapshot from [source].
     */
    fun toSnapshot(source: S): CacheSnapshot<V>
}

/**
 * Validates a detached snapshot value before it is admitted to a cache.
 */
fun interface CacheSnapshotValueValidator<V : Serializable> {
    /**
     * Validates [value] or throws [IllegalArgumentException] when it is not cache-safe.
     */
    fun validate(value: V)
}

/**
 * Estimates the retained heap size of a detached snapshot value.
 */
fun interface SnapshotValueSizer<V : Serializable> {
    /**
     * Returns the estimated retained size of [value] in bytes.
     *
     * The estimate must be zero or greater.
     */
    fun estimatedRetainedBytes(value: V): Long
}

/**
 * Creates a validator that rejects a direct top-level Exposed DAO `Entity` value when Exposed DAO is available.
 *
 * The DAO base class is resolved by name so constructing and using this validator for ordinary DTOs remains safe
 * when the optional Exposed DAO artifact is absent. The validator intentionally does not reflect through the value
 * graph; callers remain responsible for mapping nested state to immutable DTOs.
 */
fun <V : Serializable> rejectDirectEntitySnapshotValues(): CacheSnapshotValueValidator<V> =
    CacheSnapshotValueValidator { value ->
        val entityBaseClass = resolveExposedDaoEntityClass(value.javaClass)
        require(entityBaseClass?.isAssignableFrom(value.javaClass) != true) {
            "Direct Exposed DAO Entity snapshot values are forbidden: ${value.javaClass.name}."
        }
    }

/**
 * Creates a validator that rejects values whose estimated retained size exceeds [limit].
 *
 * @param sizer application-provided retained-size estimator
 * @param limit maximum accepted estimate in bytes; must be positive
 */
fun <V : Serializable> maximumEstimatedPayloadBytes(
    sizer: SnapshotValueSizer<V>,
    limit: Long,
): CacheSnapshotValueValidator<V> {
    require(limit > 0L) { "limit[$limit] must be positive." }

    return CacheSnapshotValueValidator { value ->
        val estimate = sizer.estimatedRetainedBytes(value)
        require(estimate >= 0L) { "estimatedRetainedBytes[$estimate] must not be negative." }
        require(estimate <= limit) {
            "estimatedRetainedBytes[$estimate] must not exceed limit[$limit]."
        }
    }
}

private const val EXPOSED_DAO_ENTITY_CLASS_NAME = "org.jetbrains.exposed.v1.dao.Entity"

private fun resolveExposedDaoEntityClass(valueClass: Class<*>): Class<*>? {
    val classLoaders = listOfNotNull(
        valueClass.classLoader,
        CacheSnapshotValueValidator::class.java.classLoader,
        Thread.currentThread().contextClassLoader,
    ).distinct()

    return classLoaders.firstNotNullOfOrNull { classLoader ->
        try {
            Class.forName(EXPOSED_DAO_ENTITY_CLASS_NAME, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
