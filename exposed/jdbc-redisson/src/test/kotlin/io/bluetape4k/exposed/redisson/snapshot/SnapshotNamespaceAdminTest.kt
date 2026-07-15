@file:OptIn(DelicateSnapshotCacheAdminApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.RedisTimeoutException
import org.redisson.client.codec.StringCodec
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SnapshotNamespaceAdminTest {

    private val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())

    @Test
    fun `invalid inputs fail before every Redisson interaction`() {
        val invalidNamespaces = listOf("", "Orders:v1", "orders", "orders:v0", "orders:v01", "a".repeat(64) + ":v1")
        val invalidFingerprints = listOf("", "A".repeat(64), "f".repeat(63), "g".repeat(64))
        val invalidTimeouts = listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))

        listOf<(RedissonClient, String, String, Duration) -> SnapshotNamespaceCleanupResult>(
            { client, namespace, fingerprint, timeout ->
                clearSnapshotNamespace(client, codec, namespace, fingerprint, timeout)
            },
            { client, namespace, fingerprint, timeout ->
                clearMapRetainingMarker(client, codec, namespace, fingerprint, timeout)
            },
        ).forEach { operation ->
            invalidNamespaces.forEach { invalid ->
                val admin = RecordingAdmin()
                assertFailsWith<IllegalArgumentException> {
                    operation(admin.client, invalid, FINGERPRINT, Duration.ofSeconds(2))
                }
                admin.events shouldBeEqualTo emptyList()
            }
            invalidFingerprints.forEach { invalid ->
                val admin = RecordingAdmin()
                assertFailsWith<IllegalArgumentException> {
                    operation(admin.client, NAMESPACE, invalid, Duration.ofSeconds(2))
                }
                admin.events shouldBeEqualTo emptyList()
            }
            invalidTimeouts.forEach { invalid ->
                val admin = RecordingAdmin()
                assertFailsWith<IllegalArgumentException> {
                    operation(admin.client, NAMESPACE, FINGERPRINT, invalid)
                }
                admin.events shouldBeEqualTo emptyList()
            }
        }
    }

    @Test
    fun `maximum nanosecond-representable timeout remains a valid bounded input`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        val result = clearSnapshotNamespace(
            admin.client,
            codec,
            NAMESPACE,
            FINGERPRINT,
            Duration.ofNanos(Long.MAX_VALUE),
        )

        result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.ALREADY_COMPLETE
        admin.events shouldBeEqualTo listOf(
            "script", "wait:script-1",
            "map-access", "local-clear", "wait:local-clear",
            "script", "wait:script-2",
            "destroy",
        )
    }

    @Test
    fun `atomic marker verification claims only an empty namespace and accepts an exact marker`() {
        val claimed = RecordingAdmin().apply { scriptReturns(markerState(MARKER_ABSENT, mapExists = false)) }
        val matched = RecordingAdmin().apply { scriptReturns(markerState(MARKER_EXACT, mapExists = true)) }

        verifyOrClaimSnapshotNamespace(claimed.client, NAMESPACE, FINGERPRINT, Duration.ofSeconds(2))
            .shouldBeEqualTo(SnapshotNamespaceMarkerVerification.CLAIMED)
        verifyOrClaimSnapshotNamespace(matched.client, NAMESPACE, FINGERPRINT, Duration.ofSeconds(2))
            .shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)

        claimed.events shouldBeEqualTo listOf("script", "wait:script-1")
        matched.events shouldBeEqualTo listOf("script", "wait:script-1")
        claimed.scriptCalls.single().keys shouldBeEqualTo listOf(snapshotNamespaceMarkerKey(NAMESPACE), NAMESPACE)
        claimed.scriptCalls.single().arguments shouldBeEqualTo listOf(FINGERPRINT)
    }

    @Test
    fun `atomic marker verification restores interrupt status and rethrows the same interruption`() {
        val interruption = InterruptedException("verification interrupted")
        val admin = RecordingAdmin().apply { scriptInterrupts(interruption) }
        Thread.interrupted()

        try {
            val thrown = assertFailsWith<InterruptedException> {
                verifyOrClaimSnapshotNamespace(admin.client, NAMESPACE, FINGERPRINT, Duration.ofSeconds(2))
            }

            (thrown === interruption).shouldBeTrue()
            Thread.currentThread().isInterrupted.shouldBeTrue()
            admin.cancelCalls.shouldBeFalse()
            admin.destroyCount shouldBeEqualTo 0
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `marker key uses the exact map namespace as its Redis Cluster hash tag`() {
        val markerKey = snapshotNamespaceMarkerKey(NAMESPACE)

        markerKey shouldBeEqualTo "{$NAMESPACE}:__bt4k_snapshot_fingerprint"
        markerKey.substringAfter('{').substringBefore('}') shouldBeEqualTo NAMESPACE
    }

    @Test
    fun `atomic marker verification rejects mismatch and legacy map before cache access`() {
        listOf(
            markerState(MARKER_MISMATCH, mapExists = false),
            markerState(MARKER_ABSENT, mapExists = true),
        ).forEach { remoteState ->
            val admin = RecordingAdmin().apply { scriptReturns(remoteState) }

            assertFailsWith<IllegalStateException> {
                verifyOrClaimSnapshotNamespace(admin.client, NAMESPACE, FINGERPRINT, Duration.ofSeconds(2))
            }

            admin.events shouldBeEqualTo listOf("script", "wait:script-1")
            admin.events.contains("map-access").shouldBeFalse()
        }
    }

    @Test
    fun `clear deletes the map before local state and marker then verifies absence`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapReturns(true, label = "map-unlink")
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            scriptReturns(1L)
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.COMPLETED,
            mapAbsent = true,
            markerPresent = false,
        )
        admin.events shouldBeEqualTo listOf(
            "script", "wait:script-1",
            "map-access", "map-unlink", "wait:map-unlink",
            "local-clear", "wait:local-clear",
            "script", "wait:script-2",
            "script", "wait:script-3",
            "script", "wait:script-4",
            "destroy",
        )
        admin.destroyCount shouldBeEqualTo 1
        admin.cancelCalls.shouldBeFalse()
    }

    @Test
    fun `admin local map disables expiration event subscription`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        admin.localCachedMapOptions.single().option("getExpirationEventPolicy") shouldBeEqualTo
                LocalCachedMapOptions.ExpirationEventPolicy.DONT_SUBSCRIBE
    }

    @Test
    fun `already absent remote state still clears caller local view and verifies terminal absence`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT) shouldBeEqualTo
                SnapshotNamespaceCleanupResult(
                    SnapshotNamespaceCleanupOutcome.ALREADY_COMPLETE,
                    mapAbsent = true,
                    markerPresent = false,
                )

        admin.events shouldBeEqualTo listOf(
            "script", "wait:script-1",
            "map-access", "local-clear", "wait:local-clear",
            "script", "wait:script-2",
            "destroy",
        )
    }

    @Test
    fun `already absent remote state reports accepted unknown when caller local clear times out`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
            mapNeverCompletes(label = "local-clear")
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN,
            mapAbsent = true,
            markerPresent = false,
            exceptionType = TimeoutException::class.java.name,
        )
        admin.cancelCalls.shouldBeFalse()
    }

    @Test
    fun `already absent remote state remains unknown when terminal absence verification times out`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptNeverCompletes()
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN,
            mapAbsent = true,
            markerPresent = false,
            exceptionType = TimeoutException::class.java.name,
        )
        admin.cancelCalls.shouldBeFalse()
    }

    @Test
    fun `clear fails closed when the marker is absent but the map exists`() {
        val admin = RecordingAdmin().apply { scriptReturns(markerState(MARKER_ABSENT, mapExists = true)) }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
        result.mapAbsent.shouldBeFalse()
        result.markerPresent.shouldBeFalse()
        result.exceptionType shouldBeEqualTo IllegalStateException::class.java.name
        admin.events shouldBeEqualTo listOf("script", "wait:script-1")
    }

    @Test
    fun `clear resumes marker deletion when the map is absent and marker remains`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            scriptReturns(1L)
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.COMPLETED
        admin.events.contains("map-unlink").shouldBeFalse()
        admin.events.indexOf("local-clear").let { localIndex ->
            (localIndex >= 0 && localIndex < admin.events.indexOfLast { it == "script" }).shouldBeTrue()
        }
    }

    @Test
    fun `rollback cleanup removes map and local view but retains and revalidates marker`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapReturns(true, label = "map-unlink")
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
        }

        val result = clearMapRetainingMarker(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.MARKER_RETAINED,
            mapAbsent = true,
            markerPresent = true,
        )
        admin.events shouldBeEqualTo listOf(
            "script", "wait:script-1",
            "map-access", "map-unlink", "wait:map-unlink",
            "local-clear", "wait:local-clear",
            "script", "wait:script-2",
            "destroy",
        )
        admin.destroyCount shouldBeEqualTo 1
    }

    @Test
    fun `rollback cleanup retains an exact marker when the map is already absent`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
        }

        val result = clearMapRetainingMarker(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.MARKER_RETAINED,
            mapAbsent = true,
            markerPresent = true,
        )
        admin.events.contains("map-unlink").shouldBeFalse()
    }

    @Test
    fun `rollback cleanup refuses every state with an absent marker`() {
        listOf(false, true).forEach { mapExists ->
            val admin = RecordingAdmin().apply {
                scriptReturns(markerState(MARKER_ABSENT, mapExists = mapExists))
            }

            val result = clearMapRetainingMarker(admin.client, codec, NAMESPACE, FINGERPRINT)

            result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
            result.exceptionType shouldBeEqualTo IllegalStateException::class.java.name
            admin.events shouldBeEqualTo listOf("script", "wait:script-1")
        }
    }

    @Test
    fun `marker mismatch fails closed before cache access for both cleanup modes`() {
        listOf<(RecordingAdmin) -> SnapshotNamespaceCleanupResult>(
            { clearSnapshotNamespace(it.client, codec, NAMESPACE, FINGERPRINT) },
            { clearMapRetainingMarker(it.client, codec, NAMESPACE, FINGERPRINT) },
        ).forEach { operation ->
            val admin = RecordingAdmin().apply { scriptReturns(markerState(MARKER_MISMATCH, mapExists = true)) }

            val result = operation(admin)

            result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
            result.exceptionType shouldBeEqualTo IllegalStateException::class.java.name
            admin.events shouldBeEqualTo listOf("script", "wait:script-1")
        }
    }

    @Test
    fun `timeout before cleanup acceptance is failed but accepted unlink timeout is unknown and never cancelled`() {
        val verificationTimeout = RecordingAdmin().apply { scriptNeverCompletes() }
        val unlinkTimeout = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapNeverCompletes(label = "map-unlink")
        }

        val verification = clearSnapshotNamespace(
            verificationTimeout.client,
            codec,
            NAMESPACE,
            FINGERPRINT,
            Duration.ofSeconds(2),
        )
        val accepted = clearSnapshotNamespace(
            unlinkTimeout.client,
            codec,
            NAMESPACE,
            FINGERPRINT,
            Duration.ofSeconds(2),
        )

        verification.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
        verification.exceptionType shouldBeEqualTo TimeoutException::class.java.name
        accepted.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN
        accepted.mapAbsent.shouldBeFalse()
        accepted.markerPresent.shouldBeTrue()
        verificationTimeout.cancelCalls.shouldBeFalse()
        unlinkTimeout.cancelCalls.shouldBeFalse()
        verificationTimeout.destroyCount shouldBeEqualTo 0
        unlinkTimeout.destroyCount shouldBeEqualTo 1
        unlinkTimeout.events.last() shouldBeEqualTo "destroy"
    }

    @Test
    fun `temporary local map is destroyed after fail closed reinspection`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapReturns(true, label = "map-unlink")
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_MISMATCH, mapExists = false))
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
        admin.destroyCount shouldBeEqualTo 1
        admin.events.last() shouldBeEqualTo "destroy"
    }

    @Test
    fun `temporary local map is destroyed after synchronous Exception and Error`() {
        val exception = ConnectionFailure("map command failed")
        val exceptionAdmin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapThrows(exception, label = "map-unlink")
        }
        val error = FatalAdminError()
        val errorAdmin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapThrows(error, label = "map-unlink")
        }

        val failed = clearSnapshotNamespace(exceptionAdmin.client, codec, NAMESPACE, FINGERPRINT)
        val thrown = assertFailsWith<FatalAdminError> {
            clearSnapshotNamespace(errorAdmin.client, codec, NAMESPACE, FINGERPRINT)
        }

        failed.exceptionType shouldBeEqualTo exception.javaClass.name
        exceptionAdmin.destroyCount shouldBeEqualTo 1
        exceptionAdmin.events.last() shouldBeEqualTo "destroy"
        (thrown === error).shouldBeTrue()
        errorAdmin.destroyCount shouldBeEqualTo 1
        errorAdmin.events.last() shouldBeEqualTo "destroy"
    }

    @Test
    fun `accepted cleanup interruption remains primary when destroy also fails`() {
        val interruption = InterruptedException("local clear interrupted")
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapReturns(true, label = "map-unlink")
            mapInterrupts(interruption, label = "local-clear")
            destroyFails(interruption)
        }
        Thread.interrupted()

        try {
            val thrown = assertFailsWith<InterruptedException> {
                clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)
            }

            (thrown === interruption).shouldBeTrue()
            Thread.currentThread().isInterrupted.shouldBeTrue()
            admin.destroyCount shouldBeEqualTo 1
            admin.events.last() shouldBeEqualTo "destroy"
            admin.cancelCalls.shouldBeFalse()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `timeout after marker unlink acceptance reports resumable unknown state without cancellation`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            scriptNeverCompletes()
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN,
            mapAbsent = true,
            markerPresent = true,
            exceptionType = TimeoutException::class.java.name,
        )
        admin.cancelCalls.shouldBeFalse()
    }

    @Test
    fun `Redisson command timeout after cleanup acceptance is also an unknown accepted state`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapFails(RedisTimeoutException("endpoint and credential details must not escape"), label = "map-unlink")
        }

        val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

        result shouldBeEqualTo SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN,
            mapAbsent = false,
            markerPresent = true,
            exceptionType = RedisTimeoutException::class.java.name,
        )
        result.toString().shouldNotContain("endpoint")
        result.toString().shouldNotContain("credential")
    }

    @Test
    fun `one monotonic deadline supplies non-increasing remaining waits across every phase`() {
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = true))
            mapReturns(true, label = "map-unlink")
            mapReturns(null, label = "local-clear")
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            scriptReturns(1L)
            scriptReturns(markerState(MARKER_ABSENT, mapExists = false))
        }

        clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT, Duration.ofSeconds(2))

        admin.waitNanos.size shouldBeEqualTo 6
        admin.waitNanos.zipWithNext().all { (first, second) -> second <= first }.shouldBeTrue()
        admin.waitNanos.all { it > 0L }.shouldBeTrue()
    }

    @Test
    fun `ACL and connection failures return only structural exception type`() {
        listOf(AclFailure("redis://admin:secret@example"), ConnectionFailure("password=secret"))
            .forEach { failure ->
                val admin = RecordingAdmin().apply { scriptFails(failure) }

                val result = clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)

                result.outcome shouldBeEqualTo SnapshotNamespaceCleanupOutcome.FAILED
                result.exceptionType shouldBeEqualTo failure.javaClass.name
                result.toString().shouldNotContain("secret")
                result.toString().shouldNotContain("redis://")
            }
    }

    @Test
    fun `fatal asynchronous errors escape unchanged`() {
        val fatal = FatalAdminError()
        val admin = RecordingAdmin().apply { scriptFails(fatal) }

        val thrown = assertFailsWith<FatalAdminError> {
            clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)
        }

        (thrown === fatal).shouldBeTrue()
    }

    @Test
    fun `synchronous client Error escapes by identity and stops before cleanup submission`() {
        val fatal = FatalAdminError()
        val admin = RecordingAdmin().apply {
            scriptReturns(markerState(MARKER_EXACT, mapExists = false))
            mapAccessFails(fatal)
        }

        val thrown = assertFailsWith<FatalAdminError> {
            clearSnapshotNamespace(admin.client, codec, NAMESPACE, FINGERPRINT)
        }

        (thrown === fatal).shouldBeTrue()
        admin.events shouldBeEqualTo listOf("script", "wait:script-1", "map-access")
    }

    @Test
    fun `public result outcomes remain exhaustive and delicate calls require explicit opt in`() {
        SnapshotNamespaceCleanupOutcome.entries.map(::describe) shouldBeEqualTo listOf(
            "completed",
            "already-complete",
            "marker-retained",
            "timed-out-accepted-unknown",
            "failed",
        )
    }

    @Test
    fun `both public destructive functions retain the delicate admin annotation`() {
        val source = Files.readString(adminSource())

        listOf("clearSnapshotNamespace", "clearMapRetainingMarker").forEach { functionName ->
            Regex("""@DelicateSnapshotCacheAdminApi\s+fun\s+<ID\s*:\s*Any>\s+$functionName\s*\(""")
                .containsMatchIn(source)
                .shouldBeTrue()
        }
    }

    @Test
    fun `cleanup result has a stable serializable structural contract`() {
        val expected = SnapshotNamespaceCleanupResult(
            SnapshotNamespaceCleanupOutcome.FAILED,
            mapAbsent = false,
            markerPresent = true,
            exceptionType = AclFailure::class.java.name,
        )
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(expected) }
            output.toByteArray()
        }

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        restored shouldBeEqualTo expected
    }

    @Test
    fun `admin KDoc requires quiescence scoped ACL network isolation and no request-facing exposure`() {
        val source = Files.readString(adminSource())

        source.shouldContain("quiesce")
        source.shouldContain("dedicated namespace-scoped Redis ACL")
        source.shouldContain("marker and map inspection and unlink")
        source.shouldContain("local-cache clear scoped pub/sub")
        source.shouldContain("\${namespace}:clear:*")
        source.shouldContain("semaphore keys and channels")
        source.shouldContain("deny global keyevent subscription")
        source.shouldNotContain("limited to inspecting and unlinking")
        source.shouldContain("network isolation")
        source.shouldContain("must never be exposed through request-facing")
        source.shouldContain("accident guard, not authorization")
    }

    private fun describe(outcome: SnapshotNamespaceCleanupOutcome): String = when (outcome) {
        SnapshotNamespaceCleanupOutcome.COMPLETED -> "completed"
        SnapshotNamespaceCleanupOutcome.ALREADY_COMPLETE -> "already-complete"
        SnapshotNamespaceCleanupOutcome.MARKER_RETAINED -> "marker-retained"
        SnapshotNamespaceCleanupOutcome.TIMED_OUT_ACCEPTED_UNKNOWN -> "timed-out-accepted-unknown"
        SnapshotNamespaceCleanupOutcome.FAILED -> "failed"
    }

    private fun adminSource(): Path {
        val rootCandidate = Path.of(
            "exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdmin.kt",
        )
        if (Files.exists(rootCandidate)) return rootCandidate
        return Path.of("src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdmin.kt")
    }

    private fun Any.option(name: String): Any? = javaClass.getMethod(name).invoke(this)

    private companion object {
        const val NAMESPACE = "orders-snapshot:v1"
        const val FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val MARKER_ABSENT = 0L
        const val MARKER_EXACT = 1L
        const val MARKER_MISMATCH = 2L

        fun markerState(marker: Long, mapExists: Boolean): List<Long> = listOf(marker, if (mapExists) 1L else 0L)
    }
}

private sealed interface FutureBehavior {
    data class Value(val value: Any?) : FutureBehavior
    data class Failure(val failure: Throwable) : FutureBehavior
    data class Interrupted(val interruption: InterruptedException) : FutureBehavior
    data object Never : FutureBehavior
}

private sealed interface MapBehavior {
    val label: String

    data class Future(override val label: String, val behavior: FutureBehavior) : MapBehavior
    data class Throws(override val label: String, val failure: Throwable) : MapBehavior
}

private data class ScriptCall(val keys: List<Any>, val arguments: List<Any?>)

private class RecordingAdmin {
    val events = mutableListOf<String>()
    val waitNanos = mutableListOf<Long>()
    val scriptCalls = mutableListOf<ScriptCall>()
    val localCachedMapOptions = mutableListOf<LocalCachedMapOptions<*, *>>()
    var cancelCalls = false
        private set
    var destroyCount = 0
        private set

    private val scriptBehaviors = ArrayDeque<FutureBehavior>()
    private val mapBehaviors = ArrayDeque<MapBehavior>()
    private var scriptIndex = 0
    private var mapAccessFailure: Error? = null
    private var destroyFailure: Throwable? = null

    val client: RedissonClient = proxy { method, args ->
        when (method.name) {
            "getScript" -> {
                events += "script"
                script
            }
            "getLocalCachedMap" -> {
                events += "map-access"
                mapAccessFailure?.let { throw it }
                @Suppress("UNCHECKED_CAST")
                localCachedMapOptions += args.single() as LocalCachedMapOptions<*, *>
                map
            }
            else -> defaultValue(method.returnType)
        }
    }

    fun scriptReturns(value: Any?) {
        scriptBehaviors += FutureBehavior.Value(value)
    }

    fun scriptFails(failure: Throwable) {
        scriptBehaviors += FutureBehavior.Failure(failure)
    }

    fun scriptInterrupts(interruption: InterruptedException) {
        scriptBehaviors += FutureBehavior.Interrupted(interruption)
    }

    fun scriptNeverCompletes() {
        scriptBehaviors += FutureBehavior.Never
    }

    fun mapReturns(value: Any?, label: String) {
        mapBehaviors += MapBehavior.Future(label, FutureBehavior.Value(value))
    }

    fun mapNeverCompletes(label: String) {
        mapBehaviors += MapBehavior.Future(label, FutureBehavior.Never)
    }

    fun mapFails(failure: Throwable, label: String) {
        mapBehaviors += MapBehavior.Future(label, FutureBehavior.Failure(failure))
    }

    fun mapInterrupts(interruption: InterruptedException, label: String) {
        mapBehaviors += MapBehavior.Future(label, FutureBehavior.Interrupted(interruption))
    }

    fun mapThrows(failure: Throwable, label: String) {
        mapBehaviors += MapBehavior.Throws(label, failure)
    }

    fun mapAccessFails(failure: Error) {
        mapAccessFailure = failure
    }

    fun destroyFails(failure: Throwable) {
        destroyFailure = failure
    }

    private val script: RScript = proxy { method, args ->
        when (method.name) {
            "evalAsync" -> {
                scriptIndex += 1
                @Suppress("UNCHECKED_CAST")
                val keys = args.firstOrNull { it is List<*> } as? List<Any> ?: emptyList()
                val keyIndex = args.indexOfFirst { it === keys }
                val arguments = if (keyIndex >= 0) args.drop(keyIndex + 1).flatMap { value ->
                    if (value is Array<*>) value.toList() else listOf(value)
                } else emptyList()
                scriptCalls += ScriptCall(keys, arguments)
                future("script-$scriptIndex", scriptBehaviors.removeFirst())
            }
            else -> defaultValue(method.returnType)
        }
    }

    private val map: RLocalCachedMap<Long, Any?> = proxy { method, _ ->
        when (method.name) {
            "unlinkAsync", "clearLocalCacheAsync" -> {
                val behavior = mapBehaviors.removeFirst()
                events += behavior.label
                when (behavior) {
                    is MapBehavior.Future -> future(behavior.label, behavior.behavior)
                    is MapBehavior.Throws -> throw behavior.failure
                }
            }
            "destroy" -> {
                events += "destroy"
                destroyCount += 1
                destroyFailure?.let { throw it }
            }
            else -> defaultValue(method.returnType)
        }
    }

    private fun future(label: String, behavior: FutureBehavior): RFuture<Any?> = proxy { method, args ->
        when (method.name) {
            "get" -> {
                if (args.size != 2) throw AssertionError("Unbounded Future.get() is forbidden.")
                val timeout = args[0] as Long
                val unit = args[1] as TimeUnit
                events += "wait:$label"
                waitNanos += unit.toNanos(timeout)
                when (behavior) {
                    is FutureBehavior.Value -> behavior.value
                    is FutureBehavior.Failure -> throw ExecutionException(behavior.failure)
                    is FutureBehavior.Interrupted -> throw behavior.interruption
                    FutureBehavior.Never -> throw TimeoutException("never completes")
                }
            }
            "cancel" -> {
                cancelCalls = true
                false
            }
            "isDone" -> behavior !is FutureBehavior.Never
            "isCancelled" -> false
            "toCompletableFuture" -> when (behavior) {
                is FutureBehavior.Value -> CompletableFuture.completedFuture(behavior.value)
                is FutureBehavior.Failure -> CompletableFuture.failedFuture(behavior.failure)
                is FutureBehavior.Interrupted -> CompletableFuture.failedFuture(behavior.interruption)
                FutureBehavior.Never -> CompletableFuture<Any?>()
            }
            else -> defaultValue(method.returnType)
        }
    }
}

private inline fun <reified T : Any> proxy(
    crossinline invocation: (Method, List<Any?>) -> Any?,
): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
    InvocationHandler { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "${T::class.simpleName}Proxy"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            else -> invocation(method, arguments?.toList().orEmpty())
        }
    },
) as T

private fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0F
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}

private class AclFailure(message: String) : RuntimeException(message)
private class ConnectionFailure(message: String) : RuntimeException(message)
private class FatalAdminError : Error()
