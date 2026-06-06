package io.bluetape4k.exposed.starrocks.insert

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.starrocks.AbstractStarRocksTest
import io.bluetape4k.exposed.starrocks.domain.Events
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Insert smoke tests for StarRocks through Exposed.
 */
class InsertTest: AbstractStarRocksTest() {

    @Test
    fun `insert event through Exposed`() {
        transaction(db) {
            Events.insert {
                it[eventId] = 1L
                it[eventName] = "click"
                it[region] = "kr"
            }

            Events.selectAll().count() shouldBeEqualTo 1L
        }
    }
}
