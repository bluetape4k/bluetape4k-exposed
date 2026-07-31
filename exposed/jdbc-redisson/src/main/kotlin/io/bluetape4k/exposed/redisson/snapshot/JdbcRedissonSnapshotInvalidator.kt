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
import io.bluetape4k.exposed.cache.snapshot.sanitizeSnapshotCacheExceptionType
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

private val redissonInvalidationQuotas = RedissonInvalidationQuotaRegistry()

/**
 * 호출자가 소유한 Redisson local-cache map을 통해 invalidation-only removal을 비동기로 전달합니다.
 *
 * 이 facade는 read와 write를 의도적으로 노출하지 않습니다. Failure 관측은 transaction
 * coordination이 소유하며, 이 adapter는 구조적인 outcome만 보고합니다. Completion이 quota를
 * 해제한 뒤에는 identifier를 보관하지 않습니다.
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
    override val compatibilityFingerprint: String,
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
            throw error
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

/** 명시적인 runtime type token으로 JDBC Redisson snapshot invalidator를 생성합니다. */
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
    val compatibilityFingerprint = snapshotNamespaceFingerprint(
        backend = REDISSON_JDBC_BACKEND,
        namespace = config.snapshot.namespace,
        keyRawClass = idType.supportedIdentifierRawClass(),
        snapshotRawClass = valueType.java,
        schemaVersion = config.snapshot.schemaVersion,
        codec = codec,
        synchronizationStrategy = config.synchronizationStrategy,
    )
    verifyOrClaimSnapshotNamespace(
        redissonClient = redissonClient,
        namespace = config.snapshot.namespace,
        expectedFingerprint = compatibilityFingerprint,
        timeout = config.namespaceVerificationTimeout,
    )
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
            compatibilityFingerprint = compatibilityFingerprint,
            storeInstanceToken = reservation.storeInstanceToken,
        ).also { reservation.commit() }
    } catch (failure: Throwable) {
        reservation.rollback()
        throw failure
    }
}

/** Reified runtime type token을 사용해 JDBC Redisson snapshot invalidator를 생성합니다. */
inline fun <reified ID : Any, reified V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID> =
    jdbcRedissonSnapshotInvalidator(redissonClient, codec, ID::class, V::class, config, failureBuffer)

/** 이 invalidator에서 호출자가 소유한 Redisson client의 payload-free bounded admission health를 반환합니다. */
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
    .toLowerHex()

private fun ByteArray.toLowerHex(): String {
    val encoded = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        encoded[index * 2] = HEX_DIGITS[value ushr 4]
        encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return encoded.concatToString()
}

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
    exceptionType = sanitizeSnapshotCacheExceptionType(failure.javaClass.name),
)

private const val HEX_DIGITS = "0123456789abcdef"

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
