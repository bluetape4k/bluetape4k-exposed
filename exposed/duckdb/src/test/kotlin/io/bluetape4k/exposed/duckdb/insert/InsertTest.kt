package io.bluetape4k.exposed.duckdb.insert

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.exposed.duckdb.AbstractDuckDBTest
import io.bluetape4k.exposed.duckdb.domain.Events
import io.bluetape4k.exposed.duckdb.suspendTransaction
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class InsertTest: AbstractDuckDBTest() {

    companion object: KLogging() {
        private val DEFAULT_OCCURRED_AT: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }

    private data class EventFixture(
        val eventId: Long,
        val userId: Long,
        val eventType: String,
        val region: String,
        val amount: BigDecimal?,
        val occurredAt: Instant = DEFAULT_OCCURRED_AT,
    )

    private val fixtures = listOf(
        EventFixture(1L, 100L, "PURCHASE", "kr", BigDecimal("9900.00")),
        EventFixture(2L, 100L, "VIEW", "kr", null),
        EventFixture(3L, 200L, "PURCHASE", "us", BigDecimal("49.99")),
        EventFixture(4L, 200L, "CLICK", "us", null),
        EventFixture(5L, 300L, "PURCHASE", "eu", BigDecimal("19.90")),
    )

    @BeforeEach
    fun setUp() {
        withEventsTable {}
    }

    private fun insertFixtures() {
        runSuspendIO {
            suspendTransaction(db, Dispatchers.IO) {
                fixtures.forEach { f ->
                    Events.insert {
                        it[eventId] = f.eventId
                        it[userId] = f.userId
                        it[eventType] = f.eventType
                        it[region] = f.region
                        it[amount] = f.amount
                        it[occurredAt] = f.occurredAt
                    }
                }
            }
        }
    }

    @Test
    fun `insert - 이벤트 대량 적재`() {
        insertFixtures()

        val rows = transaction(db) {
            Events.selectAll().toList()
        }

        rows shouldHaveSize fixtures.size
    }

    @Suppress("DEPRECATION")
    @Test
    fun `suspended insert - 이벤트 대량 적재`() = runSuspendIO {
        insertFixtures()

        val rows = suspendedTransactionAsync(Dispatchers.IO, db) {
            Events.selectAll().toList()
        }.await()

        rows shouldHaveSize fixtures.size
    }

    @Test
    fun `insert - 리전별 집계 검증`() {
        insertFixtures()

        val krRows = transaction(db) {
            Events.selectAll().where { Events.region eq "kr" }.toList()
        }.onEach { log.debug { "kr row=$it" } }
        
        krRows shouldHaveSize 2
        krRows.all { it[Events.region] == "kr" }.shouldBeTrue()

        val purchaseRows = transaction(db) {
            Events.selectAll().where { Events.eventType eq "PURCHASE" }.toList()
        }.onEach { log.debug { "PURCHASE row=$it" } }
        purchaseRows shouldHaveSize 3
    }

    @Test
    fun `insert - nullable amount - null 행 수 검증`() {
        insertFixtures()

        val allRows = transaction(db) {
            Events.selectAll().toList()
        }
        allRows shouldHaveSize 5

        val nullAmountRows = allRows.filter { it[Events.amount] == null }.onEach { log.debug { "row=$it" } }
        nullAmountRows.shouldNotBeEmpty()
        nullAmountRows shouldHaveSize 2
    }

    @Test
    fun `insert - 단건 조회`() {
        insertFixtures()

        val row = transaction(db) {
            Events.selectAll().where { Events.eventId eq 1L }.single()
        }
        log.debug { "row=$row" }
        row[Events.userId] shouldBeEqualTo 100L
        row[Events.eventType] shouldBeEqualTo "PURCHASE"
        row[Events.region] shouldBeEqualTo "kr"
    }
}
