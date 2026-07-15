package io.bluetape4k.exposed.cache.snapshot

import java.io.Serializable
import java.time.Duration

/**
 * Common safety limits and identity for a transaction-aware snapshot cache.
 *
 * [namespace] is an operator-owned, static, non-tenant identifier. It must not contain request, entity, user, or
 * other dynamically derived identifiers. Only the namespace syntax is enforced mechanically.
 *
 * @property namespace stable cache namespace using the form `name:v1`
 * @property schemaVersion application-defined snapshot payload schema version
 * @property maxStagedMutations maximum mutations staged by one transaction
 * @property maxParticipatingStores maximum snapshot stores participating in one transaction
 */
data class SnapshotCacheConfig(
    val namespace: String,
    val schemaVersion: String,
    val maxStagedMutations: Int = 10_000,
    val maxParticipatingStores: Int = 8,
): Serializable {

    init {
        require(NAMESPACE_PATTERN.matches(namespace)) {
            "namespace[$namespace] must match ${NAMESPACE_PATTERN.pattern}."
        }
        require(schemaVersion.isNotBlank()) { "schemaVersion must not be blank." }
        require(maxStagedMutations > 0) {
            "maxStagedMutations[$maxStagedMutations] must be positive."
        }
        require(maxParticipatingStores > 0) {
            "maxParticipatingStores[$maxParticipatingStores] must be positive."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private val NAMESPACE_PATTERN = Regex("[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*")
    }
}

/**
 * Safety limits and expiry policy for a local Caffeine snapshot cache adapter.
 *
 * A non-null [maximumWeight] or [maxStagedWeight] requires a [SnapshotValueSizer] when an adapter is constructed.
 * Configuration construction intentionally does not require the sizer so immutable configuration remains separate
 * from runtime adapter collaborators.
 *
 * @property snapshot common snapshot cache identity and transaction staging limits
 * @property maximumSize maximum number of locally cached entries
 * @property maximumWeight optional maximum estimated retained weight for locally cached entries
 * @property expireAfterWrite expiry duration measured from the last write
 * @property expireAfterAccess optional expiry duration measured from the last access
 * @property maxStagedWeight optional maximum estimated retained weight staged by one transaction
 * @property localDrainBudget maximum time budget for draining local post-transaction work
 * @property fenceStripes power-of-two count of local concurrency fence stripes
 * @property maxOutstandingMissTokens maximum number of outstanding local cache-miss tokens
 */
data class CaffeineSnapshotCacheConfig(
    val snapshot: SnapshotCacheConfig,
    val maximumSize: Long = 10_000,
    val maximumWeight: Long? = null,
    val expireAfterWrite: Duration = Duration.ofMinutes(10),
    val expireAfterAccess: Duration? = null,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration = Duration.ofMillis(250),
    val fenceStripes: Int = 1_024,
    val maxOutstandingMissTokens: Int = 10_000,
): Serializable {

    init {
        require(maximumSize > 0L) { "maximumSize[$maximumSize] must be positive." }
        maximumWeight?.let {
            require(it > 0L) { "maximumWeight[$it] must be positive when set." }
        }
        require(expireAfterWrite > Duration.ZERO) {
            "expireAfterWrite[$expireAfterWrite] must be positive."
        }
        expireAfterAccess?.let {
            require(it > Duration.ZERO) { "expireAfterAccess[$it] must be positive when set." }
        }
        maxStagedWeight?.let {
            require(it > 0L) { "maxStagedWeight[$it] must be positive when set." }
        }
        require(localDrainBudget > Duration.ZERO) {
            "localDrainBudget[$localDrainBudget] must be positive."
        }
        require(fenceStripes in MIN_FENCE_STRIPES..MAX_FENCE_STRIPES && fenceStripes.isPowerOfTwo()) {
            "fenceStripes[$fenceStripes] must be a power of two in $MIN_FENCE_STRIPES..$MAX_FENCE_STRIPES."
        }
        require(maxOutstandingMissTokens > 0) {
            "maxOutstandingMissTokens[$maxOutstandingMissTokens] must be positive."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MIN_FENCE_STRIPES = 64
        private const val MAX_FENCE_STRIPES = 65_536
    }
}

private fun Int.isPowerOfTwo(): Boolean = this > 0 && this and (this - 1) == 0
