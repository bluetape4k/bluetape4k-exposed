@file:OptIn(io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.MeasuredInvalidation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheApplyReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperation
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOperationResult
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheOutcome
import io.bluetape4k.exposed.cache.snapshot.SnapshotStoreId
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.StringCodec
import java.io.Serializable
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.BiConsumer
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions

class JdbcRedissonSnapshotInvalidatorTest {

    @Test
    fun `factory verifies the canonical remote marker before map access`() {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
            .thenReturnMarker(markerState(MARKER_ABSENT, mapExists = false))
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = config()
        val expectedFingerprint = snapshotNamespaceFingerprint(
            backend = "redisson-jdbc",
            namespace = config.snapshot.namespace,
            keyRawClass = Long::class.java,
            snapshotRawClass = Payload::class.java,
            schemaVersion = config.snapshot.schemaVersion,
            codec = codec,
            synchronizationStrategy = config.synchronizationStrategy,
        )

        val invalidator = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            config,
        )

        invalidator.compatibilityFingerprint shouldBeEqualTo expectedFingerprint
        client.events shouldBeEqualTo listOf("script-access", "marker-eval", "marker-wait", "map-access")
        client.scriptCalls.single().keys shouldBeEqualTo listOf(
            snapshotNamespaceMarkerKey(config.snapshot.namespace),
            config.snapshot.namespace,
        )
        client.scriptCalls.single().arguments shouldBeEqualTo listOf(expectedFingerprint)
    }

    @Test
    fun `marker mismatch and legacy map fail before map access without reservation residue`() {
        listOf(
            markerState(MARKER_MISMATCH, mapExists = false),
            markerState(MARKER_ABSENT, mapExists = true),
        ).forEach { rejectedState ->
            val map = RecordingLocalMap<Long>()
            val client = RecordingRedissonClient(map.proxy)
                .thenReturnMarker(rejectedState)
                .thenReturnMarker(markerState(MARKER_EXACT, mapExists = false))
            val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
            val rejectedConfig = config(maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8)

            assertFailsWith<IllegalStateException> {
                jdbcRedissonSnapshotInvalidator(
                    client.proxy,
                    codec,
                    Long::class,
                    Payload::class,
                    rejectedConfig,
                    snapshotCacheFailureBuffer(1),
                )
            }

            client.options shouldBeEqualTo emptyList()
            client.events.contains("map-access").shouldBeFalse()

            val retryConfig = rejectedConfig.copy(
                nearCacheMaximumSize = rejectedConfig.nearCacheMaximumSize + 1,
                maxOutstandingChunks = 2,
                maxOutstandingEncodedBytes = 16,
            )
            val retry = jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                Payload::class,
                retryConfig,
                snapshotCacheFailureBuffer(2),
            )

            retry.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 0, 16, 0, 0, false)
            client.options.size shouldBeEqualTo 1
        }
    }

    @Test
    fun `marker timeout and connection failure fail closed before map access and unwrap the exact cause`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val map = RecordingLocalMap<Long>()

        val timedOut = RecordingRedissonClient(map.proxy).thenNeverCompleteMarker()
        assertFailsWith<TimeoutException> {
            jdbcRedissonSnapshotInvalidator(timedOut.proxy, codec, Long::class, Payload::class, config())
        }
        timedOut.options shouldBeEqualTo emptyList()
        timedOut.markerCancelCalled.shouldBeFalse()

        val connectionFailure = MarkerConnectionFailure()
        val disconnected = RecordingRedissonClient(map.proxy).thenFailMarker(connectionFailure)
        val thrown = assertFailsWith<MarkerConnectionFailure> {
            jdbcRedissonSnapshotInvalidator(disconnected.proxy, codec, Long::class, Payload::class, config())
        }
        (thrown === connectionFailure).shouldBeTrue()
        disconnected.options shouldBeEqualTo emptyList()

        val recovered = RecordingRedissonClient(map.proxy)
            .thenReturnMarker(markerState(MARKER_EXACT, mapExists = false))
        jdbcRedissonSnapshotInvalidator(
            recovered.proxy,
            codec,
            Long::class,
            Payload::class,
            config(maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 16),
        ).quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 0, 16, 0, 0, false)
    }

    @Test
    fun `marker verification preserves interruption and fatal Error control semantics`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val map = RecordingLocalMap<Long>()
        val interruption = InterruptedException("marker verification interrupted")
        val interrupted = RecordingRedissonClient(map.proxy).thenInterruptMarker(interruption)
        Thread.interrupted()

        try {
            val thrown = assertFailsWith<InterruptedException> {
                jdbcRedissonSnapshotInvalidator(interrupted.proxy, codec, Long::class, Payload::class, config())
            }
            (thrown === interruption).shouldBeTrue()
            Thread.currentThread().isInterrupted.shouldBeTrue()
            interrupted.options shouldBeEqualTo emptyList()
            interrupted.markerCancelCalled.shouldBeFalse()
        } finally {
            Thread.interrupted()
        }

        val asynchronousFatal = FatalMarkerError()
        val asynchronous = RecordingRedissonClient(map.proxy).thenFailMarker(asynchronousFatal)
        val asyncThrown = assertFailsWith<FatalMarkerError> {
            jdbcRedissonSnapshotInvalidator(asynchronous.proxy, codec, Long::class, Payload::class, config())
        }
        (asyncThrown === asynchronousFatal).shouldBeTrue()
        asynchronous.options shouldBeEqualTo emptyList()

        val directFatal = FatalMarkerError()
        val direct = RecordingRedissonClient(map.proxy).failScriptAccess(directFatal)
        val directThrown = assertFailsWith<FatalMarkerError> {
            jdbcRedissonSnapshotInvalidator(direct.proxy, codec, Long::class, Payload::class, config())
        }
        (directThrown === directFatal).shouldBeTrue()
        direct.options shouldBeEqualTo emptyList()
    }

    @Test
    fun `repository configuration and invalidator share exact codec namespace and caller contracts`() {
        val namespace = "orders-contract:v1"
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val repositoryConfig = RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE.copy(
            name = namespace,
            codec = codec,
        )
        val invalidatorConfig = config().copy(
            snapshot = SnapshotCacheConfig(namespace, "orders-v3", 32, 4),
        )
        val failureBuffer = snapshotCacheFailureBuffer(4)
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)

        val invalidator = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            invalidatorConfig,
            failureBuffer,
        )

        repositoryConfig.name shouldBeEqualTo invalidator.storeId.namespace
        (repositoryConfig.codec === codec).shouldBeTrue()
        (invalidator.failureBuffer === failureBuffer).shouldBeTrue()
        client.options.single().option("getName") shouldBeEqualTo repositoryConfig.name
        (client.options.single().option("getCodec") === repositoryConfig.codec).shouldBeTrue()
        invalidator.compatibilityFingerprint shouldBeEqualTo snapshotNamespaceFingerprint(
            backend = "redisson-jdbc",
            namespace = namespace,
            keyRawClass = Long::class.java,
            snapshotRawClass = Payload::class.java,
            schemaVersion = "orders-v3",
            codec = codec,
            synchronizationStrategy = invalidatorConfig.synchronizationStrategy,
        )
    }

    @Test
    fun `explicit and reified factories preserve caller identities tokens fingerprint and map options`() {
        val firstMap = RecordingLocalMap<Long>()
        val secondMap = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(firstMap.proxy)
            .thenReturnMap(firstMap.proxy)
            .thenReturnMap(secondMap.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = config(maxOutstandingChunks = 4, maxOutstandingEncodedBytes = 128)
        val failureBuffer = snapshotCacheFailureBuffer(4)

        val explicit = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            config,
            failureBuffer,
        )
        val reified = jdbcRedissonSnapshotInvalidator<Long, Payload>(
            client.proxy,
            codec,
            config,
            failureBuffer,
        )

        explicit.storeId shouldBeEqualTo SnapshotStoreId("redisson-jdbc", "orders:v1")
        (explicit.failureBuffer === failureBuffer).shouldBeTrue()
        (reified.failureBuffer === failureBuffer).shouldBeTrue()
        explicit.compatibilityFingerprint shouldBeEqualTo snapshotNamespaceFingerprint(
            backend = "redisson-jdbc",
            namespace = "orders:v1",
            keyRawClass = Long::class.java,
            snapshotRawClass = Payload::class.java,
            schemaVersion = "orders-v3",
            codec = codec,
            synchronizationStrategy = LocalCachedMapOptions.SyncStrategy.INVALIDATE,
        )
        reified.compatibilityFingerprint shouldBeEqualTo explicit.compatibilityFingerprint
        (reified.storeInstanceToken === explicit.storeInstanceToken).shouldBeTrue()
        explicit.limits.maxStagedWeight shouldBeEqualTo config.maxCommitEncodedKeyBytes.toLong()
        client.options.size shouldBeEqualTo 2
        client.options.forEach { options ->
            options.option("getName") shouldBeEqualTo "orders:v1"
            (options.option("getCodec") === codec).shouldBeTrue()
            options.option("getCacheSize") shouldBeEqualTo config.nearCacheMaximumSize
            options.option("getSyncStrategy") shouldBeEqualTo config.synchronizationStrategy
            options.option("getReconnectionStrategy") shouldBeEqualTo config.reconnectionStrategy
        }
    }

    @Test
    fun `same client namespace rejects every local composition mismatch before map access`() {
        val baseCodec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val baseConfig = config()
        val baseBuffer = snapshotCacheFailureBuffer(4)

        fun assertRejected(
            first: (RedissonClient) -> Unit,
            mismatch: (RedissonClient) -> Unit,
        ) {
            val client = RecordingRedissonClient(RecordingLocalMap<Long>().proxy)
            first(client.proxy)
            client.options.size shouldBeEqualTo 1

            assertFailsWith<IllegalArgumentException> { mismatch(client.proxy) }

            client.options.size shouldBeEqualTo 1
        }

        assertRejected(
            first = { jdbcRedissonSnapshotInvalidator(it, baseCodec, Long::class, Payload::class, baseConfig, baseBuffer) },
            mismatch = {
                jdbcRedissonSnapshotInvalidator(
                    it,
                    snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
                    Long::class,
                    Payload::class,
                    baseConfig,
                    baseBuffer,
                )
            },
        )
        assertRejected(
            first = { jdbcRedissonSnapshotInvalidator(it, baseCodec, Long::class, Payload::class, baseConfig, baseBuffer) },
            mismatch = {
                jdbcRedissonSnapshotInvalidator(
                    it,
                    snapshotRedissonCodec(StringCodec(), "json-v1", uuidSnapshotIdentifierPolicy()),
                    UUID::class,
                    Payload::class,
                    baseConfig,
                    baseBuffer,
                )
            },
        )
        assertRejected(
            first = { jdbcRedissonSnapshotInvalidator(it, baseCodec, Long::class, Payload::class, baseConfig, baseBuffer) },
            mismatch = {
                jdbcRedissonSnapshotInvalidator(
                    it,
                    baseCodec,
                    Long::class,
                    AlternatePayload::class,
                    baseConfig,
                    baseBuffer,
                )
            },
        )
        assertRejected(
            first = { jdbcRedissonSnapshotInvalidator(it, baseCodec, Long::class, Payload::class, baseConfig, baseBuffer) },
            mismatch = {
                jdbcRedissonSnapshotInvalidator(
                    it,
                    baseCodec,
                    Long::class,
                    Payload::class,
                    baseConfig.copy(nearCacheMaximumSize = baseConfig.nearCacheMaximumSize + 1),
                    baseBuffer,
                )
            },
        )
        assertRejected(
            first = { jdbcRedissonSnapshotInvalidator(it, baseCodec, Long::class, Payload::class, baseConfig, baseBuffer) },
            mismatch = {
                jdbcRedissonSnapshotInvalidator(
                    it,
                    baseCodec,
                    Long::class,
                    Payload::class,
                    baseConfig,
                    snapshotCacheFailureBuffer(4),
                )
            },
        )
    }

    @Test
    fun `same client allows a different namespace with client quota limits pinned`() {
        val firstMap = RecordingLocalMap<Long>()
        val secondMap = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(firstMap.proxy)
            .thenReturnMap(firstMap.proxy)
            .thenReturnMap(secondMap.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val buffer = snapshotCacheFailureBuffer(4)
        val firstConfig = config(maxOutstandingChunks = 4, maxOutstandingEncodedBytes = 128)
        val secondConfig = firstConfig.copy(snapshot = firstConfig.snapshot.copy(namespace = "customers:v1"))

        val first = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            firstConfig,
            buffer,
        )
        val second = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            secondConfig,
            buffer,
        )

        (first.storeInstanceToken === second.storeInstanceToken).shouldBeFalse()
        client.options.map { it.option("getName") } shouldBeEqualTo listOf("orders:v1", "customers:v1")
        first.quotaHealth() shouldBeEqualTo second.quotaHealth()
    }

    @Test
    fun `failed first map construction rolls back descriptor and quota reservation`() {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
            .thenThrowMap(MapConstructionFailure())
            .thenReturnMap(map.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val failedConfig = config(maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8)
        val retryConfig = failedConfig.copy(
            nearCacheMaximumSize = failedConfig.nearCacheMaximumSize + 1,
            maxOutstandingChunks = 2,
            maxOutstandingEncodedBytes = 16,
        )

        assertFailsWith<MapConstructionFailure> {
            jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                Payload::class,
                failedConfig,
                snapshotCacheFailureBuffer(4),
            )
        }
        val retry = jdbcRedissonSnapshotInvalidator(
            client.proxy,
            codec,
            Long::class,
            Payload::class,
            retryConfig,
        )

        retry.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 0, 16, 0, 0, false)
        client.options.size shouldBeEqualTo 2
    }

    @Test
    fun `quota mismatch and invalid runtime tokens fail before Redisson map access`() {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val valid = config(maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8)

        jdbcRedissonSnapshotInvalidator(client.proxy, codec, Long::class, Payload::class, valid)
        client.options.size shouldBeEqualTo 1

        assertFailsWith<IllegalArgumentException> {
            jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                Payload::class,
                valid.copy(maxOutstandingChunks = 2),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val wrongIdCodec = codec as SnapshotRedissonCodec<UUID>
        assertFailsWith<IllegalArgumentException> {
            jdbcRedissonSnapshotInvalidator(
                client.proxy,
                wrongIdCodec,
                UUID::class,
                Payload::class,
                valid,
            )
        }
        @Suppress("UNCHECKED_CAST")
        val nonSerializableType = NonSerializable::class as KClass<Payload>
        assertFailsWith<IllegalArgumentException> {
            jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                nonSerializableType,
                valid,
            )
        }

        client.options.size shouldBeEqualTo 1
    }

    @Test
    fun `measure captures exact canonical bytes hash and enforces key cap immediately`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val valid = newInvalidator(codec, config(maxEncodedKeyBytes = 8))
        val measured = valid.measure(Long.MIN_VALUE)
        val expected = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Long.MIN_VALUE).array()

        measured.encodedBytes shouldBeEqualTo expected.size
        measured.encodedSha256 shouldBeEqualTo sha256(expected)
        valid.limits.maxStagedWeight shouldBeEqualTo valid.configForTest.maxCommitEncodedKeyBytes.toLong()

        val undersized = newInvalidator(codec, config(maxEncodedKeyBytes = 7))
        assertFailsWith<IllegalArgumentException> { undersized.measure(1L) }
    }

    @Test
    fun `submission chunks in order reencodes every id and issues invalidation only`() {
        val map = RecordingLocalMap<Long>()
        repeat(3) { map.thenReturn(completedRFuture(1L)) }
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 16, maxOutstandingChunks = 3),
            map = map,
        )
        val measured = (1L..5L).map(invalidator::measure)

        val report = invalidator.submitInvalidation(measured).completedReport()

        map.submittedIds shouldBeEqualTo listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L))
        map.invokedMethods shouldBeEqualTo listOf("fastRemoveAsync", "fastRemoveAsync", "fastRemoveAsync")
        report shouldBeEqualTo SnapshotCacheApplyReport(
            listOf(
                successResult(2),
                successResult(2),
                successResult(1),
            ),
        )
        invalidator.quotaHealth().outstandingChunks shouldBeEqualTo 0
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `reencode mismatch fails only its chunk and later chunks still submit`() {
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val map = RecordingLocalMap<Long>().apply { thenReturn(completedRFuture(1L)) }
        var tamper = false
        val invalidator = newInvalidator(
            codec = codec,
            config = config(maxBatchEncodedKeyBytes = 8),
            map = map,
            identifierEncoder = { id ->
                codec.encodeSnapshotIdentifier(id).also { bytes ->
                    if (tamper && id == 1L) bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
                }
            },
        )
        val measured = listOf(invalidator.measure(1L), invalidator.measure(2L))
        tamper = true

        val report = invalidator.submitInvalidation(measured).completedReport()

        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.FAILED,
            SnapshotCacheOutcome.SUCCESS,
        )
        map.submittedIds shouldBeEqualTo listOf(listOf(2L))
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `synchronous submission failure releases its lease and later chunks continue`() {
        val map = RecordingLocalMap<Long>()
            .thenThrow(SubmissionFailure())
            .thenReturn(completedRFuture(1L))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val report = invalidator.submitInvalidation(listOf(invalidator.measure(1L), invalidator.measure(2L)))
            .completedReport()

        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.FAILED,
            SnapshotCacheOutcome.SUCCESS,
        )
        map.submittedIds shouldBeEqualTo listOf(listOf(1L), listOf(2L))
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `synchronous submission error escapes unchanged releases lease and stops later chunks`() {
        val fatal = FatalSubmissionError()
        val map = RecordingLocalMap<Long>()
            .thenThrow(fatal)
            .thenReturn(completedRFuture(1L))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val thrown = assertFailsWith<FatalSubmissionError> {
            invalidator.submitInvalidation(listOf(invalidator.measure(1L), invalidator.measure(2L)))
        }

        (thrown === fatal).shouldBeTrue()
        map.submittedIds shouldBeEqualTo listOf(listOf(1L))
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `synchronous callback registration error escapes unchanged and releases lease`() {
        val fatal = FatalSubmissionError()
        val map = RecordingLocalMap<Long>().thenReturn(throwingWhenCompleteRFuture(fatal))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val thrown = assertFailsWith<FatalSubmissionError> {
            invalidator.submitInvalidation(listOf(invalidator.measure(1L)))
        }

        (thrown === fatal).shouldBeTrue()
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `exceptional and duplicate completion release quota exactly once without direct failure recording`() {
        val exceptional = CompletableFuture<Long>().apply { completeExceptionally(SubmissionFailure()) }
        val duplicate = CompletableFuture.completedFuture(1L)
        val map = RecordingLocalMap<Long>()
            .thenReturn(rFuture(exceptional))
            .thenReturn(rFuture(duplicate, duplicateCallback = true))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val report = invalidator.submitInvalidation(listOf(invalidator.measure(1L), invalidator.measure(2L)))
            .completedReport()

        report.results.map { it.outcome } shouldBeEqualTo listOf(
            SnapshotCacheOutcome.FAILED,
            SnapshotCacheOutcome.SUCCESS,
        )
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `asynchronous error remains exceptional without direct failure recording`() {
        val fatal = FatalSubmissionError()
        val exceptional = CompletableFuture<Long>().apply { completeExceptionally(fatal) }
        val map = RecordingLocalMap<Long>().thenReturn(rFuture(exceptional))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val completion = invalidator.submitInvalidation(listOf(invalidator.measure(1L)))
        val thrown = assertFailsWith<ExecutionException> {
            completion.toCompletableFuture().get(5, TimeUnit.SECONDS)
        }

        (thrown.cause === fatal).shouldBeTrue()
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 0, 8, 0, 0, false)
        invalidator.failureBuffer.size shouldBeEqualTo 0
    }

    @Test
    fun `never completing future retains one bounded lease and rejects later chunks`() {
        val never = CompletableFuture<Long>()
        val map = RecordingLocalMap<Long>().thenReturn(rFuture(never))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val completion = invalidator.submitInvalidation(listOf(invalidator.measure(1L), invalidator.measure(2L)))

        completion.toCompletableFuture().isDone.shouldBeFalse()
        map.submittedIds shouldBeEqualTo listOf(listOf(1L))
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 1, 8, 8, 1, true)
    }

    @Test
    fun `never completing future retains no measured invalidation payload`() {
        val never = CompletableFuture<Long>()
        val map = RecordingLocalMap<Long>(retainSubmittedIds = false).thenReturn(rFuture(never))
        val invalidator = newInvalidator(
            codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy()),
            config = config(maxBatchEncodedKeyBytes = 8, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8),
            map = map,
        )

        val probe = submitRetentionProbe(invalidator)

        probe.completion.toCompletableFuture().isDone.shouldBeFalse()
        invalidator.quotaHealth() shouldBeEqualTo SnapshotInvalidationQuotaHealth(1, 1, 8, 8, 0, true)
        await().atMost(Duration.ofSeconds(5)).until {
            System.gc()
            probe.batch.get() == null && probe.measured.get() == null
        }
    }

    @Test
    fun `public facade has internal constructors exact factories and no read or put surface`() {
        JdbcRedissonSnapshotInvalidator::class.constructors
            .all { it.visibility == KVisibility.INTERNAL }
            .shouldBeTrue()
        val declaredFunctions = JdbcRedissonSnapshotInvalidator::class.declaredMemberFunctions.map { it.name }
        declaredFunctions.none { it.contains("lookup", ignoreCase = true) || it.contains("put", ignoreCase = true) }
            .shouldBeTrue()

        val factories = Class.forName(
            "io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotInvalidatorKt",
        ).declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.name == "jdbcRedissonSnapshotInvalidator"
        }
        factories.map { it.parameterCount }.sorted() shouldBeEqualTo listOf(4, 6)
        Class.forName("io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotInvalidatorKt")
            .declaredMethods.count { Modifier.isPublic(it.modifiers) && it.name == "quotaHealth" }
            .shouldBeEqualTo(1)
    }

    private fun newInvalidator(
        codec: SnapshotRedissonCodec<Long>,
        config: JdbcRedissonSnapshotInvalidatorConfig,
        map: RecordingLocalMap<Long> = RecordingLocalMap(),
        identifierEncoder: (Any) -> ByteArray = codec::encodeSnapshotIdentifier,
    ): TestInvalidator {
        val failureBuffer = snapshotCacheFailureBuffer(8)
        val quota = RedissonInvalidationQuotaRegistry().quotaFor(
            RecordingRedissonClient(map.proxy).proxy,
            config.maxOutstandingChunks,
            config.maxOutstandingEncodedBytes,
        )
        val invalidator = JdbcRedissonSnapshotInvalidator(
            localCacheMap = map.proxy,
            codec = codec,
            idType = Long::class,
            valueType = Payload::class,
            config = config,
            quota = quota,
            failureBuffer = failureBuffer,
            storeInstanceToken = Any(),
            identifierEncoder = identifierEncoder,
        )
        return TestInvalidator(invalidator, config)
    }

    private fun submitRetentionProbe(invalidator: TestInvalidator): RetentionProbe {
        val measured = invalidator.measure(9_223_372_036_854_775_000L)
        val batch = listOf(measured)
        return RetentionProbe(
            batch = WeakReference(batch),
            measured = WeakReference(measured),
            completion = invalidator.submitInvalidation(batch),
        )
    }

    private data class RetentionProbe(
        val batch: WeakReference<List<MeasuredInvalidation<Long>>>,
        val measured: WeakReference<MeasuredInvalidation<Long>>,
        val completion: CompletionStage<SnapshotCacheApplyReport>,
    )

    private fun config(
        maxEncodedKeyBytes: Int = 8,
        maxBatchEncodedKeyBytes: Int = 64,
        maxCommitEncodedKeyBytes: Int = 256,
        maxOutstandingChunks: Int = 8,
        maxOutstandingEncodedBytes: Long = 512,
    ) = JdbcRedissonSnapshotInvalidatorConfig(
        snapshot = SnapshotCacheConfig("orders:v1", "orders-v3", 32, 4),
        nearCacheMaximumSize = 128,
        maxEncodedKeyBytes = maxEncodedKeyBytes,
        maxBatchEncodedKeyBytes = maxBatchEncodedKeyBytes,
        maxCommitEncodedKeyBytes = maxCommitEncodedKeyBytes,
        maxOutstandingChunks = maxOutstandingChunks,
        maxOutstandingEncodedBytes = maxOutstandingEncodedBytes,
    )

    private fun CompletionStage<SnapshotCacheApplyReport>.completedReport(): SnapshotCacheApplyReport =
        toCompletableFuture().get(5, TimeUnit.SECONDS)

    private fun successResult(count: Int) = SnapshotCacheOperationResult(
        SnapshotCacheOperation.INVALIDATE,
        SnapshotCacheOutcome.SUCCESS,
        count,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun Any.option(name: String): Any? = javaClass.getMethod(name).invoke(this)

    private data class TestInvalidator(
        val delegate: JdbcRedissonSnapshotInvalidator<Long>,
        val configForTest: JdbcRedissonSnapshotInvalidatorConfig,
    ) {
        fun measure(id: Long): MeasuredInvalidation<Long> = delegate.measure(id)
        fun submitInvalidation(batch: List<MeasuredInvalidation<Long>>): CompletionStage<SnapshotCacheApplyReport> =
            delegate.submitInvalidation(batch)

        val limits get() = delegate.limits
        val failureBuffer get() = delegate.failureBuffer
        fun quotaHealth() = delegate.quotaHealth()
    }

    private class RecordingRedissonClient(private val localCacheMap: RLocalCachedMap<*, *>) {
        private val mapBehaviors = ArrayDeque<() -> RLocalCachedMap<*, *>>()
        private val markerBehaviors = ArrayDeque<MarkerBehavior>()
        private var scriptAccessFailure: Error? = null
        val events = mutableListOf<String>()
        val scriptCalls = mutableListOf<MarkerScriptCall>()
        val options = mutableListOf<Any>()
        var markerCancelCalled = false
            private set
        val proxy: RedissonClient = Proxy.newProxyInstance(
            RedissonClient::class.java.classLoader,
            arrayOf(RedissonClient::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getScript" -> {
                    events += "script-access"
                    scriptAccessFailure?.let { throw it }
                    script
                }
                "getLocalCachedMap" -> {
                    events += "map-access"
                    options += args.orEmpty().single()
                    if (mapBehaviors.isEmpty()) localCacheMap else mapBehaviors.removeFirst()()
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingRedissonClient"
                else -> error("Unexpected RedissonClient call: ${method.name}")
            }
        } as RedissonClient

        private val script: RScript = Proxy.newProxyInstance(
            RScript::class.java.classLoader,
            arrayOf(RScript::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "evalAsync" -> {
                    events += "marker-eval"
                    @Suppress("UNCHECKED_CAST")
                    val keys = args.orEmpty().firstOrNull { it is List<*> } as? List<Any> ?: emptyList()
                    val keyIndex = args.orEmpty().indexOfFirst { it === keys }
                    val arguments = if (keyIndex >= 0) args.orEmpty().drop(keyIndex + 1).flatMap { value ->
                        if (value is Array<*>) value.toList() else listOf(value)
                    } else {
                        emptyList()
                    }
                    scriptCalls += MarkerScriptCall(keys, arguments)
                    markerFuture(
                        if (markerBehaviors.isEmpty()) {
                            MarkerBehavior.Value(markerState(MARKER_EXACT, mapExists = false))
                        } else {
                            markerBehaviors.removeFirst()
                        },
                    )
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingMarkerScript"
                else -> error("Unexpected RScript call: ${method.name}")
            }
        } as RScript

        @Suppress("UNCHECKED_CAST")
        private fun markerFuture(behavior: MarkerBehavior): RFuture<List<Long>> = Proxy.newProxyInstance(
            RFuture::class.java.classLoader,
            arrayOf(RFuture::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "get" -> {
                    if (args.orEmpty().size != 2) error("Unbounded marker Future.get() is forbidden.")
                    events += "marker-wait"
                    when (behavior) {
                        is MarkerBehavior.Value -> behavior.value
                        is MarkerBehavior.Failure -> throw ExecutionException(behavior.failure)
                        is MarkerBehavior.Interrupted -> throw behavior.interruption
                        MarkerBehavior.Never -> throw TimeoutException("marker verification timed out")
                    }
                }
                "cancel" -> {
                    markerCancelCalled = true
                    false
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingMarkerFuture"
                else -> method.invoke(CompletableFuture<List<Long>>(), *args.orEmpty())
            }
        } as RFuture<List<Long>>

        fun thenReturnMap(map: RLocalCachedMap<*, *>): RecordingRedissonClient = apply {
            mapBehaviors += { map }
        }

        fun thenThrowMap(exception: RuntimeException): RecordingRedissonClient = apply {
            mapBehaviors += { throw exception }
        }

        fun thenReturnMarker(value: List<Long>): RecordingRedissonClient = apply {
            markerBehaviors += MarkerBehavior.Value(value)
        }

        fun thenFailMarker(failure: Throwable): RecordingRedissonClient = apply {
            markerBehaviors += MarkerBehavior.Failure(failure)
        }

        fun thenInterruptMarker(interruption: InterruptedException): RecordingRedissonClient = apply {
            markerBehaviors += MarkerBehavior.Interrupted(interruption)
        }

        fun thenNeverCompleteMarker(): RecordingRedissonClient = apply {
            markerBehaviors += MarkerBehavior.Never
        }

        fun failScriptAccess(failure: Error): RecordingRedissonClient = apply {
            scriptAccessFailure = failure
        }
    }

    private class RecordingLocalMap<ID : Any>(
        private val retainSubmittedIds: Boolean = true,
    ) {
        private val behaviors = ArrayDeque<() -> RFuture<Long>>()
        val submittedIds = mutableListOf<List<ID>>()
        val invokedMethods = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val proxy: RLocalCachedMap<ID, Any?> = Proxy.newProxyInstance(
            RLocalCachedMap::class.java.classLoader,
            arrayOf(RLocalCachedMap::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "fastRemoveAsync" -> {
                    invokedMethods += method.name
                    if (retainSubmittedIds) {
                        submittedIds += (args.orEmpty().single() as Array<*>).map { it as ID }
                    }
                    check(behaviors.isNotEmpty()) { "No submission behavior configured" }
                    behaviors.removeFirst()()
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingLocalMap"
                else -> error("Non-invalidation Redisson map command invoked: ${method.name}")
            }
        } as RLocalCachedMap<ID, Any?>

        fun thenReturn(future: RFuture<Long>): RecordingLocalMap<ID> = apply {
            behaviors += { future }
        }

        fun thenThrow(failure: Throwable): RecordingLocalMap<ID> = apply {
            behaviors += { throw failure }
        }
    }

    private fun completedRFuture(value: Long): RFuture<Long> = rFuture(CompletableFuture.completedFuture(value))

    @Suppress("UNCHECKED_CAST")
    private fun throwingWhenCompleteRFuture(failure: Error): RFuture<Long> = Proxy.newProxyInstance(
        RFuture::class.java.classLoader,
        arrayOf(RFuture::class.java),
    ) { instance, method, args ->
        when (method.name) {
            "whenComplete" -> throw failure
            "equals" -> instance === args.orEmpty().singleOrNull()
            "hashCode" -> System.identityHashCode(instance)
            "toString" -> "ThrowingWhenCompleteRFuture"
            else -> method.invoke(CompletableFuture<Long>(), *args.orEmpty())
        }
    } as RFuture<Long>

    @Suppress("UNCHECKED_CAST")
    private fun rFuture(
        delegate: CompletableFuture<Long>,
        duplicateCallback: Boolean = false,
    ): RFuture<Long> {
        lateinit var proxy: RFuture<Long>
        proxy = Proxy.newProxyInstance(
            RFuture::class.java.classLoader,
            arrayOf(RFuture::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "whenComplete" -> {
                    val callback = args.orEmpty().single() as BiConsumer<Long?, Throwable?>
                    delegate.whenComplete { value, throwable ->
                        callback.accept(value, throwable)
                        if (duplicateCallback) callback.accept(value, throwable)
                    }
                    proxy
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RFutureProxy"
                else -> method.invoke(delegate, *args.orEmpty())
            }
        } as RFuture<Long>
        return proxy
    }

    private data class Payload(val value: String = "value") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class AlternatePayload(val value: String = "value") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class NonSerializable

    private class SubmissionFailure : RuntimeException()

    private class FatalSubmissionError : Error()

    private class MapConstructionFailure : RuntimeException()

    private class MarkerConnectionFailure : RuntimeException()

    private class FatalMarkerError : Error()

    private sealed interface MarkerBehavior {
        data class Value(val value: List<Long>) : MarkerBehavior
        data class Failure(val failure: Throwable) : MarkerBehavior
        data class Interrupted(val interruption: InterruptedException) : MarkerBehavior
        data object Never : MarkerBehavior
    }

    private data class MarkerScriptCall(
        val keys: List<Any>,
        val arguments: List<Any?>,
    )

    private companion object {
        const val MARKER_ABSENT = 0L
        const val MARKER_EXACT = 1L
        const val MARKER_MISMATCH = 2L

        fun markerState(marker: Long, mapExists: Boolean): List<Long> =
            listOf(marker, if (mapExists) 1L else 0L)
    }
}
