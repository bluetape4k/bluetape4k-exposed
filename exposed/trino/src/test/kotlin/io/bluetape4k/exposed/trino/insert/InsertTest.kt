package io.bluetape4k.exposed.trino.insert

import io.bluetape4k.exposed.trino.AbstractTrinoTest
import io.bluetape4k.exposed.trino.TrinoBatchInsertOptions
import io.bluetape4k.exposed.trino.domain.Events
import io.bluetape4k.exposed.trino.trinoBatchInsert
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Instant

class InsertTest: AbstractTrinoTest() {

    companion object: KLogging()

    @Test
    fun `TrinoBatchInsertOptions validates chunk size`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoBatchInsertOptions(chunkSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TrinoBatchInsertOptions(chunkSize = -1)
        }
    }

    @Test
    fun `insert - 단건 INSERT 후 count 검증`() = withEventsTable {
        transaction(db) {
            Events.insert {
                it[eventId] = 1L
                it[eventName] = "click"
                it[region] = "kr"
                it[createdAt] = Instant.parse("2024-01-01T00:00:00Z")
            }
        }

        val count = transaction(db) {
            Events.selectAll().count()
        }

        count shouldBeEqualTo 1L
    }

    @Test
    fun `batchInsert - N건 일괄 INSERT 후 count 검증`() = withEventsTable {
        val items = listOf(
            Triple(1L, "click", "kr"),
            Triple(2L, "view", "us"),
            Triple(3L, "purchase", "eu"),
        )

        transaction(db) {
            Events.batchInsert(items) { (id, name, region) ->
                this[Events.eventId] = id
                this[Events.eventName] = name
                this[Events.region] = region
                this[Events.createdAt] = Instant.parse("2024-01-01T00:00:00Z")
            }
        }

        val count = transaction(db) {
            Events.selectAll().count()
        }

        count shouldBeEqualTo items.size.toLong()
    }

    @Test
    fun `trinoBatchInsert - chunk 단위 INSERT 후 count 검증`() = withEventsTable {
        val items = (1L..5L).map { id ->
            Triple(id, "event_$id", "region_${id % 2}")
        }

        val returnedRows = transaction(db) {
            Events.trinoBatchInsert(items, TrinoBatchInsertOptions(chunkSize = 2)) { (id, name, region) ->
                this[Events.eventId] = id
                this[Events.eventName] = name
                this[Events.region] = region
                this[Events.createdAt] = Instant.parse("2024-01-01T00:00:00Z")
            }
        }

        returnedRows shouldHaveSize 0
        val count = transaction(db) {
            Events.selectAll().count()
        }
        count shouldBeEqualTo items.size.toLong()
    }

    @Test
    fun `trinoBatchInsert - generated values option path 도 INSERT 를 유지한다`() = withEventsTable {
        val items = listOf(
            Triple(1L, "generated-click", "kr"),
            Triple(2L, "generated-view", "us"),
        )

        val returnedRows = transaction(db) {
            Events.trinoBatchInsert(
                data = items,
                options = TrinoBatchInsertOptions(chunkSize = 10, shouldReturnGeneratedValues = true),
            ) { (id, name, region) ->
                this[Events.eventId] = id
                this[Events.eventName] = name
                this[Events.region] = region
                this[Events.createdAt] = Instant.parse("2024-01-01T00:00:00Z")
            }
        }

        returnedRows.map { it[Events.eventId] } shouldBeEqualTo listOf(1L, 2L)
        val count = transaction(db) {
            Events.selectAll().count()
        }
        count shouldBeEqualTo items.size.toLong()
    }

    @Test
    fun `trinoBatchInsert - 빈 data 는 INSERT body 를 실행하지 않는다`() = withEventsTable {
        var invoked = false

        val returnedRows = transaction(db) {
            Events.trinoBatchInsert(emptyList<Triple<Long, String, String>>()) {
                invoked = true
            }
        }

        returnedRows shouldHaveSize 0
        invoked.shouldBeFalse()
        val count = transaction(db) {
            Events.selectAll().count()
        }
        count shouldBeEqualTo 0L
    }

    @Test
    fun `trinoBatchInsert - 후속 chunk 실패 시 앞선 chunk 는 롤백되지 않는다`() = withEventsTable {
        val items = (1L..5L).map { id ->
            Triple(id, "event_$id", "region_${id % 2}")
        }

        assertFailsWith<IllegalStateException> {
            transaction(db) {
                Events.trinoBatchInsert(items, TrinoBatchInsertOptions(chunkSize = 2)) { (id, name, region) ->
                    if (id == 3L) {
                        error("intentional batch failure")
                    }
                    this[Events.eventId] = id
                    this[Events.eventName] = name
                    this[Events.region] = region
                    this[Events.createdAt] = Instant.parse("2024-01-01T00:00:00Z")
                }
            }
        }

        val ids = transaction(db) {
            Events
                .select(Events.eventId)
                .orderBy(Events.eventId to SortOrder.ASC)
                .map { it[Events.eventId] }
        }
        ids shouldBeEqualTo listOf(1L, 2L)
    }

    @Test
    fun `insert - nullable createdAt null 값으로 삽입 검증`() = withEventsTable {
        transaction(db) {
            Events.insert {
                it[eventId] = 1L
                it[eventName] = "view"
                it[region] = "us"
                it[createdAt] = null
            }
        }

        val row = transaction(db) {
            Events.selectAll().single()
        }

        row[Events.createdAt].shouldBeNull()
    }
}
