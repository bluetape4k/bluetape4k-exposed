package io.bluetape4k.exposed.r2dbc.lettuce.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.lettuce.AbstractR2dbcLettuceTest
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.SuspendedMapLoader
import io.bluetape4k.redis.lettuce.map.SuspendedMapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.codec.StringCodec
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ExposedR2dbcLettuceSuspendedLoadedMapTest: AbstractR2dbcLettuceTest() {

    @Test
    fun `get and getAll load cache misses and pattern invalidation removes cached values`() = runSuspendIO {
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
    fun `write-through set and evict keep writer and redis state aligned`() = runSuspendIO {
        val prefix = randomName()
        val written = ConcurrentHashMap<String, String>()
        val writer =
            object: SuspendedMapWriter<String, String> {
                override suspend fun write(map: Map<String, String>) {
                    written.putAll(map)
                }

                override suspend fun delete(keys: Collection<String>) {
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

            map.evict("a")
            map.get("a").shouldBeNull()

            map.evictAll(listOf("b"))
            map.get("b").shouldBeNull()
        } finally {
            map.suspendClose()
        }
    }

    @Test
    fun `write-behind suspendClose drains queued writes`() = runSuspendIO {
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
    fun `write-behind close drains queued writes from blocking boundary`() = runSuspendIO {
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
    fun `write-behind failure is handled during suspendClose`() = runSuspendIO {
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

    @Test
    fun `write-behind mixed retry batch keeps each entry retry count independent`() = runSuspendIO {
        val attempts = AtomicInteger()
        val attemptedBatches = CopyOnWriteArrayList<Set<String>>()
        val secondAttemptStarted = CompletableDeferred<Unit>()
        val freshEntryRetried = CompletableDeferred<Unit>()
        val writer =
            object: SuspendedMapWriter<String, String> {
                override suspend fun write(map: Map<String, String>) {
                    attemptedBatches += map.keys
                    when (attempts.incrementAndGet()) {
                        1 -> error("planned first write failure")
                        2 -> {
                            secondAttemptStarted.complete(Unit)
                            delay(100)
                            error("planned second write failure")
                        }
                        3 -> error("planned mixed batch write failure")
                        else -> if ("fresh" in map) freshEntryRetried.complete(Unit)
                    }
                }

                override suspend fun delete(keys: Collection<String>) = Unit
            }
        val map = newMap(
            prefix = randomName(),
            writer = writer,
            writeMode = WriteMode.WRITE_BEHIND,
            writeBehindBatchSize = 2,
            writeBehindDelay = Duration.ofMillis(500)
        )

        try {
            map.set("retried", "old")
            secondAttemptStarted.await()
            delay(200)
            map.set("fresh", "new")

            withTimeout(5_000) { freshEntryRetried.await() }

            attemptedBatches shouldHaveSize 4
            attemptedBatches[2] shouldBeEqualTo setOf("retried", "fresh")
            attemptedBatches[3] shouldBeEqualTo setOf("fresh")
            attemptedBatches[3].contains("retried").shouldBeFalse()
        } finally {
            map.suspendClose()
        }
    }

    private fun newMap(
        prefix: String,
        loader: SuspendedMapLoader<String, String>? = null,
        writer: SuspendedMapWriter<String, String>? = null,
        writeMode: WriteMode = WriteMode.NONE,
        writeBehindBatchSize: Int = 10,
        writeBehindDelay: Duration = Duration.ofMillis(10),
    ): ExposedR2dbcLettuceSuspendedLoadedMap<String, String> =
        ExposedR2dbcLettuceSuspendedLoadedMap(
            client = redisClient,
            loader = loader,
            writer = writer,
            config =
                LettuceCacheConfig(
                    keyPrefix = prefix,
                    writeMode = writeMode,
                    writeBehindDelay = writeBehindDelay,
                    writeBehindBatchSize = writeBehindBatchSize,
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
