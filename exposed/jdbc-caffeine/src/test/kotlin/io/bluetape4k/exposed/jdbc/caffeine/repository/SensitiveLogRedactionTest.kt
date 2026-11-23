package io.bluetape4k.exposed.jdbc.caffeine.repository

import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorJdbcCaffeineRepository
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.ActorRecord
import io.bluetape4k.exposed.jdbc.caffeine.domain.ActorSchema.withActorTable
import io.bluetape4k.exposed.tests.TestDB
import org.junit.jupiter.api.Test

class SensitiveLogRedactionTest {

    @Test
    fun `post-persistence logs omit caller ID and entity payload`() {
        val sensitiveId = 987654321L
        val sensitiveValue = "credential=caffeine-redaction-sentinel"
        val repository = ActorJdbcCaffeineRepository(LocalCacheConfig.WRITE_THROUGH)

        withActorTable(TestDB.H2) {
            RecordingLogbackAppender().use { appender ->
                repository.put(
                    sensitiveId,
                    ActorRecord(
                        id = sensitiveId,
                        firstName = sensitiveValue,
                        lastName = "redaction-test",
                        email = "token=$sensitiveValue@example.com",
                    ),
                )

                val rendered = appender.events.joinToString("\n") { it.formattedMessage }
                rendered shouldNotContain sensitiveId.toString()
                rendered shouldNotContain sensitiveValue
            }
        }
    }
}
