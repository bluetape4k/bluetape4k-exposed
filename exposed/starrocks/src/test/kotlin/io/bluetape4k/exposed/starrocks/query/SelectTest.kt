package io.bluetape4k.exposed.starrocks.query

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.starrocks.AbstractStarRocksTest
import io.bluetape4k.exposed.starrocks.domain.Events
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Select smoke tests for StarRocks through Exposed.
 */
class SelectTest: AbstractStarRocksTest() {

    @Test
    fun `select event by region`() {
        transaction(db) {
            Events.insert {
                it[eventId] = 1L
                it[eventName] = "click"
                it[region] = "kr"
            }
            Events.insert {
                it[eventId] = 2L
                it[eventName] = "view"
                it[region] = "us"
            }

            val names = Events
                .selectAll()
                .where { Events.region eq "kr" }
                .map { it[Events.eventName] }

            names shouldBeEqualTo listOf("click")
        }
    }
}
