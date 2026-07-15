@file:OptIn(
    DelicateSnapshotCacheAdminApi::class,
    io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class,
)

package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.redis.redisson.redissonClientOf
import io.bluetape4k.testcontainers.storage.RedisServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.api.redisnode.RedisNodes
import org.redisson.client.codec.StringCodec
import org.redisson.config.Config
import org.redisson.config.ConstantDelay
import org.testcontainers.DockerClientFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class JdbcRedissonSnapshotInvalidatorIntegrationTest {

    private val ownedClients = mutableListOf<RedissonClient>()
    private val clientPolicies = mutableListOf<ClientPolicy>()
    private val redis: RedisServer get() = RedisServer.Launcher.redis

    @AfterEach
    fun closeOwnedClients() {
        ownedClients.asReversed().forEach { client -> if (!client.isShutdown) client.shutdown() }
        ownedClients.clear()
        clientPolicies.clear()
    }

    @Test
    fun `real marker claim persists matches and guards incompatible cleanup states`() {
        val first = newClient()
        val second = newClient()
        val namespace = namespace("marker")
        val fingerprint = "a".repeat(64)
        val codec = codec()

        verifyOrClaimSnapshotNamespace(first, namespace, fingerprint, Duration.ofSeconds(2))
            .shouldBeEqualTo(SnapshotNamespaceMarkerVerification.CLAIMED)
        verifyOrClaimSnapshotNamespace(second, namespace, fingerprint, Duration.ofSeconds(2))
            .shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)
        assertFailsWith<IllegalStateException> {
            verifyOrClaimSnapshotNamespace(second, namespace, "b".repeat(64), Duration.ofSeconds(2))
        }

        val map = localMap(first, codec, config(namespace))
        map[1L] = "value"
        clearMapRetainingMarker(second, codec, namespace, fingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.MARKER_RETAINED)
        verifyOrClaimSnapshotNamespace(first, namespace, fingerprint, Duration.ofSeconds(2))
            .shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)
        clearSnapshotNamespace(first, codec, namespace, fingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.COMPLETED)
    }

    @Test
    fun `commit invalidates a peer near cache while rollback emits no peer invalidation`() {
        val clientA = newClient()
        val clientB = newClient()
        val namespace = namespace("peer")
        val codec = codec()
        val config = config(namespace)
        val invalidator = jdbcRedissonSnapshotInvalidator(clientA, codec, Long::class, String::class, config)
        val mapA = localMap(clientA, codec, config)
        val mapB = localMap(clientB, codec, config)
        val database = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        mapA[1L] = "stale"
        mapB[1L].shouldBeEqualTo("stale")
        transaction(database) {
            maxAttempts = 1
            stageInvalidation(invalidator, 1L)
        }
        awaitCondition("peer invalidation namespace=$namespace key=1") { mapB[1L] == null }

        mapA[2L] = "rollback"
        mapB[2L].shouldBeEqualTo("rollback")
        assertFailsWith<RollbackSignal> {
            transaction(database) {
                maxAttempts = 1
                stageInvalidation(invalidator, 2L)
                throw RollbackSignal()
            }
        }
        Thread.sleep(150)
        mapB[2L].shouldBeEqualTo("rollback")
    }

    @Test
    fun `factory options are bounded invalidate and clear and shared outage reconnect clears stale local state`() {
        val clientA = newClient()
        val clientB = newClient()
        val namespace = namespace("reconnect")
        val codec = codec()
        val config = config(namespace)
        jdbcRedissonSnapshotInvalidator(clientA, codec, Long::class, String::class, config)
        val mapA = localMap(clientA, codec, config)
        val mapB = localMap(clientB, codec, config)

        (config.nearCacheMaximumSize > 0).shouldBeTrue()
        config.synchronizationStrategy.shouldBeEqualTo(LocalCachedMapOptions.SyncStrategy.INVALIDATE)
        config.reconnectionStrategy.shouldBeEqualTo(LocalCachedMapOptions.ReconnectionStrategy.CLEAR)
        clientPolicies.all { policy ->
            policy.commandTimeout in 1..5_000 &&
                    policy.connectTimeout in 1..5_000 &&
                    policy.retryAttempts >= 0 &&
                    policy.retryDelay <= Duration.ofSeconds(5)
        }.shouldBeTrue()
        mapA[3L] = "stale"
        mapB[3L].shouldBeEqualTo("stale")
        redis.execInContainer("redis-cli", "DEL", namespace).apply {
            exitCode.shouldBeEqualTo(0)
            stdout.trim().shouldBeEqualTo("1")
        }
        mapB[3L].shouldBeEqualTo("stale")

        val docker = DockerClientFactory.instance().client()
        docker.pauseContainerCmd(redis.containerId).exec()
        try {
            clientB.isShutdown.shouldBeFalse()
            awaitCondition("client B disconnect observation") { redisDisconnected(clientB) }
            assertFailsWith<Exception> {
                clientB.getBucket<String>("force-client-b-disconnect", StringCodec()).get()
            }
        } finally {
            docker.unpauseContainerCmd(redis.containerId).exec()
        }
        awaitCondition("Redis service restoration after container unpause") { redisAvailable(clientA) }
        awaitCondition("CLEAR before next local hit namespace=$namespace key=3") { mapB[3L] == null }
    }

    @Test
    fun `recovery drains completed quota replaces client identity and rejects old or expired clients`() {
        val oldClient = newClient()
        val namespace = namespace("recovery")
        val codec = codec()
        val buffer = snapshotCacheFailureBuffer(4)
        val old = jdbcRedissonSnapshotInvalidator(oldClient, codec, Long::class, String::class, config(namespace), buffer)
        val database = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val docker = DockerClientFactory.instance().client()
        docker.pauseContainerCmd(redis.containerId).exec()
        try {
            transaction(database) {
                maxAttempts = 1
                stageInvalidation(old, 9L)
            }
            old.quotaHealth().outstandingChunks.shouldBeEqualTo(1)
            oldClient.shutdown(0, 1, TimeUnit.SECONDS)
        } finally {
            docker.unpauseContainerCmd(redis.containerId).exec()
        }
        awaitCondition("old completion quota drain and failure publication") {
            old.quotaHealth().outstandingChunks == 0 && buffer.size == 1
        }
        buffer.drainTo(observer = { }).apply {
            deliveredCount.shouldBeEqualTo(1)
            remainingCount.shouldBeEqualTo(0)
        }
        oldClient.isShutdown.shouldBeTrue()
        assertFailsWith<Exception> {
            jdbcRedissonSnapshotInvalidator(oldClient, codec, Long::class, String::class, config(namespace))
        }

        val replacementClient = newClient()
        val replacement = jdbcRedissonSnapshotInvalidator(
            replacementClient,
            codec,
            Long::class,
            String::class,
            config(namespace).copy(maxOutstandingChunks = 2),
        )
        replacement.quotaHealth().maxOutstandingChunks.shouldBeEqualTo(2)

        docker.pauseContainerCmd(redis.containerId).exec()
        try {
            assertFailsWith<Exception> {
                jdbcRedissonSnapshotInvalidator(
                    replacementClient,
                    codec,
                    Long::class,
                    String::class,
                    config(namespace("expired")).copy(namespaceVerificationTimeout = Duration.ofMillis(20)),
                )
            }
        } finally {
            docker.unpauseContainerCmd(redis.containerId).exec()
        }
        awaitCondition("Redis service restoration after expiry proof") { redisAvailable(replacementClient) }
    }

    @Test
    fun `versioned configuration performs the guarded rollout and rollback operations`() {
        val client = newClient()
        val base = "migration-${UUID.randomUUID().toString().take(8)}"
        val v1 = config("$base:v1")
        val v2 = config("$base:v2")
        val codec = codec()
        val v1Invalidator = jdbcRedissonSnapshotInvalidator(client, codec, Long::class, String::class, v1)
        val v2Invalidator = jdbcRedissonSnapshotInvalidator(client, codec, Long::class, String::class, v2)
        val v1Map = localMap(client, codec, v1)
        val v2Map = localMap(client, codec, v2)
        val database = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction(database) {
            exec("CREATE TABLE snapshot_source (id BIGINT PRIMARY KEY, payload VARCHAR(64) NOT NULL)")
            exec("INSERT INTO snapshot_source (id, payload) VALUES (1, 'database-v1')")
        }
        v1Map[1L] = "v1"
        v2Map[1L] = "v2"

        clearSnapshotNamespace(client, codec, v1.snapshot.namespace, v1Invalidator.compatibilityFingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.COMPLETED)
        verifyOrClaimSnapshotNamespace(
            client, v2.snapshot.namespace, v2Invalidator.compatibilityFingerprint, Duration.ofSeconds(2),
        ).shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)

        verifyOrClaimSnapshotNamespace(
            client, v1.snapshot.namespace, v1Invalidator.compatibilityFingerprint, Duration.ofSeconds(2),
        ).shouldBeEqualTo(SnapshotNamespaceMarkerVerification.CLAIMED)
        v1Map[1L] = "rebuild-required"
        clearMapRetainingMarker(client, codec, v1.snapshot.namespace, v1Invalidator.compatibilityFingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.MARKER_RETAINED)
        v1Map[1L].shouldBeEqualTo(null)
        verifyOrClaimSnapshotNamespace(
            client, v1.snapshot.namespace, v1Invalidator.compatibilityFingerprint, Duration.ofSeconds(2),
        ).shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)
        val rebuilt = transaction(database) {
            exec("SELECT payload FROM snapshot_source WHERE id = 1") { result ->
                result.next()
                result.getString(1)
            }
        }
        rebuilt.shouldBeEqualTo("database-v1")
        v1Map[1L] = rebuilt
        v1Map[1L].shouldBeEqualTo("database-v1")
        clearSnapshotNamespace(client, codec, v2.snapshot.namespace, v2Invalidator.compatibilityFingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.COMPLETED)

        assertFailsWith<IllegalArgumentException> { config(base) }
    }

    private fun newClient(): RedissonClient {
        val redissonConfig = Config()
        val server = redissonConfig.useSingleServer()
            .setAddress(redis.url)
            .setTimeout(2_000)
            .setConnectTimeout(2_000)
            .setPingConnectionInterval(500)
            .setRetryAttempts(1)
            .setRetryDelay(ConstantDelay(Duration.ofMillis(250)))
            .setConnectionPoolSize(8)
            .setConnectionMinimumIdleSize(1)
        clientPolicies += ClientPolicy(
            commandTimeout = server.timeout,
            connectTimeout = server.connectTimeout,
            retryAttempts = server.retryAttempts,
            retryDelay = server.retryDelay.calcDelay(1),
        )
        return redissonClientOf(redissonConfig).also(ownedClients::add)
    }

    private fun config(namespace: String) = JdbcRedissonSnapshotInvalidatorConfig(
        snapshot = SnapshotCacheConfig(namespace, "payload-v1", 32, 8),
        nearCacheMaximumSize = 16,
        maxEncodedKeyBytes = Long.SIZE_BYTES,
        maxBatchEncodedKeyBytes = 64,
        maxCommitEncodedKeyBytes = 256,
        maxOutstandingChunks = 1,
        maxOutstandingEncodedBytes = 64,
    )

    private fun codec() = snapshotRedissonCodec(StringCodec(), "string-v1", longSnapshotIdentifierPolicy())

    private fun localMap(
        client: RedissonClient,
        codec: SnapshotRedissonCodec<Long>,
        config: JdbcRedissonSnapshotInvalidatorConfig,
    ): RLocalCachedMap<Long, String> = client.getLocalCachedMap(
        LocalCachedMapOptions.name<Long, String>(config.snapshot.namespace).apply {
            codec(codec)
            cacheSize(config.nearCacheMaximumSize)
            syncStrategy(config.synchronizationStrategy)
            reconnectionStrategy(config.reconnectionStrategy)
        },
    )

    private fun awaitCondition(description: String, timeout: Duration = Duration.ofSeconds(5), condition: () -> Boolean) {
        val started = System.nanoTime()
        val timeoutNanos = timeout.toNanos()
        var attempts = 0
        while (System.nanoTime() - started < timeoutNanos) {
            attempts++
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(20)
        }
        error("Timed out after ${timeout.toMillis()}ms waiting for $description; attempts=$attempts")
    }

    private fun namespace(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}:v1"

    private fun redisAvailable(client: RedissonClient): Boolean {
        client.getBucket<String>("snapshot-integration-health", StringCodec()).isExists
        return true
    }

    private fun redisDisconnected(client: RedissonClient): Boolean = runCatching {
        !client.getRedisNodes(RedisNodes.SINGLE).pingAll(200, TimeUnit.MILLISECONDS)
    }.getOrDefault(true)

    private class RollbackSignal : RuntimeException()

    private class ClientPolicy(
        val commandTimeout: Int,
        val connectTimeout: Int,
        val retryAttempts: Int,
        val retryDelay: Duration,
    )

}
