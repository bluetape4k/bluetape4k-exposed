package io.bluetape4k.exposed.cache.snapshot

import java.io.Serializable

/**
 * Detached, immutable value stored by a snapshot cache.
 *
 * @property value serializable value detached from transaction-scoped persistence state
 * @property revision optional application-defined consistency revision
 */
data class CacheSnapshot<V: Serializable>(
    val value: V,
    val revision: String? = null,
): Serializable {
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
fun interface CacheSnapshotMapper<S, V: Serializable> {
    /**
     * Creates a detached snapshot from [source].
     */
    fun toSnapshot(source: S): CacheSnapshot<V>
}

/**
 * Validates a detached snapshot value before it is admitted to a cache.
 */
fun interface CacheSnapshotValueValidator<V: Serializable> {
    /**
     * Validates [value] or throws [IllegalArgumentException] when it is not cache-safe.
     */
    fun validate(value: V)
}

/**
 * Estimates the retained heap size of a detached snapshot value.
 */
fun interface SnapshotValueSizer<V: Serializable> {
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
fun <V: Serializable> rejectDirectEntitySnapshotValues(): CacheSnapshotValueValidator<V> =
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
fun <V: Serializable> maximumEstimatedPayloadBytes(
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
