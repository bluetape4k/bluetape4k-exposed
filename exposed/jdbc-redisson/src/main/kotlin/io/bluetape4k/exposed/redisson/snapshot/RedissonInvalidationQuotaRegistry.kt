package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import kotlinx.atomicfu.atomic
import org.redisson.api.RedissonClient
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * 하나의 Redisson client identity가 승인한 무효화 작업의 구조적 상태입니다.
 *
 * 이 보고서에는 식별자, Redis endpoint, credential, command payload를 의도적으로 포함하지 않습니다.
 *
 * @property maxOutstandingChunks 설정된 최대 승인 chunk 수
 * @property outstandingChunks 완료 lease가 아직 해제되지 않은 현재 승인 chunk 수
 * @property maxOutstandingEncodedBytes 승인 chunk가 보유할 수 있는 canonical identifier의 최대 byte 수
 * @property outstandingEncodedBytes 승인 chunk가 현재 보유한 canonical identifier byte 수
 * @property rejectedChunks 제한된 승인 quota가 거부한 전체 chunk 수
 * @property saturated 현재 outstanding 값 중 하나라도 설정된 최댓값에 도달했는지 여부
 */
data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

/** 호출자가 소유한 [RedissonClient]마다 weak-identity quota 하나를 관리합니다. */
internal class RedissonInvalidationQuotaRegistry {
    private val lock = ReentrantLock()
    private val staleClients = ReferenceQueue<RedissonClient>()
    private val clients = HashMap<RedissonClientIdentityWeakReference, RedissonClientRegistration>()

    /**
     * [redissonClient]에 고정된 quota를 반환하며, 첫 유효 조회 시 생성합니다.
     *
     * 이후 동일한 client identity 조회에는 처음 고정한 한도를 사용해야 합니다.
     */
    fun quotaFor(
        redissonClient: RedissonClient,
        maxOutstandingChunks: Int,
        maxOutstandingEncodedBytes: Long,
    ): RedissonInvalidationQuota {
        require(maxOutstandingChunks > 0) {
            "maxOutstandingChunks[$maxOutstandingChunks] must be positive."
        }
        require(maxOutstandingEncodedBytes > 0L) {
            "maxOutstandingEncodedBytes[$maxOutstandingEncodedBytes] must be positive."
        }

        return lock.withLock {
            expungeStaleClients()
            val lookup = RedissonClientIdentityWeakReference(redissonClient)
            val existing = clients[lookup]
            if (existing != null) {
                existing.quota.requireMatchingLimits(maxOutstandingChunks, maxOutstandingEncodedBytes)
                existing.quotaPinned = true
                existing.quota
            } else {
                RedissonInvalidationQuota(maxOutstandingChunks, maxOutstandingEncodedBytes).also { quota ->
                    clients[RedissonClientIdentityWeakReference(redissonClient, staleClients)] =
                        RedissonClientRegistration(quota, quotaPinned = true)
                }
            }
        }
    }

/** Redisson map과 상호 작용하기 전에 namespace composition 하나를 예약합니다. */
    fun reserveComposition(
        redissonClient: RedissonClient,
        descriptor: RedissonInvalidationCompositionDescriptor,
    ): RedissonInvalidationCompositionReservation {
        val config = descriptor.config
        return lock.withLock {
            expungeStaleClients()
            val lookup = RedissonClientIdentityWeakReference(redissonClient)
            val registration = clients[lookup]?.also {
                it.quota.requireMatchingLimits(config.maxOutstandingChunks, config.maxOutstandingEncodedBytes)
            } ?: RedissonClientRegistration(
                quota = RedissonInvalidationQuota(
                    config.maxOutstandingChunks,
                    config.maxOutstandingEncodedBytes,
                ),
                quotaPinned = false,
            ).also {
                clients[RedissonClientIdentityWeakReference(redissonClient, staleClients)] = it
            }
            val namespace = descriptor.namespace
            val composition = registration.compositions[namespace]?.also { existing ->
                require(existing.descriptor.matches(descriptor)) {
                    "Redisson snapshot namespace composition must match the first reserved composition."
                }
                existing.inFlightReservations++
            } ?: RedissonInvalidationCompositionRegistration(descriptor).also {
                registration.compositions[namespace] = it
            }
            RedissonInvalidationCompositionReservation(
                registry = this,
                clientKey = lookup,
                namespace = namespace,
                registration = composition,
                quota = registration.quota,
                storeInstanceToken = composition.storeInstanceToken,
            )
        }
    }

    internal fun completeReservation(
        clientKey: RedissonClientIdentityWeakReference,
        namespace: String,
        reservation: RedissonInvalidationCompositionRegistration,
        commit: Boolean,
    ) = lock.withLock {
        val client = clients[clientKey] ?: return@withLock
        val current = client.compositions[namespace]
        check(current === reservation) { "Snapshot composition reservation is no longer current." }
        check(current.inFlightReservations > 0) { "Snapshot composition reservation count cannot become negative." }
        current.inFlightReservations--
        if (commit) {
            current.committed = true
            client.quotaPinned = true
        } else if (!current.committed && current.inFlightReservations == 0) {
            client.compositions.remove(namespace)
        }
        if (!client.quotaPinned && client.compositions.isEmpty()) {
            clients.remove(clientKey)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun expungeStaleClients() {
        while (true) {
            val stale = staleClients.poll() as? RedissonClientIdentityWeakReference ?: return
            clients.remove(stale)
        }
    }
}

/** client namespace마다 고정되는 identity-sensitive local facade composition입니다. */
internal class RedissonInvalidationCompositionDescriptor(
    private val codec: SnapshotRedissonCodec<*>,
    private val idType: KClass<*>,
    private val valueType: KClass<*>,
    internal val config: JdbcRedissonSnapshotInvalidatorConfig,
    private val failureBuffer: SnapshotCacheFailureBuffer,
) {
    val namespace: String get() = config.snapshot.namespace

    fun matches(other: RedissonInvalidationCompositionDescriptor): Boolean =
        codec === other.codec &&
                idType == other.idType &&
                valueType == other.valueType &&
                config == other.config &&
                failureBuffer === other.failureBuffer
}

private class RedissonClientRegistration(
    val quota: RedissonInvalidationQuota,
    var quotaPinned: Boolean,
) {
    val compositions = HashMap<String, RedissonInvalidationCompositionRegistration>()
}

internal class RedissonInvalidationCompositionRegistration(
    val descriptor: RedissonInvalidationCompositionDescriptor,
) {
    val storeInstanceToken: Any = Any()
    var inFlightReservations: Int = 1
    var committed: Boolean = false
}

/** map과 facade 생성에 적용하는 exactly-once 예약입니다. */
internal class RedissonInvalidationCompositionReservation(
    private val registry: RedissonInvalidationQuotaRegistry,
    private val clientKey: RedissonClientIdentityWeakReference,
    private val namespace: String,
    private val registration: RedissonInvalidationCompositionRegistration,
    val quota: RedissonInvalidationQuota,
    val storeInstanceToken: Any,
) {
    private val completed = atomic(false)

    fun commit() {
        check(completed.compareAndSet(expect = false, update = true)) {
            "Snapshot composition reservation already completed."
        }
        registry.completeReservation(clientKey, namespace, registration, commit = true)
    }

    fun rollback() {
        if (completed.compareAndSet(expect = false, update = true)) {
            registry.completeReservation(clientKey, namespace, registration, commit = false)
        }
    }
}

/** 하나의 client identity를 사용하는 invalidator가 공유하는 제한된 chunk-and-byte quota입니다. */
internal class RedissonInvalidationQuota(
    private val maxOutstandingChunks: Int,
    private val maxOutstandingEncodedBytes: Long,
) {
    private val lock = ReentrantLock()
    private var outstandingChunks = 0
    private var outstandingEncodedBytes = 0L
    private var rejectedChunks = 0L

/** chunk 하나와 실제 canonical encoded byte 수를 원자적으로 승인합니다. */
    fun tryAdmit(encodedBytes: Long): RedissonInvalidationQuotaLease? {
        require(encodedBytes > 0L) { "encodedBytes[$encodedBytes] must be positive." }

        return lock.withLock {
            val chunkCapacityAvailable = outstandingChunks < maxOutstandingChunks
            val byteCapacityAvailable = encodedBytes <= maxOutstandingEncodedBytes - outstandingEncodedBytes
            if (!chunkCapacityAvailable || !byteCapacityAvailable) {
                if (rejectedChunks < Long.MAX_VALUE) {
                    rejectedChunks++
                }
                null
            } else {
                outstandingChunks++
                outstandingEncodedBytes += encodedBytes
                RedissonInvalidationQuotaLease(this, encodedBytes)
            }
        }
    }

/** payload를 포함하지 않는 현재 승인 상태 snapshot을 반환합니다. */
    fun health(): SnapshotInvalidationQuotaHealth = lock.withLock {
        SnapshotInvalidationQuotaHealth(
            maxOutstandingChunks = maxOutstandingChunks,
            outstandingChunks = outstandingChunks,
            maxOutstandingEncodedBytes = maxOutstandingEncodedBytes,
            outstandingEncodedBytes = outstandingEncodedBytes,
            rejectedChunks = rejectedChunks,
            saturated = outstandingChunks >= maxOutstandingChunks ||
                    outstandingEncodedBytes >= maxOutstandingEncodedBytes,
        )
    }

    fun requireMatchingLimits(maxOutstandingChunks: Int, maxOutstandingEncodedBytes: Long) {
        require(
            this.maxOutstandingChunks == maxOutstandingChunks &&
                    this.maxOutstandingEncodedBytes == maxOutstandingEncodedBytes,
        ) {
            "Redisson client invalidation quota limits must match the first registered limits."
        }
    }

    fun release(encodedBytes: Long) = lock.withLock {
        check(outstandingChunks > 0) { "Invalidation quota chunk count cannot become negative." }
        check(outstandingEncodedBytes >= encodedBytes) {
            "Invalidation quota encoded-byte count cannot become negative."
        }
        outstandingChunks--
        outstandingEncodedBytes -= encodedBytes
    }
}

/** 승인된 무효화 chunk 하나의 exactly-once ownership token입니다. */
internal class RedissonInvalidationQuotaLease(
    private val quota: RedissonInvalidationQuota,
    private val encodedBytes: Long,
) {
    private val released = atomic(false)

/** 승인된 count를 한 번만 해제하며, 중복 완료 알림은 영향을 주지 않습니다. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            quota.release(encodedBytes)
        }
    }
}

internal class RedissonClientIdentityWeakReference(
    redissonClient: RedissonClient,
    queue: ReferenceQueue<RedissonClient>? = null,
) : WeakReference<RedissonClient>(redissonClient, queue) {
    private val identityHashCode = System.identityHashCode(redissonClient)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RedissonClientIdentityWeakReference) return false
        val client = get() ?: return false
        return client === other.get()
    }
}
