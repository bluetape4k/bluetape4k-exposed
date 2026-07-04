package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.SuspendedMapLoader
import io.bluetape4k.redis.lettuce.map.SuspendedMapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ExposedLettuceSuspendedLoadedMapTest: AbstractJdbcLettuceTest() {

    @Test
    fun `get and getAll load cache misses and pattern invalidation removes cached values`() = runTest {
        val prefix = randomName()
        val loadCalls = AtomicInteger()
        val loader =
            object: SuspendedMapLoader<String, String> {
                private val values = mapOf("a" to "alpha", "b" to "bravo")

                override suspend fun load(key: String): String? {
                    loadCalls.incrementAndGet()
                    return values[key]
                }

                override suspend fun loadAllKeys(): List<String> = values.keys.toList()
            }

        val map = newMap(prefix = prefix, loader = loader)
        try {
            map.get("a") shouldBeEqualTo "alpha"
            map.get("a") shouldBeEqualTo "alpha"
            loadCalls.get() shouldBeEqualTo 1

            map.getAll(setOf("a", "b", "missing")) shouldBeEqualTo mapOf("a" to "alpha", "b" to "bravo")

            map.invalidateByPattern("*") shouldBeGreaterThan 0L
            map.get("a") shouldBeEqualTo "alpha"

            map.clear()
            map.get("b") shouldBeEqualTo "bravo"
        } finally {
            map.suspendClose()
        }
    }

    @Test
    fun `write-through set and delete keep writer and redis state aligned`() = runTest {
        val prefix = randomName()
        val written = ConcurrentHashMap<String, String>()
        val deleted = mutableListOf<String>()
        val writer =
            object: SuspendedMapWriter<String, String> {
                override suspend fun write(map: Map<String, String>) {
                    written.putAll(map)
                }

                override suspend fun delete(keys: Collection<String>) {
                    deleted.addAll(keys)
                    keys.forEach { written.remove(it) }
                }
            }

        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeMode = WriteMode.WRITE_THROUGH
        )
        try {
            map.set("a", "alpha")
            map.set("b", "bravo")
            written shouldBeEqualTo mapOf("a" to "alpha", "b" to "bravo")

            map.delete("a")
            deleted shouldBeEqualTo listOf("a")
            map.get("a").shouldBeNull()

            map.evictAll(listOf("b"))
            map.get("b").shouldBeNull()
        } finally {
            map.suspendClose()
        }
    }

    @Test
    fun `write-behind suspendClose drains queued writes`() = runTest {
        val written = ConcurrentHashMap<String, String>()
        val map = newMap(
            prefix = randomName(),
            writer = collectingWriter(written),
            writeMode = WriteMode.WRITE_BEHIND
        )

        map.set("a", "alpha")
        map.suspendClose()

        written shouldBeEqualTo mapOf("a" to "alpha")
    }

    @Test
    fun `write-behind close drains queued writes from blocking boundary`() = runTest {
        val written = ConcurrentHashMap<String, String>()
        val map = newMap(
            prefix = randomName(),
            writer = collectingWriter(written),
            writeMode = WriteMode.WRITE_BEHIND
        )

        map.set("a", "alpha")
        map.close()

        written shouldBeEqualTo mapOf("a" to "alpha")
    }

    @Test
    fun `write-behind failure is handled during suspendClose`() = runTest {
        val map = newMap(
            prefix = randomName(),
            writer =
                object: SuspendedMapWriter<String, String> {
                    override suspend fun write(map: Map<String, String>) {
                        error("planned write failure")
                    }

                    override suspend fun delete(keys: Collection<String>) = Unit
                },
            writeMode = WriteMode.WRITE_BEHIND
        )

        map.set("a", "alpha")
        map.suspendClose()
    }

    private fun newMap(
        prefix: String,
        loader: SuspendedMapLoader<String, String>? = null,
        writer: SuspendedMapWriter<String, String>? = null,
        writeMode: WriteMode = WriteMode.NONE,
    ): ExposedLettuceSuspendedLoadedMap<String, String> =
        ExposedLettuceSuspendedLoadedMap(
            client = redisClient,
            loader = loader,
            writer = writer,
            config =
                LettuceCacheConfig(
                    keyPrefix = prefix,
                    writeMode = writeMode,
                    writeBehindDelay = Duration.ofMillis(10),
                    writeBehindBatchSize = 10,
                    writeBehindShutdownTimeout = Duration.ofSeconds(5)
                ),
            valueCodec = StringCodec.UTF8
        )

    private fun collectingWriter(
        written: ConcurrentHashMap<String, String>,
    ): SuspendedMapWriter<String, String> =
        object: SuspendedMapWriter<String, String> {
            override suspend fun write(map: Map<String, String>) {
                written.putAll(map)
            }

            override suspend fun delete(keys: Collection<String>) {
                keys.forEach { written.remove(it) }
            }
        }
}
