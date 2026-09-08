package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.MapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExposedLettuceLoadedMapRetryTest: AbstractJdbcLettuceTest() {

    @Test
    fun `write-behind mixed retry batch preserves each entry count in both queue orders`() {
        val prefix = randomName()
        val attempts = AtomicInteger()
        val attemptedBatches = CopyOnWriteArrayList<List<String>>()
        val firstAttemptStarted = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val freshWritten = CountDownLatch(1)
        val writer =
            object: MapWriter<String, String> {
                override fun write(map: Map<String, String>) {
                    attemptedBatches += map.keys.toList()
                    when (attempts.incrementAndGet()) {
                        1 -> {
                            firstAttemptStarted.countDown()
                            check(releaseFirstAttempt.await(5, TimeUnit.SECONDS)) {
                                "첫 번째 write-behind 시도 해제 대기 시간이 초과되었습니다."
                            }
                            error("planned first write failure")
                        }
                        2, 3 -> error("planned mixed write failure")
                        else -> if ("fresh" in map) freshWritten.countDown()
                    }
                }

                override fun delete(keys: Collection<String>) = Unit
            }
        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeBehindBatchSize = 2,
            writeBehindDelay = Duration.ofMillis(20)
        )

        try {
            map["retried"] = "old"
            check(firstAttemptStarted.await(5, TimeUnit.SECONDS)) {
                "첫 번째 write-behind 시도가 시작되지 않았습니다."
            }
            map["fresh"] = "new"
            releaseFirstAttempt.countDown()

            check(freshWritten.await(5, TimeUnit.SECONDS)) {
                "새 항목이 재시도 후 성공하지 않았습니다."
            }

            attemptedBatches shouldBeEqualTo listOf(
                listOf("retried"),
                listOf("retried", "fresh"),
                listOf("fresh", "retried"),
                listOf("fresh")
            )
            deadLetterKeys(prefix) shouldBeEqualTo listOf("retried")
        } finally {
            releaseFirstAttempt.countDown()
            map.close()
        }
    }

    @Test
    fun `write-behind queue offer failure dead-letters only the rejected entry`() {
        val prefix = randomName()
        val attempts = AtomicInteger()
        val attemptedBatches = CopyOnWriteArrayList<List<String>>()
        val firstAttemptStarted = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val freshWritten = CountDownLatch(1)
        val writer =
            object: MapWriter<String, String> {
                override fun write(map: Map<String, String>) {
                    attemptedBatches += map.keys.toList()
                    if (attempts.incrementAndGet() == 1) {
                        firstAttemptStarted.countDown()
                        check(releaseFirstAttempt.await(5, TimeUnit.SECONDS)) {
                            "첫 번째 write-behind 시도 해제 대기 시간이 초과되었습니다."
                        }
                        error("planned queue saturation failure")
                    }
                    if ("fresh" in map) freshWritten.countDown()
                }

                override fun delete(keys: Collection<String>) = Unit
            }
        val map = newMap(
            prefix = prefix,
            writer = writer,
            writeBehindBatchSize = 1,
            writeBehindQueueCapacity = 1,
            writeBehindDelay = Duration.ofMillis(20)
        )

        try {
            map["retried"] = "old"
            check(firstAttemptStarted.await(5, TimeUnit.SECONDS)) {
                "첫 번째 write-behind 시도가 시작되지 않았습니다."
            }
            map["fresh"] = "new"
            releaseFirstAttempt.countDown()

            check(freshWritten.await(5, TimeUnit.SECONDS)) {
                "포화된 큐에 남은 새 항목이 write-behind에 반영되지 않았습니다."
            }

            attemptedBatches shouldBeEqualTo listOf(listOf("retried"), listOf("fresh"))
            deadLetterKeys(prefix) shouldBeEqualTo listOf("retried")
        } finally {
            releaseFirstAttempt.countDown()
            map.close()
        }
    }

    private fun newMap(
        prefix: String,
        writer: MapWriter<String, String>,
        writeBehindBatchSize: Int,
        writeBehindQueueCapacity: Int = 10,
        writeBehindDelay: Duration,
    ): ExposedLettuceLoadedMap<String, String> =
        ExposedLettuceLoadedMap(
            client = redisClient,
            writer = writer,
            config =
                LettuceCacheConfig(
                    keyPrefix = prefix,
                    writeMode = WriteMode.WRITE_BEHIND,
                    writeBehindBatchSize = writeBehindBatchSize,
                    writeBehindQueueCapacity = writeBehindQueueCapacity,
                    writeBehindDelay = writeBehindDelay,
                    writeBehindShutdownTimeout = Duration.ofSeconds(5)
                ),
            valueCodec = StringCodec.UTF8
        )

    private fun deadLetterKeys(prefix: String): List<String> =
        redisClient.connect(StringCodec.UTF8).use { connection ->
            connection.sync().lrange("$prefix:dead-letter", 0, -1)
        }
}
