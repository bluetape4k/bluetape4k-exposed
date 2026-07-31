package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.redisson.repository.ExposedRedissonCodecSafety
import org.redisson.api.options.LocalCachedMapOptions
import java.io.Serializable
import java.time.Duration

/**
 * JDBC Redisson snapshot invalidation adapter의 안전 및 admission 한도입니다.
 *
 * 이 configuration에는 endpoint, username, credential, near-cache expiry 설정이 없습니다.
 * 해당 설정은 호출자의 Redisson client와 repository가 계속 소유합니다. [trustedBinaryCache]는
 * 모든 writer와 payload를 신뢰하는 격리 cache data에서만 명시적으로 opt-in해야 합니다.
 *
 * @property snapshot 공통 namespace, schema, transaction staging 한도
 * @property nearCacheMaximumSize local invalidation-map의 최대 entry 수
 * @property maxEncodedKeyBytes canonical identifier 하나에 허용할 최대 byte 수
 * @property maxBatchEncodedKeyBytes 비동기 chunk 하나로 제출할 canonical identifier의 최대 byte 수
 * @property maxCommitEncodedKeyBytes commit 하나가 staging할 canonical identifier의 최대 byte 수
 * @property maxOutstandingChunks Redisson client마다 허용할 최대 비동기 invalidation chunk 수
 * @property maxOutstandingEncodedBytes Redisson client마다 admission할 canonical identifier의 최대 byte 수
 * @property namespaceVerificationTimeout remote namespace marker 검증에 허용할 최대 시간
 * @property multiNode peer-node invalidation이 필요한지 여부
 * @property synchronizationStrategy local-cache synchronization 전략
 * @property reconnectionStrategy reconnection 후 local-cache 복구 전략
 * @property trustedBinaryCache 격리된 binary-codec data에 대한 명시적 trust opt-in
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
        try {
            namespaceVerificationTimeout.toNanos()
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException(
                "namespaceVerificationTimeout must be representable in nanoseconds.",
                exception,
            )
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

/** 이 invalidator consumer가 독립적으로 소유하는 binary-codec trust authority를 적용합니다. */
internal fun JdbcRedissonSnapshotInvalidatorConfig.requireSafeCodec(codec: SnapshotRedissonCodec<*>) {
    ExposedRedissonCodecSafety.requireSafe(codec, trustedBinaryCache)
}
