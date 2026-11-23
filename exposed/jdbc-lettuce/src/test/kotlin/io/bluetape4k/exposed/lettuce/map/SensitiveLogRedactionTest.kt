package io.bluetape4k.exposed.lettuce.map

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.lettuce.AbstractJdbcLettuceTest
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.bluetape4k.redis.lettuce.map.SuspendedMapWriter
import io.bluetape4k.redis.lettuce.map.WriteMode
import io.lettuce.core.codec.StringCodec
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import java.time.Duration

class SensitiveLogRedactionTest: AbstractJdbcLettuceTest() {

    @Test
    fun `synchronous map logs omit caller key`() {
        val sensitiveKey = "email=jdbc-lettuce-redaction-sentinel@example.com"
        val map = ExposedLettuceLoadedMap<String, String>(
            client = redisClient,
            config = LettuceCacheConfig(keyPrefix = randomName(), writeMode = WriteMode.NONE),
            valueCodec = StringCodec.UTF8,
        )

        try {
            RecordingLogAppender().use { appender ->
                map.delete(sensitiveKey)

                appender.rendered shouldNotContain sensitiveKey
            }
        } finally {
            map.close()
        }
    }

    @Test
    fun `suspended map logs omit caller key and value`() = runSuspendIO {
        val sensitiveKey = "email=jdbc-lettuce-redaction-sentinel@example.com"
        val sensitiveValue = "token=jdbc-lettuce-redaction-sentinel"
        val map = ExposedLettuceSuspendedLoadedMap<String, String>(
            client = redisClient,
            config = LettuceCacheConfig(keyPrefix = randomName(), writeMode = WriteMode.NONE),
            valueCodec = StringCodec.UTF8,
        )

        try {
            RecordingLogAppender().use { appender ->
                map.set(sensitiveKey, sensitiveValue)

                appender.rendered shouldNotContain sensitiveKey
                appender.rendered shouldNotContain sensitiveValue
            }
        } finally {
            map.suspendClose()
        }
    }

    @Test
    fun `write-behind failure logs omit caller payload and exception message`() = runSuspendIO {
        val sensitiveKey = "email=jdbc-lettuce-failure@example.com"
        val sensitiveValue = "credential=jdbc-lettuce-failure-sentinel"
        val sensitiveMessage = "token=jdbc-lettuce-error-sentinel"
        val writer = object: SuspendedMapWriter<String, String> {
            override suspend fun write(map: Map<String, String>) {
                throw IllegalStateException("$sensitiveMessage:$map")
            }

            override suspend fun delete(keys: Collection<String>) = Unit
        }
        val map = ExposedLettuceSuspendedLoadedMap<String, String>(
            client = redisClient,
            writer = writer,
            config = LettuceCacheConfig(
                keyPrefix = randomName(),
                writeMode = WriteMode.WRITE_BEHIND,
                writeBehindBatchSize = 1,
                writeBehindDelay = Duration.ofMillis(20),
            ),
            valueCodec = StringCodec.UTF8,
        )

        RecordingLogAppender().use { appender ->
            try {
                map.set(sensitiveKey, sensitiveValue)
                await
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(100))
                    .until {
                        appender.events.count { it.formattedMessage.contains("Write-behind flush failed") } >= 3
                    }

                appender.rendered shouldNotContain sensitiveKey
                appender.rendered shouldNotContain sensitiveValue
                appender.rendered shouldNotContain sensitiveMessage
                appender.events
                    .filter { it.formattedMessage.contains("Write-behind flush failed") }
                    .all { it.throwableProxy == null }
                    .shouldBeTrue()
            } finally {
                map.suspendClose()
            }
        }
    }
}
