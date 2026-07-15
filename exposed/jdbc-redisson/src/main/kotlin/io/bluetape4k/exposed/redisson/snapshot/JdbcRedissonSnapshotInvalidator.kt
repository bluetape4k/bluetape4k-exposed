@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.AsyncSnapshotInvalidationStore
import io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi
import io.bluetape4k.exposed.cache.snapshot.MeasuredInvalidation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheLimits
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import java.io.Serializable
import java.lang.reflect.Array as ReflectArray
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

private const val REDISSON_JDBC_BACKEND = "redisson-jdbc"
private const val LONG_KEY_ENCODING = "bt4k-long-be-v1"
private const val UUID_KEY_ENCODING = "bt4k-uuid-be-v1"
private const val MAX_EXCEPTION_TYPE_LENGTH = 512

private val redissonInvalidationQuotas = RedissonInvalidationQuotaRegistry()

/**
 * Asynchronously publishes invalidation-only removals through a caller-owned Redisson local-cache map.
 *
 * This facade intentionally exposes neither reads nor writes. Transaction coordination owns failure observation;
 * this adapter only reports structural outcomes and retains no identifiers after a completion releases its quota.
 */
class JdbcRedissonSnapshotInvalidator<ID : Any> internal constructor(
    private val localCacheMap: RLocalCachedMap<ID, Any?>,
    private val codec: SnapshotRedissonCodec<ID>,
    private val idType: KClass<ID>,
    valueType: KClass<*>,
    private val config: JdbcRedissonSnapshotInvalidatorConfig,
    private val quota: RedissonInvalidationQuota,
    override val failureBuffer: SnapshotCacheFailureBuffer,
    @InternalSnapshotCacheApi
    override val storeInstanceToken: Any,
    private val identifierEncoder: (Any) -> ByteArray = codec::encodeSnapshotIdentifier,
) : AsyncSnapshotInvalidationStore<ID> {

    init {
        requireSupportedIdentifier(codec, idType)
        require(Serializable::class.java.isAssignableFrom(valueType.java)) {
            "Snapshot value type[${valueType.java.name}] must implement Serializable."
        }
        config.requireSafeCodec(codec)
    }

    override val storeId: SnapshotStoreId = SnapshotStoreId(REDISSON_JDBC_BACKEND, config.snapshot.namespace)

    @InternalSnapshotCacheApi
    override val compatibilityFingerprint: String = snapshotNamespaceFingerprint(
        backend = REDISSON_JDBC_BACKEND,
        namespace = config.snapshot.namespace,
        keyRawClass = idType.supportedIdentifierRawClass(),
        snapshotRawClass = valueType.java,
        schemaVersion = config.snapshot.schemaVersion,
        codec = codec,
        synchronizationStrategy = config.synchronizationStrategy,
    )

    @InternalSnapshotCacheApi
    override val limits: SnapshotCacheLimits = SnapshotCacheLimits(
        maxStagedMutations = config.snapshot.maxStagedMutations,
        maxParticipatingStores = config.snapshot.maxParticipatingStores,
        maxStagedWeight = config.maxCommitEncodedKeyBytes.toLong(),
    )

    @InternalSnapshotCacheApi
    override fun measure(id: ID): MeasuredInvalidation<ID> {
        val encoded = identifierEncoder(id)
        require(encoded.isNotEmpty()) { "Canonical snapshot identifier encoding must not be empty." }
        require(encoded.size <= config.maxEncodedKeyBytes) {
            "Canonical snapshot identifier bytes[${encoded.size}] must not exceed " +
                    "maxEncodedKeyBytes[${config.maxEncodedKeyBytes}]."
        }
        return MeasuredInvalidation(id, encoded.size, encoded.sha256())
    }

    @InternalSnapshotCacheApi
    override fun submitInvalidation(
        batch: List<MeasuredInvalidation<ID>>,
    ): CompletionStage<SnapshotCacheApplyReport> {
        if (batch.isEmpty()) return CompletableFuture.completedFuture(SnapshotCacheApplyReport(emptyList()))

        val chunks = batch.partitionByEncodedBytes(config.maxBatchEncodedKeyBytes)
        val collector = InvalidationResultCollector(chunks.size)
        chunks.forEachIndexed { index, chunk -> submitChunk(index, chunk, collector) }
        return collector.completion
    }

    internal fun currentQuotaHealth(): SnapshotInvalidationQuotaHealth = quota.health()

    private fun submitChunk(
        index: Int,
        chunk: List<MeasuredInvalidation<ID>>,
        collector: InvalidationResultCollector,
    ) {
        val affectedCount = chunk.size
        val encodedBytes = try {
            verifyChunk(chunk)
        } catch (exception: Exception) {
            collector.record(index, failedResult(affectedCount, exception))
            return
        }

        val lease = quota.tryAdmit(encodedBytes)
        if (lease == null) {
            collector.record(index, result(SnapshotCacheOutcome.REJECTED, affectedCount))
            return
        }

        val completionClaimed = AtomicBoolean(false)
        try {
            val future = localCacheMap.fastRemoveAsync(*chunk.toTypedIdentifierArray())
            future.whenComplete { _, throwable ->
                try {
                    if (completionClaimed.compareAndSet(false, true)) {
                        val failure = throwable?.unwrapCompletionFailure()
                        when (failure) {
                            null -> collector.record(index, result(SnapshotCacheOutcome.SUCCESS, affectedCount))
                            is Error -> collector.fail(failure)
                            else -> collector.record(index, failedResult(affectedCount, failure))
                        }
                    }
                } finally {
                    lease.release()
                }
            }
        } catch (error: Error) {
            lease.release()
            if (completionClaimed.compareAndSet(false, true)) collector.fail(error)
        } catch (exception: Exception) {
            lease.release()
            if (completionClaimed.compareAndSet(false, true)) {
                collector.record(index, failedResult(affectedCount, exception))
            }
        }
    }

    private fun verifyChunk(chunk: List<MeasuredInvalidation<ID>>): Long {
        var totalBytes = 0L
        chunk.forEach { measured ->
            val encoded = identifierEncoder(measured.id)
            require(encoded.isNotEmpty()) { "Canonical snapshot identifier encoding must not be empty." }
            require(encoded.size <= config.maxEncodedKeyBytes) {
                "Canonical snapshot identifier bytes[${encoded.size}] must not exceed " +
                        "maxEncodedKeyBytes[${config.maxEncodedKeyBytes}]."
            }
            require(encoded.size == measured.encodedBytes && encoded.sha256() == measured.encodedSha256) {
                "Measured snapshot identifier encoding no longer matches its canonical bytes."
            }
            totalBytes += encoded.size.toLong()
        }
        require(totalBytes <= config.maxBatchEncodedKeyBytes.toLong()) {
            "Canonical invalidation chunk bytes[$totalBytes] must not exceed " +
                    "maxBatchEncodedKeyBytes[${config.maxBatchEncodedKeyBytes}]."
        }
        return totalBytes
    }

    @Suppress("UNCHECKED_CAST")
    private fun List<MeasuredInvalidation<ID>>.toTypedIdentifierArray(): Array<ID> {
        val identifiers = ReflectArray.newInstance(idType.javaObjectType, size) as Array<ID>
        forEachIndexed { index, measured -> ReflectArray.set(identifiers, index, measured.id) }
        return identifiers
    }
}

/** Creates a JDBC Redisson snapshot invalidator with explicit runtime type tokens. */
fun <ID : Any, V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID> {
    requireSupportedIdentifier(codec, idType)
    require(Serializable::class.java.isAssignableFrom(valueType.java)) {
        "Snapshot value type[${valueType.java.name}] must implement Serializable."
    }
    config.requireSafeCodec(codec)
    val reservation = redissonInvalidationQuotas.reserveComposition(
        redissonClient = redissonClient,
        descriptor = RedissonInvalidationCompositionDescriptor(
            codec = codec,
            idType = idType,
            valueType = valueType,
            config = config,
            failureBuffer = failureBuffer,
        ),
    )
    try {
        val options = LocalCachedMapOptions.name<ID, Any?>(config.snapshot.namespace).apply {
            codec(codec)
            cacheSize(config.nearCacheMaximumSize)
            syncStrategy(config.synchronizationStrategy)
            reconnectionStrategy(config.reconnectionStrategy)
        }
        val localCacheMap = redissonClient.getLocalCachedMap(options)
        return JdbcRedissonSnapshotInvalidator(
            localCacheMap = localCacheMap,
            codec = codec,
            idType = idType,
            valueType = valueType,
            config = config,
            quota = reservation.quota,
            failureBuffer = failureBuffer,
            storeInstanceToken = reservation.storeInstanceToken,
        ).also { reservation.commit() }
    } catch (failure: Throwable) {
        reservation.rollback()
        throw failure
    }
}

/** Creates a JDBC Redisson snapshot invalidator using reified runtime type tokens. */
inline fun <reified ID : Any, reified V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID> =
    jdbcRedissonSnapshotInvalidator(redissonClient, codec, ID::class, V::class, config, failureBuffer)

/** Returns payload-free bounded admission health for this invalidator's caller-owned Redisson client. */
fun JdbcRedissonSnapshotInvalidator<*>.quotaHealth(): SnapshotInvalidationQuotaHealth = currentQuotaHealth()

private fun <ID : Any> requireSupportedIdentifier(codec: SnapshotRedissonCodec<ID>, idType: KClass<ID>) {
    val expectedEncoding = when (idType) {
        Long::class -> LONG_KEY_ENCODING
        UUID::class -> UUID_KEY_ENCODING
        else -> throw IllegalArgumentException(
            "Distributed snapshot invalidation supports only Long or UUID surrogate identifiers.",
        )
    }
    require(codec.snapshotKeyEncodingId == expectedEncoding) {
        "Snapshot identifier runtime type and canonical codec policy must match."
    }
}

private fun KClass<*>.supportedIdentifierRawClass(): Class<*> = when (this) {
    Long::class -> Long::class.java
    UUID::class -> UUID::class.java
    else -> java
}

private fun <ID : Any> List<MeasuredInvalidation<ID>>.partitionByEncodedBytes(
    maxEncodedBytes: Int,
): List<List<MeasuredInvalidation<ID>>> {
    val chunks = ArrayList<List<MeasuredInvalidation<ID>>>()
    var current = ArrayList<MeasuredInvalidation<ID>>()
    var currentBytes = 0L
    forEach { measured ->
        val measuredBytes = measured.encodedBytes.toLong()
        if (current.isNotEmpty() && currentBytes + measuredBytes > maxEncodedBytes.toLong()) {
            chunks += current
            current = ArrayList()
            currentBytes = 0L
        }
        current += measured
        currentBytes += measuredBytes
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private tailrec fun Throwable.unwrapCompletionFailure(): Throwable = when (this) {
    is CompletionException, is ExecutionException -> cause?.unwrapCompletionFailure() ?: this
    else -> this
}

private fun result(outcome: SnapshotCacheOutcome, affectedCount: Int) = SnapshotCacheOperationResult(
    operation = SnapshotCacheOperation.INVALIDATE,
    outcome = outcome,
    affectedCount = affectedCount,
)

private fun failedResult(affectedCount: Int, failure: Throwable) = SnapshotCacheOperationResult(
    operation = SnapshotCacheOperation.INVALIDATE,
    outcome = SnapshotCacheOutcome.FAILED,
    affectedCount = affectedCount,
    exceptionType = failure.javaClass.name.takeIf { it.length <= MAX_EXCEPTION_TYPE_LENGTH },
)

private class InvalidationResultCollector(expectedResults: Int) {
    private val lock = ReentrantLock()
    private val results = arrayOfNulls<SnapshotCacheOperationResult>(expectedResults)
    private var remaining = expectedResults
    val completion = CompletableFuture<SnapshotCacheApplyReport>()

    fun record(index: Int, result: SnapshotCacheOperationResult) {
        val report = lock.withLock {
            if (results[index] != null) return
            results[index] = result
            remaining--
            if (remaining == 0) SnapshotCacheApplyReport(results.filterNotNull()) else null
        }
        if (report != null) completion.complete(report)
    }

    fun fail(error: Error) {
        completion.completeExceptionally(error)
    }
}
