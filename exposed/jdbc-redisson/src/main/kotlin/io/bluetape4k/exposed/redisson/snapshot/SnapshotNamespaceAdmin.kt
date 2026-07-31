@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.exposed.cache.snapshot.requireSafeSnapshotCacheExceptionType
import io.bluetape4k.exposed.cache.snapshot.sanitizeSnapshotCacheExceptionType
import org.redisson.api.RFuture
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.RedisTimeoutException
import org.redisson.client.codec.StringCodec
import java.io.Serializable
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val SNAPSHOT_NAMESPACE_PATTERN = Regex("[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*")
private val SNAPSHOT_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")

private const val MARKER_ABSENT = 0L
private const val MARKER_EXACT = 1L
private const val MARKER_MISMATCH = 2L

private val INSPECT_NAMESPACE_SCRIPT = """
    local marker = redis.call('get', KEYS[1])
    local mapExists = redis.call('exists', KEYS[2])
    if not marker then
        return {0, mapExists}
    end
    if marker == ARGV[1] then
        return {1, mapExists}
    end
    return {2, mapExists}
""".trimIndent()

private val CLAIM_OR_COMPARE_NAMESPACE_SCRIPT = """
    local marker = redis.call('get', KEYS[1])
    local mapExists = redis.call('exists', KEYS[2])
    if not marker then
        if mapExists == 1 then
            return {0, 1}
        end
        redis.call('set', KEYS[1], ARGV[1])
        return {0, 0}
    end
    if marker == ARGV[1] then
        return {1, mapExists}
    end
    return {2, mapExists}
""".trimIndent()

private val DELETE_EXACT_MARKER_SCRIPT = """
    local marker = redis.call('get', KEYS[1])
    if not marker then
        return 0
    end
    if marker ~= ARGV[1] then
        return -1
    end
    return redis.call('unlink', KEYS[1])
""".trimIndent()

/**
 * 운영자의 명시적 검토가 필요한 파괴적 snapshot-cache namespace 작업을 표시합니다.
 *
 * 이 API는 정지된 namespace에만 사용합니다. network isolation과 namespace 전용 Redis ACL identity가 필요합니다.
 * ACL은 marker와 map 조회 및 unlink, local-cache clear 범위의 pub/sub,
 * 임시 `${namespace}:clear:*` semaphore key와 channel을 허용하고 global keyevent subscription은 거부해야 합니다.
 * 이 API를 request-facing application path에 노출해서는 안 됩니다. fingerprint는 실수 방지 장치이지 권한이 아닙니다.
 *
 * Contract anchors: quiesce; network isolation; dedicated namespace-scoped Redis ACL;
 * marker and map inspection and unlink; local-cache clear scoped pub/sub;
 * `${namespace}:clear:*` semaphore keys and channels; deny global keyevent subscription.
 * This API must never be exposed through request-facing paths.
 * The fingerprint is an accident guard, not authorization.
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class DelicateSnapshotCacheAdminApi

/** 제한된 snapshot namespace 정리 시도의 구조적 결과입니다. */
enum class SnapshotNamespaceCleanupOutcome {
/** 요청한 정리와 최종 remote-state 검증을 완료했습니다. */
    COMPLETED,

/** 호출자 local view를 비운 뒤 remote map과 marker가 없음을 다시 검증했습니다. */
    ALREADY_COMPLETE,

/** map과 호출자 local view를 비우고 정확한 marker는 유지했습니다. */
    MARKER_RETAINED,

/** 정리 command는 승인됐지만 공유 deadline 만료 시 최종 상태를 알 수 없었습니다. */
    TIMED_OUT_ACCEPTED_UNKNOWN,

/** 요청한 terminal state를 입증하기 전에 정리가 fail-closed로 실패했습니다. */
    FAILED,
}

/**
 * snapshot namespace 정리의 구조적 결과입니다.
 *
 * [exceptionType]에는 exception class 이름만 포함합니다. message, endpoint, credential 또는 임의의 client text는
 * 포함하지 않습니다.
 */
data class SnapshotNamespaceCleanupResult(
    val outcome: SnapshotNamespaceCleanupOutcome,
    val mapAbsent: Boolean,
    val markerPresent: Boolean,
    val exceptionType: String? = null,
) : Serializable {
    init {
        exceptionType?.let(::requireSafeSnapshotCacheExceptionType)
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 정지된 snapshot namespace와 compatibility marker를 함께 비웁니다.
 *
 * 운영자는 먼저 모든 writer를 중지하고 모든 live client에서 namespace를 제거한 뒤 in-flight traffic을 비워야 합니다.
 * network isolation과 namespace 전용 Redis ACL identity를 사용합니다. ACL은 marker와 map 조회 및 unlink,
 * local-cache clear 범위의 pub/sub, 임시 `${namespace}:clear:*` semaphore key와 channel을 허용하고
 * global keyevent subscription은 거부해야 합니다. 이 함수를 request-facing application path에 노출해서는 안 됩니다.
 * expected fingerprint는 실수 방지 장치이지 권한이 아닙니다. map unlink는 marker unlink보다 먼저 승인되고
 * 하나의 shared monotonic deadline을 사용하며 취소되지 않습니다. 이 함수를 다시 실행해 안전하게 재개할 수 있습니다.
 */
@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearSnapshotNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult =
    clearNamespace(redissonClient, codec, namespace, expectedFingerprint, timeout, retainMarker = false)

/**
 * 정확한 compatibility marker를 유지하면서 정지된 snapshot namespace map과 호출자 local view를 비웁니다.
 *
 * 이 rollback 준비 작업에는 [clearSnapshotNamespace]와 같은 quiescence, namespace 전용 Redis ACL,
 * network isolation, request-facing 노출 금지 조건을 적용합니다. fingerprint는 실수 방지 장치이지 권한이 아닙니다.
 * 승인된 command는 취소되지 않으며, 작업을 다시 실행해 부분 정리 상태를 확인하고 재개합니다.
 */
@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearMapRetainingMarker(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult =
    clearNamespace(redissonClient, codec, namespace, expectedFingerprint, timeout, retainMarker = true)

internal enum class SnapshotNamespaceMarkerVerification {
    CLAIMED,
    MATCHED,
}

/** namespace 기반 facade가 map에 접근하기 전에 사용하는 원자적 fail-closed marker claim/compare 경계입니다. */
internal fun verifyOrClaimSnapshotNamespace(
    redissonClient: RedissonClient,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration,
): SnapshotNamespaceMarkerVerification {
    val deadline = validateAdminInputs(namespace, expectedFingerprint, timeout)
    val state = try {
        val scriptKeys = resolveSnapshotNamespaceScriptKeys(redissonClient, namespace)
        inspectWithScript(
            redissonClient = redissonClient,
            script = CLAIM_OR_COMPARE_NAMESPACE_SCRIPT,
            scriptKeys = scriptKeys,
            expectedFingerprint = expectedFingerprint,
            deadline = deadline,
        )
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (error: Error) {
        throw error
    } catch (exception: Exception) {
        val failure = exception.unwrapFutureFailure()
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
        }
        throw failure
    }
    return when {
        state.marker == RemoteMarker.ABSENT && state.mapAbsent -> SnapshotNamespaceMarkerVerification.CLAIMED
        state.marker == RemoteMarker.EXACT -> SnapshotNamespaceMarkerVerification.MATCHED
        else -> throw IllegalStateException("Snapshot namespace compatibility marker could not be verified.")
    }
}

internal fun snapshotNamespaceMarkerKey(mappedNamespace: String): String {
    val slotTag = redisClusterSlotTag(mappedNamespace)
    val namespaceHash = MessageDigest.getInstance("SHA-256")
        .digest(mappedNamespace.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "{$slotTag}:__bt4k_snapshot_fingerprint:$namespaceHash"
}

private class SnapshotNamespaceScriptKeys(
    val marker: String,
    val map: String,
)

private fun resolveSnapshotNamespaceScriptKeys(
    redissonClient: RedissonClient,
    namespace: String,
): SnapshotNamespaceScriptKeys {
    val mapper = redissonClient.config.nameMapper
    val mappedNamespace = checkNotNull(mapper.map(namespace)) {
        "Redisson NameMapper must not map the snapshot namespace to null."
    }
    check(mappedNamespace.isNotEmpty()) { "Redisson NameMapper must not map the snapshot namespace to an empty key." }
    val physicalMarker = snapshotNamespaceMarkerKey(mappedNamespace)
    val rawMarker = checkNotNull(mapper.unmap(physicalMarker)) {
        "Redisson NameMapper must not unmap the snapshot namespace marker key to null."
    }
    val remappedMarker = checkNotNull(mapper.map(rawMarker)) {
        "Redisson NameMapper must not remap the snapshot namespace marker key to null."
    }
    check(remappedMarker == physicalMarker) {
        "Redisson NameMapper must round-trip the snapshot namespace marker key."
    }
    return SnapshotNamespaceScriptKeys(rawMarker, namespace)
}

private fun redisClusterSlotTag(key: String): String {
    val open = key.indexOf('{')
    if (open < 0) {
        check('}' !in key) {
            "Mapped snapshot namespace must not contain a stray Redis Cluster hash-tag closing brace."
        }
        return key
    }
    val close = key.indexOf('}', startIndex = open + 1)
    check(close > open + 1) {
        "Mapped snapshot namespace must not contain an empty or malformed first Redis Cluster hash tag."
    }
    return key.substring(open + 1, close)
}

private fun <ID : Any> clearNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration,
    retainMarker: Boolean,
): SnapshotNamespaceCleanupResult {
    val deadline = validateAdminInputs(namespace, expectedFingerprint, timeout)
    var mapAbsent = false
    var markerPresent = false
    var cleanupAccepted = false

    try {
        val scriptKeys = resolveSnapshotNamespaceScriptKeys(redissonClient, namespace)
        val initial = inspectNamespace(redissonClient, scriptKeys, expectedFingerprint, deadline)
        mapAbsent = initial.mapAbsent
        markerPresent = initial.marker != RemoteMarker.ABSENT

        val initiallyAlreadyComplete = when (initial.marker) {
            RemoteMarker.MISMATCH -> return failedState(mapAbsent, markerPresent)
            RemoteMarker.ABSENT -> {
                if (!initial.mapAbsent || retainMarker) return failedState(mapAbsent, markerPresent)
                true
            }
            RemoteMarker.EXACT -> false
        }

        val options = LocalCachedMapOptions.name<ID, Any?>(namespace).apply {
            codec(codec)
            expirationEventPolicy(LocalCachedMapOptions.ExpirationEventPolicy.DONT_SUBSCRIBE)
        }
        val map = redissonClient.getLocalCachedMap(options)
        var mapFailure: Throwable? = null
        try {
            if (!mapAbsent) {
                val unlink = map.unlinkAsync()
                cleanupAccepted = true
                deadline.waitFor(unlink)
                mapAbsent = true
            }

            val clearLocal = map.clearLocalCacheAsync()
            cleanupAccepted = true
            deadline.waitFor(clearLocal)

            val afterMapCleanup = inspectNamespace(redissonClient, scriptKeys, expectedFingerprint, deadline)
            mapAbsent = afterMapCleanup.mapAbsent
            markerPresent = afterMapCleanup.marker != RemoteMarker.ABSENT
            if (!mapAbsent || afterMapCleanup.marker == RemoteMarker.MISMATCH) {
                return failedState(mapAbsent, markerPresent)
            }

            if (initiallyAlreadyComplete) {
                if (afterMapCleanup.marker != RemoteMarker.ABSENT) return failedState(mapAbsent, markerPresent)
                return SnapshotNamespaceCleanupResult(
                    SnapshotNamespaceCleanupOutcome.ALREADY_COMPLETE,
                    mapAbsent = true,
                    markerPresent = false,
                )
            }

            if (retainMarker) {
                if (afterMapCleanup.marker != RemoteMarker.EXACT) return failedState(mapAbsent, markerPresent)
                return SnapshotNamespaceCleanupResult(
                    SnapshotNamespaceCleanupOutcome.MARKER_RETAINED,
                    mapAbsent = true,
                    markerPresent = true,
                )
            }

            if (afterMapCleanup.marker == RemoteMarker.EXACT) {
                val markerDelete = deleteExactMarker(redissonClient, scriptKeys, expectedFingerprint)
                cleanupAccepted = true
                if (deadline.waitFor(markerDelete) < 0L) return failedState(mapAbsent = true, markerPresent = true)
            }

            val terminal = inspectNamespace(redissonClient, scriptKeys, expectedFingerprint, deadline)
            mapAbsent = terminal.mapAbsent
            markerPresent = terminal.marker != RemoteMarker.ABSENT
            if (terminal.mapAbsent && terminal.marker == RemoteMarker.ABSENT) {
                return SnapshotNamespaceCleanupResult(
                    SnapshotNamespaceCleanupOutcome.COMPLETED,
                    mapAbsent = true,
                    markerPresent = false,
                )
            }
            return failedState(mapAbsent, markerPresent)
        } catch (failure: Throwable) {
            mapFailure = failure.unwrapFutureFailure()
            throw failure
        } finally {
            try {
                map.destroy()
            } catch (destroyFailure: Throwable) {
                val primary = mapFailure ?: throw destroyFailure
                if (primary !== destroyFailure) primary.addSuppressed(destroyFailure)
            }
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (error: Error) {
        throw error
    } catch (exception: Exception) {
        val failure = exception.unwrapFutureFailure()
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        if (failure is Error) throw failure
        val outcome = if ((failure is TimeoutException || failure is RedisTimeoutException) && cleanupAccepted) {
            SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN
        } else {
            SnapshotNamespaceCleanupOutcome.FAILED
        }
        return SnapshotNamespaceCleanupResult(
            outcome = outcome,
            mapAbsent = mapAbsent,
            markerPresent = markerPresent,
            exceptionType = sanitizeSnapshotCacheExceptionType(failure.javaClass.name),
        )
    }
}

private fun validateAdminInputs(
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration,
): MonotonicDeadline {
    require(SNAPSHOT_NAMESPACE_PATTERN.matches(namespace)) {
        "namespace must match ${SNAPSHOT_NAMESPACE_PATTERN.pattern}."
    }
    require(SNAPSHOT_FINGERPRINT_PATTERN.matches(expectedFingerprint)) {
        "expectedFingerprint must be lowercase SHA-256 hex."
    }
    val timeoutNanos = try {
        timeout.toNanos()
    } catch (exception: ArithmeticException) {
        throw IllegalArgumentException("timeout must be representable in nanoseconds.", exception)
    }
    require(timeoutNanos > 0L) { "timeout must be positive." }
    return MonotonicDeadline(timeoutNanos)
}

private fun inspectNamespace(
    redissonClient: RedissonClient,
    scriptKeys: SnapshotNamespaceScriptKeys,
    expectedFingerprint: String,
    deadline: MonotonicDeadline,
): RemoteNamespaceState =
    inspectWithScript(redissonClient, INSPECT_NAMESPACE_SCRIPT, scriptKeys, expectedFingerprint, deadline)

private fun inspectWithScript(
    redissonClient: RedissonClient,
    script: String,
    scriptKeys: SnapshotNamespaceScriptKeys,
    expectedFingerprint: String,
    deadline: MonotonicDeadline,
): RemoteNamespaceState {
    val result = redissonClient.getScript(StringCodec())
        .evalAsync<List<Any?>>(
            RScript.Mode.READ_WRITE,
            script,
            RScript.ReturnType.LIST,
            listOf(scriptKeys.marker, scriptKeys.map),
            expectedFingerprint,
        )
    return RemoteNamespaceState.from(deadline.waitFor(result))
}

private fun deleteExactMarker(
    redissonClient: RedissonClient,
    scriptKeys: SnapshotNamespaceScriptKeys,
    expectedFingerprint: String,
): RFuture<Long> =
    redissonClient.getScript(StringCodec()).evalAsync(
        RScript.Mode.READ_WRITE,
        DELETE_EXACT_MARKER_SCRIPT,
        RScript.ReturnType.LONG,
        listOf(scriptKeys.marker),
        expectedFingerprint,
    )

private fun failedState(mapAbsent: Boolean, markerPresent: Boolean): SnapshotNamespaceCleanupResult =
    SnapshotNamespaceCleanupResult(
        outcome = SnapshotNamespaceCleanupOutcome.FAILED,
        mapAbsent = mapAbsent,
        markerPresent = markerPresent,
        exceptionType = IllegalStateException::class.java.name,
    )

private enum class RemoteMarker {
    ABSENT,
    EXACT,
    MISMATCH,
}

private data class RemoteNamespaceState(
    val marker: RemoteMarker,
    val mapAbsent: Boolean,
) {
    companion object {
        fun from(result: List<Any?>): RemoteNamespaceState {
            require(result.size == 2) { "Unexpected snapshot namespace marker response." }
            val marker = when ((result[0] as? Number)?.toLong()) {
                MARKER_ABSENT -> RemoteMarker.ABSENT
                MARKER_EXACT -> RemoteMarker.EXACT
                MARKER_MISMATCH -> RemoteMarker.MISMATCH
                else -> throw IllegalStateException("Unexpected snapshot namespace marker state.")
            }
            val mapAbsent = when ((result[1] as? Number)?.toLong()) {
                0L -> true
                1L -> false
                else -> throw IllegalStateException("Unexpected snapshot namespace map state.")
            }
            return RemoteNamespaceState(marker, mapAbsent)
        }
    }
}

private class MonotonicDeadline(private val timeoutNanos: Long) {
    private val startedAt = System.nanoTime()

    fun <T> waitFor(future: RFuture<T>): T {
        val remaining = remainingNanos()
        val value = try {
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        remainingNanos()
        return value
    }

    private fun remainingNanos(): Long {
        val elapsed = System.nanoTime() - startedAt
        val remaining = timeoutNanos - elapsed
        if (remaining <= 0L) throw TimeoutException("Snapshot namespace administration deadline expired.")
        return remaining
    }
}

private fun Throwable.unwrapFutureFailure(): Throwable {
    var current = this
    while (current is ExecutionException || current is CompletionException) {
        val cause = current.cause ?: break
        current = cause
    }
    return current
}
