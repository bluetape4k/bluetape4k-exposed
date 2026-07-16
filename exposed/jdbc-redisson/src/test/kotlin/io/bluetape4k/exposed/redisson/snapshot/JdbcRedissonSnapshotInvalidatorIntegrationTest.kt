@file:OptIn(
    DelicateSnapshotCacheAdminApi::class,
    io.bluetape4k.exposed.cache.snapshot.InternalSnapshotCacheApi::class,
)

package io.bluetape4k.exposed.redisson.snapshot

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.snapshotCacheFailureBuffer
import io.bluetape4k.redis.redisson.redissonClientOf
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.api.listener.BaseStatusListener
import org.redisson.api.options.LocalCachedMapOptions
import org.redisson.client.codec.StringCodec
import org.redisson.config.Config
import org.redisson.config.ConstantDelay
import org.redisson.connection.ConnectionListener
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JdbcRedissonSnapshotInvalidatorIntegrationTest {

    private val ownedClients = mutableListOf<RedissonClient>()
    private val clientPolicies = mutableListOf<ClientPolicy>()
    private val namespaces = linkedSetOf<String>()
    private val ownedProxies = mutableListOf<OwnedProxy>()
    private var redisPaused = false
    private val redis: RedisServer get() = RedisServer.Launcher.redis

    @AfterEach
    fun cleanIntegrationResources() {
        val cleanupFailures = mutableListOf<String>()
        cleanupStep("restore Redis", cleanupFailures) { restoreRedis() }
        ownedProxies.asReversed().forEach { owned ->
            cleanupStep("restore proxy", cleanupFailures) {
                if (!owned.proxy.isEnabled) owned.proxy.enable()
            }
        }
        ownedClients.asReversed().forEach { client ->
            cleanupStep("close Redisson client", cleanupFailures) {
                if (!client.isShutdown) client.shutdown()
            }
        }
        ownedProxies.asReversed().forEach { owned ->
            cleanupStep("delete proxy", cleanupFailures) { owned.proxy.delete() }
        }
        if (namespaces.isNotEmpty()) {
            cleanupStep("remove Redis snapshot residue", cleanupFailures) {
                val cleanupClient = createClient(recordPolicy = false, own = false)
                try {
                    val keys = namespaces.flatMap { namespace ->
                        listOf(namespace, snapshotNamespaceMarkerKey(namespace))
                    }.toTypedArray()
                    cleanupClient.keys.delete(*keys)
                    cleanupClient.keys.countExists(*keys).shouldBeEqualTo(0)
                } finally {
                    cleanupClient.shutdown()
                }
            }
        }
        ownedClients.clear()
        ownedProxies.clear()
        clientPolicies.clear()
        namespaces.clear()
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("Integration cleanup failed: ${cleanupFailures.joinToString()}")
        }
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
        mapA[20L] = "commit-barrier"
        mapB[20L].shouldBeEqualTo("commit-barrier")
        transaction(database) {
            maxAttempts = 1
            stageInvalidation(invalidator, 20L)
        }
        awaitCondition("post-rollback committed barrier namespace=$namespace key=20") { mapB[20L] == null }
        mapB[2L].shouldBeEqualTo("rollback")
    }

    @Test
    fun `factory options are bounded invalidate and clear and peer reconnect clears stale local state on first hit`() {
        val reconnectEvents = ReconnectEvents()
        val ownedProxy = newRedisProxy()
        val clientA = newClient()
        val clientB = newClient(address = ownedProxy.redisUrl, connectionListener = reconnectEvents)
        val namespace = namespace("reconnect")
        val codec = codec()
        val config = config(namespace)
        jdbcRedissonSnapshotInvalidator(clientA, codec, Long::class, String::class, config)
        val mapA = localMap(clientA, codec, config)
        val invalidationChannel = "{$namespace}:topic"
        val subscriptionEvents = ReconnectSubscriptions(invalidationChannel)
        clientB.getTopic(invalidationChannel, StringCodec()).addListener(subscriptionEvents)
        subscriptionEvents.awaitInitialSubscription()
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
        mapB.cachedKeySet().contains(3L).shouldBeTrue()

        ownedProxy.proxy.disable()
        try {
            clientB.isShutdown.shouldBeFalse()
            reconnectEvents.awaitDisconnect()
            mapA.fastRemove(3L).shouldBeEqualTo(1)
            redisAvailable(clientA).shouldBeTrue()
        } finally {
            ownedProxy.proxy.enable()
        }
        reconnectEvents.awaitReconnect()
        subscriptionEvents.awaitResubscription()
        awaitPubSubBarrier(clientB, namespace)
        awaitCondition("peer B local-cache CLEAR namespace=$namespace key=3") {
            !mapB.cachedKeySet().contains(3L)
        }
        (reconnectEvents.disconnectCount.get() > 0).shouldBeTrue()
        (reconnectEvents.reconnectCount.get() > 0).shouldBeTrue()
        mapB[3L].shouldBeEqualTo(null)
    }

    @Test
    fun `recovery drains completed quota replaces client identity and rejects old or expired clients`() {
        val oldClient = newClient()
        val namespace = namespace("recovery")
        val codec = codec()
        val buffer = snapshotCacheFailureBuffer(4)
        val old = jdbcRedissonSnapshotInvalidator(oldClient, codec, Long::class, String::class, config(namespace), buffer)
        val database = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        pauseRedis()
        try {
            transaction(database) {
                maxAttempts = 1
                stageInvalidation(old, 9L)
            }
            old.quotaHealth().outstandingChunks.shouldBeEqualTo(1)
            oldClient.shutdown(0, 1, TimeUnit.SECONDS)
        } finally {
            restoreRedis()
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

        pauseRedis()
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
            restoreRedis()
        }
        awaitCondition("Redis service restoration after expiry proof") { redisAvailable(replacementClient) }
    }

    @Test
    fun `versioned configuration performs the guarded rollout and rollback operations`() {
        val adminClient = newClient()
        val rolloutV1Client = newClient()
        val v2Client = newClient()
        val v1Namespace = namespace("migration")
        val base = v1Namespace.removeSuffix(":v1")
        val v1 = config(v1Namespace)
        val v2 = config(trackNamespace("$base:v2"))
        val codec = codec()
        val trace = mutableListOf<String>()
        val rolloutV1 = jdbcRedissonSnapshotInvalidator(rolloutV1Client, codec, Long::class, String::class, v1)
        val v2Invalidator = jdbcRedissonSnapshotInvalidator(v2Client, codec, Long::class, String::class, v2)
        val rolloutV1Map = localMap(rolloutV1Client, codec, v1)
        val v2Map = localMap(v2Client, codec, v2)
        val database = Database.connect("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction(database) {
            exec("CREATE TABLE snapshot_source (id BIGINT PRIMARY KEY, payload VARCHAR(64) NOT NULL)")
            exec("INSERT INTO snapshot_source (id, payload) VALUES (1, 'database-v1')")
        }
        rolloutV1Map[1L] = "v1"
        v2Map[1L] = "v2"
        trace += "v2-deployed"

        rolloutV1.quotaHealth().outstandingChunks.shouldBeEqualTo(0)
        shutdownAppClient(rolloutV1Client)
        trace += "v1-writers-stopped"
        clearSnapshotNamespace(adminClient, codec, v1.snapshot.namespace, rolloutV1.compatibilityFingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.COMPLETED)
        trace += "v1-rollout-cleaned"
        verifyOrClaimSnapshotNamespace(
            adminClient, v2.snapshot.namespace, v2Invalidator.compatibilityFingerprint, Duration.ofSeconds(2),
        ).shouldBeEqualTo(SnapshotNamespaceMarkerVerification.MATCHED)

        val rollbackV1Client = newClient()
        val rollbackV1 = jdbcRedissonSnapshotInvalidator(rollbackV1Client, codec, Long::class, String::class, v1)
        val rollbackV1Map = localMap(rollbackV1Client, codec, v1)
        rollbackV1Map[1L] = "rebuild-required"
        rollbackV1.quotaHealth().outstandingChunks.shouldBeEqualTo(0)
        v2Invalidator.quotaHealth().outstandingChunks.shouldBeEqualTo(0)
        shutdownAppClient(rollbackV1Client)
        trace += "v1-old-readers-stopped"
        shutdownAppClient(v2Client)
        trace += "v2-writers-stopped"
        clearMapRetainingMarker(
            adminClient, codec, v1.snapshot.namespace, rollbackV1.compatibilityFingerprint,
        ).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.MARKER_RETAINED)
        trace += "v1-local-cleared-marker-retained"

        val freshV1Client = newClient()
        val freshV1 = jdbcRedissonSnapshotInvalidator(freshV1Client, codec, Long::class, String::class, v1)
        freshV1.compatibilityFingerprint.shouldBeEqualTo(rollbackV1.compatibilityFingerprint)
        val freshV1Map = localMap(freshV1Client, codec, v1)
        freshV1Map[1L].shouldBeEqualTo(null)
        trace += "v1-fresh-client-empty"
        val rebuilt = transaction(database) {
            exec("SELECT payload FROM snapshot_source WHERE id = 1") { result ->
                result.next()
                result.getString(1)
            }
        }
        rebuilt.shouldBeEqualTo("database-v1")
        freshV1Map[1L] = rebuilt
        freshV1Map[1L].shouldBeEqualTo("database-v1")
        trace += "v1-rebuilt-from-database"
        freshV1.quotaHealth().outstandingChunks.shouldBeEqualTo(0)
        shutdownAppClient(freshV1Client)
        trace += "v1-rebuild-client-stopped"
        clearSnapshotNamespace(adminClient, codec, v2.snapshot.namespace, v2Invalidator.compatibilityFingerprint).outcome
            .shouldBeEqualTo(SnapshotNamespaceCleanupOutcome.COMPLETED)
        trace += "v2-rollback-cleaned"

        trace.shouldBeEqualTo(
            listOf(
                "v2-deployed",
                "v1-writers-stopped",
                "v1-rollout-cleaned",
                "v1-old-readers-stopped",
                "v2-writers-stopped",
                "v1-local-cleared-marker-retained",
                "v1-fresh-client-empty",
                "v1-rebuilt-from-database",
                "v1-rebuild-client-stopped",
                "v2-rollback-cleaned",
            ),
        )

        assertFailsWith<IllegalArgumentException> { config(base) }
    }

    private fun newClient(
        address: String = redis.url,
        connectionListener: ConnectionListener? = null,
    ): RedissonClient = createClient(address, connectionListener, recordPolicy = true, own = true)

    private fun createClient(
        address: String = redis.url,
        connectionListener: ConnectionListener? = null,
        recordPolicy: Boolean,
        own: Boolean,
    ): RedissonClient {
        val redissonConfig = Config()
        connectionListener?.let(redissonConfig::setConnectionListener)
        val server = redissonConfig.useSingleServer()
            .setAddress(address)
            .setTimeout(2_000)
            .setConnectTimeout(2_000)
            .setPingConnectionInterval(500)
            .setRetryAttempts(1)
            .setRetryDelay(ConstantDelay(Duration.ofMillis(250)))
            .setConnectionPoolSize(8)
            .setConnectionMinimumIdleSize(1)
        if (recordPolicy) {
            clientPolicies += ClientPolicy(
                commandTimeout = server.timeout,
                connectTimeout = server.connectTimeout,
                retryAttempts = server.retryAttempts,
                retryDelay = server.retryDelay.calcDelay(1),
            )
        }
        return redissonClientOf(redissonConfig).also { client -> if (own) ownedClients += client }
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
        var lastExceptionType: String? = null
        while (System.nanoTime() - started < timeoutNanos) {
            attempts++
            try {
                if (condition()) return
            } catch (exception: Exception) {
                lastExceptionType = exception.javaClass.name
            }
            Thread.sleep(20)
        }
        error(
            "Timed out after ${timeout.toMillis()}ms waiting for $description; " +
                    "attempts=$attempts; lastExceptionType=${lastExceptionType ?: "none"}",
        )
    }

    private fun namespace(prefix: String): String =
        trackNamespace("$prefix-${UUID.randomUUID().toString().take(8)}:v1")

    private fun trackNamespace(namespace: String): String = namespace.also(namespaces::add)

    private fun redisAvailable(client: RedissonClient): Boolean {
        client.getBucket<String>("snapshot-integration-health", StringCodec()).isExists
        return true
    }

    private fun pauseRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(redis.containerId).exec()
        redisPaused = true
    }

    private fun restoreRedis() {
        if (redisPaused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(redis.containerId).exec()
            redisPaused = false
        }
    }

    private fun newRedisProxy(): OwnedProxy {
        Testcontainers.exposeHostPorts(redis.port)
        val server = ToxiproxyServer.Launcher.toxiproxy
        val proxy = ToxiproxyClient(server.host, server.port).createProxy(
            "redis-${UUID.randomUUID()}",
            "0.0.0.0:$PROXY_PORT",
            "host.testcontainers.internal:${redis.port}",
        )
        return OwnedProxy(proxy, "redis://${server.host}:${server.getMappedPort(PROXY_PORT)}")
            .also(ownedProxies::add)
    }

    private fun shutdownAppClient(client: RedissonClient) {
        client.shutdown()
        client.isShutdown.shouldBeTrue()
    }

    private fun awaitPubSubBarrier(client: RedissonClient, namespace: String) {
        val subscribed = CountDownLatch(1)
        val topic = client.getTopic("$namespace:reconnect-barrier", StringCodec())
        val listenerId = topic.addListener(object: BaseStatusListener() {
            override fun onSubscribe(channel: String) {
                subscribed.countDown()
            }
        })
        try {
            subscribed.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            topic.removeListener(listenerId)
        }
    }

    private fun cleanupStep(description: String, failures: MutableList<String>, action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            failures += "$description (${exception.javaClass.simpleName})"
        }
    }

    private class RollbackSignal : RuntimeException()

    private class ClientPolicy(
        val commandTimeout: Int,
        val connectTimeout: Int,
        val retryAttempts: Int,
        val retryDelay: Duration,
    )

    private class OwnedProxy(val proxy: Proxy, val redisUrl: String)

    @Suppress("OVERRIDE_DEPRECATION")
    private class ReconnectEvents: ConnectionListener {
        private val disconnected = CountDownLatch(1)
        private val reconnected = CountDownLatch(1)
        private val disconnectObserved = AtomicBoolean()
        val disconnectCount = AtomicInteger()
        val reconnectCount = AtomicInteger()

        override fun onConnect(addr: InetSocketAddress) {
            if (disconnectObserved.get()) {
                reconnectCount.incrementAndGet()
                reconnected.countDown()
            }
        }

        override fun onDisconnect(addr: InetSocketAddress) {
            disconnectObserved.set(true)
            disconnectCount.incrementAndGet()
            disconnected.countDown()
        }

        fun awaitDisconnect() {
            disconnected.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }

        fun awaitReconnect() {
            reconnected.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }
    }

    private class ReconnectSubscriptions(private val expectedChannel: String): BaseStatusListener() {
        private val initialSubscription = CountDownLatch(1)
        private val resubscription = CountDownLatch(1)
        private val subscriptionCount = AtomicInteger()

        override fun onSubscribe(channel: String) {
            channel.shouldBeEqualTo(expectedChannel)
            when (subscriptionCount.incrementAndGet()) {
                1 -> initialSubscription.countDown()
                2 -> resubscription.countDown()
            }
        }

        fun awaitInitialSubscription() {
            initialSubscription.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }

        fun awaitResubscription() {
            resubscription.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }
    }

    private companion object {
        const val PROXY_PORT = 8666
    }

}
