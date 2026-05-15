package io.bluetape4k.exposed.trino

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.trino.domain.Events
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.Instant

class TrinoExtensionsTest: AbstractTrinoTest() {

    companion object: KLogging() {
        private val CREATED_AT: Instant = Instant.parse("2024-06-01T00:00:00Z")

        private val FIXTURES = listOf(
            Triple(1L, "click", "kr"),
            Triple(2L, "view", "us"),
            Triple(3L, "purchase", "eu"),
        )

        private val PAGED_FIXTURES = listOf(
            Triple(1L, "click", "kr"),
            Triple(2L, "view", "us"),
            Triple(3L, "purchase", "eu"),
            Triple(4L, "signup", "jp"),
            Triple(5L, "logout", "au"),
        )
    }

    /**
     * 픽스처 데이터를 events 테이블에 삽입합니다.
     */
    private fun insertFixtures() {
        insertRows(FIXTURES)
    }

    /**
     * 테스트 데이터를 events 테이블에 삽입합니다.
     */
    private fun insertRows(rows: List<Triple<Long, String, String>>) {
        rows.forEach { (id, name, region) ->
            Events.insert {
                it[eventId] = id
                it[eventName] = name
                it[Events.region] = region
                it[createdAt] = CREATED_AT
            }
        }
    }

    @Test
    fun `TrinoPagedQueryOptions validates page settings`() {
        assertFailsWith<IllegalArgumentException> {
            TrinoPagedQueryOptions(pageSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TrinoPagedQueryOptions(initialOffset = -1L)
        }
    }

    @Test
    fun `Trino option classes are Serializable with stable serialVersionUID`() {
        TrinoPagedQueryOptions() shouldBeInstanceOf Serializable::class
        TrinoBatchInsertOptions() shouldBeInstanceOf Serializable::class

        ObjectStreamClass.lookup(TrinoPagedQueryOptions::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(TrinoBatchInsertOptions::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `suspendTransaction 은 Trino 트랜잭션 결과를 반환한다`() = runTest {
        withEventsTableSuspend {
            val count = suspendTransaction(db) {
                Events.selectAll().count()
            }
            count shouldBeEqualTo 0L
        }
    }

    @Test
    fun `suspendTransaction 안에서 쓰기 후 읽기가 가능하다`() = runTest {
        withEventsTableSuspend {
            suspendTransaction(db) {
                insertFixtures()
            }

            val rows = suspendTransaction(db) {
                Events.selectAll()
                    .orderBy(Events.eventId to SortOrder.ASC)
                    .toList()
            }

            rows shouldHaveSize 3
            rows.map { it[Events.eventId] } shouldBeEqualTo listOf(1L, 2L, 3L)
            rows.map { it[Events.eventName] } shouldBeEqualTo listOf("click", "view", "purchase")
        }
    }

    @Test
    fun `queryFlow 는 Trino 쿼리 결과를 Flow 로 반환한다`() = runTest {
        withEventsTableSuspend {
            suspendTransaction(db) {
                insertFixtures()
            }

            val rows = queryFlow(db) {
                Events.selectAll()
                    .orderBy(Events.eventId to SortOrder.ASC)
            }.toList()

            rows shouldHaveSize 3
            rows.map { it[Events.eventId] } shouldBeEqualTo listOf(1L, 2L, 3L)
            rows.map { it[Events.region] } shouldBeEqualTo listOf("kr", "us", "eu")
        }
    }

    @Test
    fun `queryFlow 는 transaction 내부에서 materialize 하고 transaction 밖에서 emit 한다`() = runTest {
        withEventsTableSuspend {
            suspendTransaction(db) {
                insertFixtures()
            }

            val transactionActiveInBlock = mutableListOf<Boolean>()
            val transactionActiveDuringEmit = mutableListOf<Boolean>()

            val rows = queryFlow(db) {
                transactionActiveInBlock += (TransactionManager.currentOrNull() != null)
                Events.selectAll()
                    .orderBy(Events.eventId to SortOrder.ASC)
                    .toList()
            }.onEach {
                transactionActiveDuringEmit += (TransactionManager.currentOrNull() != null)
            }.toList()

            rows shouldHaveSize 3
            transactionActiveInBlock shouldBeEqualTo listOf(true)
            transactionActiveDuringEmit shouldBeEqualTo listOf(false, false, false)
        }
    }

    @Test
    fun `queryFlow 는 빈 테이블에서 빈 리스트를 반환한다`() = runTest {
        withEventsTableSuspend {
            val rows = queryFlow(db) {
                Events.selectAll()
            }.toList()

            rows.shouldBeEmpty()
        }
    }

    @Test
    fun `pagedQueryFlow 는 page 단위로 조회하고 순서를 보존한다`() = runTest {
        withEventsTableSuspend {
            suspendTransaction(db) {
                insertRows(PAGED_FIXTURES)
            }

            val requestedOffsets = mutableListOf<Long>()
            val rows = pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 2)) { limit, offset ->
                requestedOffsets += offset
                Events.selectAll()
                    .orderBy(Events.eventId to SortOrder.ASC)
                    .limit(limit)
                    .offset(offset)
            }.toList()

            rows shouldHaveSize 5
            rows.map { it[Events.eventId] } shouldBeEqualTo listOf(1L, 2L, 3L, 4L, 5L)
            rows.map { it[Events.region] } shouldBeEqualTo listOf("kr", "us", "eu", "jp", "au")
            requestedOffsets shouldBeEqualTo listOf(0L, 2L, 4L)
        }
    }

    @Test
    fun `pagedQueryFlow 는 take 후 다음 page 를 요청하지 않는다`() = runTest {
        withEventsTableSuspend {
            suspendTransaction(db) {
                insertRows(PAGED_FIXTURES)
            }

            val requestedOffsets = mutableListOf<Long>()
            val rows = pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 2)) { limit, offset ->
                requestedOffsets += offset
                Events.selectAll()
                    .orderBy(Events.eventId to SortOrder.ASC)
                    .limit(limit)
                    .offset(offset)
            }.take(3).toList()

            rows.map { it[Events.eventId] } shouldBeEqualTo listOf(1L, 2L, 3L)
            requestedOffsets shouldBeEqualTo listOf(0L, 2L)
        }
    }
}
