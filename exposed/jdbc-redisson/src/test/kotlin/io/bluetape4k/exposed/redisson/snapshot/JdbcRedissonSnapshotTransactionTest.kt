package io.bluetape4k.exposed.redisson.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RLocalCachedMap
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import java.io.Serializable
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture

class JdbcRedissonSnapshotTransactionTest {

    @Test
    fun `source usage compiles and exposes only the exact transaction invalidation extension`() {
        val invalidator = fixture("api:v1").invalidator
        val database = database()

        transaction(database) {
            maxAttempts = 1
            stageInvalidation(invalidator, 1L)
        }

        val methods = Class.forName(
            "io.bluetape4k.exposed.redisson.snapshot.JdbcRedissonSnapshotTransactionKt",
        ).declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.name == "stageInvalidation"
        }
        methods.size shouldBeEqualTo 1
        methods.single().parameterTypes.toList() shouldBeEqualTo listOf(
            JdbcTransaction::class.java,
            JdbcRedissonSnapshotInvalidator::class.java,
            Any::class.java,
        )
    }

    @Test
    fun `commit submits the actual invalidator command only after transaction success`() {
        val fixture = fixture("commit:v1")

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            fixture.map.submittedIds.shouldBeEqualTo(emptyList())
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
        fixture.map.invokedMethods shouldBeEqualTo listOf("fastRemoveAsync")
    }

    @Test
    fun `rollback submits no Redis command`() {
        val fixture = fixture("rollback:v1")

        assertFailsWith<RollbackMarker> {
            transaction(database()) {
                maxAttempts = 1
                stageInvalidation(fixture.invalidator, 1L)
                throw RollbackMarker()
            }
        }

        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
        fixture.map.invokedMethods.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `captured closed transaction is rejected`() {
        val fixture = fixture("closed:v1")
        lateinit var captured: JdbcTransaction

        transaction(database()) {
            maxAttempts = 1
            captured = this
        }

        assertFailsWith<IllegalStateException> {
            captured.stageInvalidation(fixture.invalidator, 1L)
        }
        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `wrong current receiver and savepoint transaction are rejected`() {
        val fixture = fixture("nested:v1")
        val database = database(useNestedTransactions = true)

        transaction(database) outer@{
            maxAttempts = 1
            transaction(database) {
                maxAttempts = 1
                assertFailsWith<IllegalStateException> {
                    this@outer.stageInvalidation(fixture.invalidator, 1L)
                }
                assertFailsWith<IllegalStateException> {
                    stageInvalidation(fixture.invalidator, 2L)
                }
            }
        }

        fixture.map.submittedIds.shouldBeEqualTo(emptyList())
    }

    @Test
    fun `transaction invalidation requires one configured database attempt`() {
        val fixture = fixture("attempts:v1")

        transaction(database()) {
            maxAttempts = 2
            assertFailsWith<IllegalStateException> {
                stageInvalidation(fixture.invalidator, 1L)
            }
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 2L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(2L))
    }

    @Test
    fun `repeated identifier coalesces to one last invalidation`() {
        val fixture = fixture("coalesce:v1")

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            stageInvalidation(fixture.invalidator, 1L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
    }

    @Test
    fun `commit byte rejection preserves prior mutation and permits a later valid replacement`() {
        val fixture = fixture("commit-cap:v1", maxCommitEncodedKeyBytes = Long.SIZE_BYTES)

        transaction(database()) {
            maxAttempts = 1
            stageInvalidation(fixture.invalidator, 1L)
            assertFailsWith<IllegalStateException> {
                stageInvalidation(fixture.invalidator, 2L)
            }
            stageInvalidation(fixture.invalidator, 1L)
        }

        fixture.map.submittedIds shouldBeEqualTo listOf(listOf(1L))
    }

    private fun fixture(
        namespace: String,
        maxCommitEncodedKeyBytes: Int = 256,
    ): Fixture {
        val map = RecordingLocalMap<Long>()
        val client = RecordingRedissonClient(map.proxy)
        val codec = snapshotRedissonCodec(StringCodec(), "json-v1", longSnapshotIdentifierPolicy())
        val config = JdbcRedissonSnapshotInvalidatorConfig(
            snapshot = SnapshotCacheConfig(namespace, "payload-v1", 32, 4),
            nearCacheMaximumSize = 16,
            maxEncodedKeyBytes = Long.SIZE_BYTES,
            maxBatchEncodedKeyBytes = minOf(Long.SIZE_BYTES * 4, maxCommitEncodedKeyBytes),
            maxCommitEncodedKeyBytes = maxCommitEncodedKeyBytes,
            maxOutstandingChunks = 4,
            maxOutstandingEncodedBytes = 256,
        )
        return Fixture(
            invalidator = jdbcRedissonSnapshotInvalidator(
                client.proxy,
                codec,
                Long::class,
                Payload::class,
                config,
            ),
            map = map,
        )
    }

    private fun database(useNestedTransactions: Boolean = false): Database = Database.connect(
        url = "jdbc:h2:mem:redisson-transaction-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        databaseConfig = DatabaseConfig { this.useNestedTransactions = useNestedTransactions },
    )

    private data class Fixture(
        val invalidator: JdbcRedissonSnapshotInvalidator<Long>,
        val map: RecordingLocalMap<Long>,
    )

    private class RecordingRedissonClient(localCacheMap: RLocalCachedMap<*, *>) {
        val proxy: RedissonClient = Proxy.newProxyInstance(
            RedissonClient::class.java.classLoader,
            arrayOf(RedissonClient::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "getLocalCachedMap" -> localCacheMap
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingRedissonClient"
                else -> error("Unexpected RedissonClient call: ${method.name}")
            }
        } as RedissonClient
    }

    private class RecordingLocalMap<ID : Any> {
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
                    submittedIds += (args.orEmpty().single() as Array<*>).map { it as ID }
                    completedRFuture(1L)
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "RecordingLocalMap"
                else -> error("Non-invalidation Redisson map command invoked: ${method.name}")
            }
        } as RLocalCachedMap<ID, Any?>
    }

    private data class Payload(val value: String = "value") : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class RollbackMarker : RuntimeException()

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun completedRFuture(value: Long): RFuture<Long> = Proxy.newProxyInstance(
            RFuture::class.java.classLoader,
            arrayOf(RFuture::class.java),
        ) { instance, method, args ->
            when (method.name) {
                "whenComplete" -> {
                    val callback = args.orEmpty().single() as java.util.function.BiConsumer<Long?, Throwable?>
                    callback.accept(value, null)
                    instance
                }
                "equals" -> instance === args.orEmpty().singleOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "CompletedRFuture"
                else -> method.invoke(CompletableFuture.completedFuture(value), *args.orEmpty())
            }
        } as RFuture<Long>
    }
}
