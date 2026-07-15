package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import org.redisson.api.options.LocalCachedMapOptions
import java.io.Serializable
import java.time.Duration

/**
 * Safety and admission limits for the JDBC Redisson snapshot invalidation adapter.
 *
 * This configuration contains no endpoint, username, credential, or near-cache expiry setting. Those remain owned by
 * the caller's Redisson client and repository. [trustedBinaryCache] is an explicit opt-in only for isolated cache data
 * where every writer and payload is trusted.
 *
 * @property snapshot common namespace, schema, and transaction staging limits
 * @property nearCacheMaximumSize maximum local invalidation-map entry count
 * @property maxEncodedKeyBytes maximum bytes accepted for one canonical identifier
 * @property maxBatchEncodedKeyBytes maximum canonical identifier bytes submitted in one async chunk
 * @property maxCommitEncodedKeyBytes maximum canonical identifier bytes staged by one commit
 * @property maxOutstandingChunks maximum async invalidation chunks admitted per Redisson client
 * @property maxOutstandingEncodedBytes maximum admitted canonical identifier bytes per Redisson client
 * @property namespaceVerificationTimeout maximum time allowed to verify the remote namespace marker
 * @property multiNode whether peer-node invalidation is required
 * @property synchronizationStrategy local-cache synchronization strategy
 * @property reconnectionStrategy local-cache recovery strategy after reconnection
 * @property trustedBinaryCache explicit trust opt-in for isolated binary-codec data
 */
data class JdbcRedissonSnapshotInvalidatorConfig(
    val snapshot: SnapshotCacheConfig,
    val nearCacheMaximumSize: Int = 10_000,
    val maxEncodedKeyBytes: Int = 4 * 1024,
    val maxBatchEncodedKeyBytes: Int = 64 * 1024,
    val maxCommitEncodedKeyBytes: Int = 256 * 1024,
    val maxOutstandingChunks: Int = 64,
    val maxOutstandingEncodedBytes: Long = 4L * 1024 * 1024,
    val namespaceVerificationTimeout: Duration = Duration.ofSeconds(2),
    val multiNode: Boolean = true,
    val synchronizationStrategy: LocalCachedMapOptions.SyncStrategy =
        LocalCachedMapOptions.SyncStrategy.INVALIDATE,
    val reconnectionStrategy: LocalCachedMapOptions.ReconnectionStrategy =
        LocalCachedMapOptions.ReconnectionStrategy.CLEAR,
    val trustedBinaryCache: Boolean = false,
) : Serializable {

    init {
        require(nearCacheMaximumSize > 0) {
            "nearCacheMaximumSize[$nearCacheMaximumSize] must be positive."
        }
        require(maxEncodedKeyBytes > 0) { "maxEncodedKeyBytes[$maxEncodedKeyBytes] must be positive." }
        require(maxBatchEncodedKeyBytes > 0) {
            "maxBatchEncodedKeyBytes[$maxBatchEncodedKeyBytes] must be positive."
        }
        require(maxCommitEncodedKeyBytes > 0) {
            "maxCommitEncodedKeyBytes[$maxCommitEncodedKeyBytes] must be positive."
        }
        require(maxOutstandingChunks > 0) { "maxOutstandingChunks[$maxOutstandingChunks] must be positive." }
        require(maxOutstandingEncodedBytes > 0L) {
            "maxOutstandingEncodedBytes[$maxOutstandingEncodedBytes] must be positive."
        }
        require(namespaceVerificationTimeout > Duration.ZERO) {
            "namespaceVerificationTimeout[$namespaceVerificationTimeout] must be positive."
        }
        require(maxBatchEncodedKeyBytes <= maxCommitEncodedKeyBytes) {
            "maxBatchEncodedKeyBytes[$maxBatchEncodedKeyBytes] must not exceed " +
                    "maxCommitEncodedKeyBytes[$maxCommitEncodedKeyBytes]."
        }
        require(synchronizationStrategy != LocalCachedMapOptions.SyncStrategy.UPDATE) {
            "synchronizationStrategy UPDATE is not valid for invalidation-only snapshot caches."
        }
        require(!multiNode || synchronizationStrategy == LocalCachedMapOptions.SyncStrategy.INVALIDATE) {
            "Multi-node snapshot caches require INVALIDATE synchronization."
        }
        require(reconnectionStrategy == LocalCachedMapOptions.ReconnectionStrategy.CLEAR) {
            "Snapshot invalidators require CLEAR reconnection strategy."
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
