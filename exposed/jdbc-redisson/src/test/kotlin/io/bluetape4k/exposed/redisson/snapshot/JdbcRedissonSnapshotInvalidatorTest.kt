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
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions

class JdbcRedissonSnapshotInvalidatorTest {

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
        val options = mutableListOf<Any>()
        val proxy: RedissonClient = Proxy.newProxyInstance(
            RedissonClient::class.java.classLoader,
            arrayOf(RedissonClient::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getLocalCachedMap" -> {
                    options += args.orEmpty().single()
                    if (mapBehaviors.isEmpty()) localCacheMap else mapBehaviors.removeFirst()()
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingRedissonClient"
                else -> error("Unexpected RedissonClient call: ${method.name}")
            }
        } as RedissonClient

        fun thenReturnMap(map: RLocalCachedMap<*, *>): RecordingRedissonClient = apply {
            mapBehaviors += { map }
        }

        fun thenThrowMap(exception: RuntimeException): RecordingRedissonClient = apply {
            mapBehaviors += { throw exception }
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

        fun thenThrow(exception: RuntimeException): RecordingLocalMap<ID> = apply {
            behaviors += { throw exception }
        }
    }

    private fun completedRFuture(value: Long): RFuture<Long> = rFuture(CompletableFuture.completedFuture(value))

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

    private class MapConstructionFailure : RuntimeException()
}
