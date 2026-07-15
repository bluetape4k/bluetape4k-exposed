package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import java.lang.ref.Reference
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue

class RedissonInvalidationQuotaRegistryTest {

    @Test
    fun `first valid client lookup pins positive quota limits`() {
        val registry = RedissonInvalidationQuotaRegistry()
        val client = equalToEveryClientProxy()

        assertFailsWith<IllegalArgumentException> {
            registry.quotaFor(client, maxOutstandingChunks = 0, maxOutstandingEncodedBytes = 32)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.quotaFor(client, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 0)
        }

        val first = registry.quotaFor(client, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 32)
        val matching = registry.quotaFor(client, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 32)

        (matching === first).shouldBeTrue()
        assertFailsWith<IllegalArgumentException> {
            registry.quotaFor(client, maxOutstandingChunks = 3, maxOutstandingEncodedBytes = 32)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.quotaFor(client, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 33)
        }
        first.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(
            maxOutstandingChunks = 2,
            outstandingChunks = 0,
            maxOutstandingEncodedBytes = 32,
            outstandingEncodedBytes = 0,
            rejectedChunks = 0,
            saturated = false,
        )
    }

    @Test
    fun `equals clients with distinct identities own separate quotas`() {
        val registry = RedissonInvalidationQuotaRegistry()
        val firstClient = equalToEveryClientProxy()
        val secondClient = equalToEveryClientProxy()

        (firstClient == secondClient).shouldBeTrue()
        val first = registry.quotaFor(firstClient, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8)
        val second = registry.quotaFor(secondClient, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 16)

        (first === second).shouldBeFalse()
        first.tryAdmit(encodedBytes = 8).shouldNotBeNull()
        second.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 0, 16, 0, 0, false)
    }

    @Test
    fun `chunk and actual byte admission is atomic and rejected work receives no lease`() {
        val quota = RedissonInvalidationQuotaRegistry().quotaFor(
            equalToEveryClientProxy(),
            maxOutstandingChunks = 4,
            maxOutstandingEncodedBytes = 4,
        )
        val leases = ConcurrentLinkedQueue<RedissonInvalidationQuotaLease>()

        MultithreadingTester()
            .workers(32)
            .rounds(1)
            .addAll(List(32) { { quota.tryAdmit(encodedBytes = 1)?.let(leases::add) } })
            .run()

        leases.size shouldBeEqualTo 4
        quota.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(4, 4, 4, 4, 28, true)

        leases.forEach {
            it.release()
            it.release()
        }
        quota.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(4, 0, 4, 0, 28, false)
    }

    @Test
    fun `byte rejection and a never released lease remain bounded`() {
        val quota = RedissonInvalidationQuotaRegistry().quotaFor(
            equalToEveryClientProxy(),
            maxOutstandingChunks = 2,
            maxOutstandingEncodedBytes = 5,
        )

        val retainedLease = quota.tryAdmit(encodedBytes = 5).shouldNotBeNull()
        quota.tryAdmit(encodedBytes = 1).shouldBeNull()
        quota.tryAdmit(encodedBytes = 6).shouldBeNull()

        quota.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 1, 5, 5, 2, true)
        repeat(100) {
            quota.tryAdmit(encodedBytes = 1).shouldBeNull()
        }
        quota.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 1, 5, 5, 102, true)

        retainedLease.release()
        quota.health() shouldBeEqualTo SnapshotInvalidationQuotaHealth(2, 0, 5, 0, 102, false)
        assertFailsWith<IllegalArgumentException> { quota.tryAdmit(encodedBytes = 0) }
        assertFailsWith<IllegalArgumentException> { quota.tryAdmit(encodedBytes = -1) }
    }

    @Test
    fun `queued weak client identity is removed without background work`() {
        val registry = RedissonInvalidationQuotaRegistry()
        val firstClient = equalToEveryClientProxy()
        registry.quotaFor(firstClient, maxOutstandingChunks = 1, maxOutstandingEncodedBytes = 8)
        clearAndEnqueueRegisteredWeakKey(registry)

        val replacementClient = equalToEveryClientProxy()
        registry.quotaFor(replacementClient, maxOutstandingChunks = 2, maxOutstandingEncodedBytes = 16)

        registeredWeakKeys(registry).size shouldBeEqualTo 1
        (registeredWeakKeys(registry).single().get() === replacementClient).shouldBeTrue()
    }

    private fun equalToEveryClientProxy(): RedissonClient = Proxy.newProxyInstance(
        RedissonClient::class.java.classLoader,
        arrayOf(RedissonClient::class.java),
    ) { _, method, args ->
        when (method.name) {
            "equals" -> true
            "hashCode" -> 1
            "toString" -> "EqualRedissonClientProxy"
            else -> error("Unexpected RedissonClient call: ${method.name}(${args?.size ?: 0})")
        }
    } as RedissonClient

    private fun clearAndEnqueueRegisteredWeakKey(registry: RedissonInvalidationQuotaRegistry) {
        val weakKey = registeredWeakKeys(registry).single()
        weakKey.clear()
        weakKey.enqueue().shouldBeTrue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun registeredWeakKeys(registry: RedissonInvalidationQuotaRegistry): Set<Reference<RedissonClient>> {
        val quotasField = registry.javaClass.declaredFields.single {
            Map::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        val quotas = quotasField.get(registry) as Map<Reference<RedissonClient>, *>
        return quotas.keys
    }
}
