package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.SuspendedMapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ExposedLettuceSuspendedLoadedMapRetryTest: AbstractJdbcLettuceTest() {

    companion object: KLoggingChannel()

    @Test
    fun `write-behind mixed retry batch preserves each entry count for fresh-first order`() = runSuspendIO {
        val prefix = randomName()
        val attempts = AtomicInteger()
        val attemptedBatches = CopyOnWriteArrayList<List<String>>()
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val releaseFirstAttempt = CompletableDeferred<Unit>()
        val freshWritten = CompletableDeferred<Unit>()

        val writer = object: SuspendedMapWriter<String, String> {
            override suspend fun write(map: Map<String, String>) {
                attemptedBatches += map.keys.toList()
                when (attempts.incrementAndGet()) {
                    1    -> {
                        firstAttemptStarted.complete(Unit)
                        releaseFirstAttempt.await()
                        error("planned first write failure")
                    }
                    2, 3 -> error("planned mixed write failure")
                    else -> if ("fresh" in map) freshWritten.complete(Unit)
                }
            }

            override suspend fun delete(keys: Collection<String>) = Unit
        }

        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeBehindBatchSize = 2,
            writeBehindDelay = Duration.ofMillis(20)
        )

        try {
            map.set("retried", "old")
            withTimeout(timeMillis = 5_000) { firstAttemptStarted.await() }

            map.set("fresh", "new")
            releaseFirstAttempt.complete(Unit)
            withTimeout(timeMillis = 5_000) { freshWritten.await() }

            attemptedBatches shouldBeEqualTo listOf(
                listOf("retried"),
                listOf("fresh", "retried"),
                listOf("fresh", "retried"),
                listOf("fresh")
            )

            deadLetterKeys(prefix) shouldBeEqualTo listOf("retried")
        } finally {
            releaseFirstAttempt.complete(Unit)
            map.suspendClose()
        }
    }

    @Test
    fun `write-behind channel offer failure dead-letters only the rejected entry`() = runSuspendIO {
        val prefix = randomName()
        val attempts = AtomicInteger()
        val attemptedBatches = CopyOnWriteArrayList<List<String>>()
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val releaseFirstAttempt = CompletableDeferred<Unit>()
        val freshWritten = CompletableDeferred<Unit>()

        val writer = object: SuspendedMapWriter<String, String> {
            override suspend fun write(map: Map<String, String>) {
                attemptedBatches += map.keys.toList()
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                    error("planned channel saturation failure")
                }
                if ("fresh" in map) freshWritten.complete(Unit)
            }

            override suspend fun delete(keys: Collection<String>) = Unit
        }

        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeBehindBatchSize = 1,
            writeBehindQueueCapacity = 1,
            writeBehindDelay = Duration.ofMillis(20)
        )

        try {
            map.set("retried", "old")
            withTimeout(timeMillis = 5_000) { firstAttemptStarted.await() }

            map.set("fresh", "new")
            releaseFirstAttempt.complete(Unit)
            withTimeout(timeMillis = 5_000) { freshWritten.await() }

            attemptedBatches shouldBeEqualTo listOf(listOf("retried"), listOf("fresh"))
            deadLetterKeys(prefix) shouldBeEqualTo listOf("retried")
        } finally {
            releaseFirstAttempt.complete(Unit)
            map.suspendClose()
        }
    }

    @Test
    fun `write-behind propagates writer cancellation without retry or dead-letter`() = runSuspendIO {
        val prefix = randomName()
        val attempts = AtomicInteger()
        val writerStarted = CompletableDeferred<Unit>()
        val writer = object: SuspendedMapWriter<String, String> {
            override suspend fun write(map: Map<String, String>) {
                attempts.incrementAndGet()
                writerStarted.complete(Unit)
                throw CancellationException("planned writer cancellation")
            }

            override suspend fun delete(keys: Collection<String>) = Unit
        }

        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeBehindBatchSize = 1,
            writeBehindDelay = Duration.ofMillis(20)
        )

        var closed = false
        try {
            map.set("cancelled", "value")
            withTimeout(timeMillis = 5_000) { writerStarted.await() }
            map.suspendClose()
            closed = true

            attempts.get() shouldBeEqualTo 1
            deadLetterKeys(prefix).shouldBeEmpty()
        } finally {
            if (!closed) map.suspendClose()
        }
    }

    private fun newMap(
        prefix: String,
        writer: SuspendedMapWriter<String, String>,
        writeBehindBatchSize: Int,
        writeBehindQueueCapacity: Int = 10,
        writeBehindDelay: Duration,
    ): ExposedLettuceSuspendedLoadedMap<String, String> =
        ExposedLettuceSuspendedLoadedMap(
            client = redisClient,
            writer = writer,
            config = LettuceCacheConfig(
                keyPrefix = prefix,
                writeMode = WriteMode.WRITE_BEHIND,
                writeBehindBatchSize = writeBehindBatchSize,
                writeBehindQueueCapacity = writeBehindQueueCapacity,
                writeBehindDelay = writeBehindDelay,
                writeBehindShutdownTimeout = Duration.ofSeconds(5)
            ),
            valueCodec = StringCodec.UTF8
        )

    private suspend fun deadLetterKeys(prefix: String): List<String> =
        redisClient.connect(StringCodec.UTF8).use { connection ->
            connection.async().lrange("$prefix:dead-letter", 0, -1).await()
        }
}
