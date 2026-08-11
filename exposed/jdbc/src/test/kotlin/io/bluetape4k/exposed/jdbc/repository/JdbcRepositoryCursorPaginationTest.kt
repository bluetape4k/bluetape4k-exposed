package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

private object CursorPaginationTable : IdTable<Long>("cursor_pagination_rows") {
    override val id: Column<EntityID<Long>> = long("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val active = bool("active")
}

private data class CursorRecord(
    val id: Long,
    val active: Boolean,
)

private object CursorPaginationRepository : LongJdbcRepository<CursorRecord> {
    override val table = CursorPaginationTable

    override fun extractId(entity: CursorRecord): Long = entity.id

    override fun ResultRow.toEntity(): CursorRecord = CursorRecord(
        id = this[CursorPaginationTable.id].value,
        active = this[CursorPaginationTable.active],
    )

    fun insert(record: CursorRecord) {
        CursorPaginationTable.insert {
            it[CursorPaginationTable.id] = record.id
            it[CursorPaginationTable.active] = record.active
        }
    }
}

class JdbcRepositoryCursorPaginationTest : AbstractExposedTest() {
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `커서 페이지는 sparse ID를 오름차순으로 이어서 조회한다`(testDB: TestDB) {
        withTables(testDB, CursorPaginationTable) {
            seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))

            val first = CursorPaginationRepository.findCursorPage(pageSize = 2)
            first.content.map(CursorRecord::id) shouldBeEqualTo listOf(1L, 3L)
            first.nextCursor shouldBeEqualTo 3L
            first.hasNext.shouldBeTrue()

            val second = CursorPaginationRepository.findCursorPage(pageSize = 2, cursor = first.nextCursor)
            second.content.map(CursorRecord::id) shouldBeEqualTo listOf(7L, 20L)
            second.nextCursor.shouldBeNull()
            second.hasNext.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `커서 페이지는 DESC와 null placement sort를 방향별로 처리한다`(testDB: TestDB) {
        withTables(testDB, CursorPaginationTable) {
            seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))

            listOf(
                SortOrder.DESC,
                SortOrder.DESC_NULLS_FIRST,
                SortOrder.DESC_NULLS_LAST,
            ).forEach { sortOrder ->
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2, sortOrder = sortOrder)
                page.content.map(CursorRecord::id) shouldBeEqualTo listOf(20L, 7L)
                page.nextCursor shouldBeEqualTo 7L
                page.hasNext.shouldBeTrue()
            }

            listOf(
                SortOrder.ASC,
                SortOrder.ASC_NULLS_FIRST,
                SortOrder.ASC_NULLS_LAST,
            ).forEach { sortOrder ->
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2, sortOrder = sortOrder)
                page.content.map(CursorRecord::id) shouldBeEqualTo listOf(1L, 3L)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `predicate는 cursor boundary와 AND로 결합된다`(testDB: TestDB) {
        withTables(testDB, CursorPaginationTable) {
            seed(CursorRecord(1, false), CursorRecord(3, true), CursorRecord(7, false), CursorRecord(20, true))

            val page = CursorPaginationRepository.findCursorPage(pageSize = 10) {
                CursorPaginationTable.active eq true
            }

            page.content.map(CursorRecord::id) shouldBeEqualTo listOf(3L, 20L)
            page.hasNext.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `pageSize는 1 이상 10000 이하로 제한된다`(testDB: TestDB) {
        withTables(testDB, CursorPaginationTable) {
            assertFailsWith<IllegalArgumentException> {
                CursorPaginationRepository.findCursorPage(pageSize = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                CursorPaginationRepository.findCursorPage(pageSize = -1)
            }
            assertFailsWith<IllegalArgumentException> {
                CursorPaginationRepository.findCursorPage(pageSize = 10_001)
            }
        }
    }

    @Test
    fun `커서 행 삭제와 앞뒤 삽입은 별도 커밋 트랜잭션에서 중복 없이 진행된다`() {
        val database = Database.connect(
            url = "jdbc:h2:mem:cursor-mutation-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        val connectionIds = mutableListOf<Int>()

        try {
            val firstCursor = transaction(database) {
                SchemaUtils.create(CursorPaginationTable)
                seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))
                connectionIds += System.identityHashCode(connection)
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2)
                commit()
                page.nextCursor
            }

            transaction(database) {
                connectionIds += System.identityHashCode(connection)
                CursorPaginationTable.deleteWhere { CursorPaginationTable.id eq 3L }
                CursorPaginationRepository.insert(CursorRecord(2, true))
                CursorPaginationRepository.insert(CursorRecord(5, true))
                commit()
            }

            val nextPage = transaction(database) {
                connectionIds += System.identityHashCode(connection)
                val page = CursorPaginationRepository.findCursorPage(pageSize = 2, cursor = firstCursor)
                commit()
                page
            }

            nextPage.content.map(CursorRecord::id) shouldBeEqualTo listOf(5L, 7L)
            nextPage.nextCursor shouldBeEqualTo 7L
            nextPage.hasNext.shouldBeTrue()
            assertTrue(connectionIds.distinct().size >= 2) {
                "cursor mutation checks must use at least two physical JDBC connections"
            }
        } finally {
            transaction(database) {
                SchemaUtils.drop(CursorPaginationTable)
                commit()
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `커서 predicate는 앞에 삽입된 불일치 행을 노출하지 않는다`(testDB: TestDB) {
        withTables(testDB, CursorPaginationTable) {
            seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, false), CursorRecord(20, true))

            val first = CursorPaginationRepository.findCursorPage(pageSize = 1) {
                CursorPaginationTable.active eq true
            }
            CursorPaginationRepository.insert(CursorRecord(2, false))

            val next = CursorPaginationRepository.findCursorPage(pageSize = 10, cursor = first.nextCursor) {
                CursorPaginationTable.active eq true
            }
            next.content.map(CursorRecord::id) shouldBeEqualTo listOf(3L, 20L)
            next.content.map(CursorRecord::id).contains(2L).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `커서 조회는 count 없이 한 번의 bounded SELECT를 실행한다`(testDB: TestDB) {
        Assumptions.assumeTrue(testDB == TestDB.H2)
        withTables(testDB, CursorPaginationTable) {
            seed(CursorRecord(1, true), CursorRecord(3, true), CursorRecord(7, true), CursorRecord(20, true))
            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })

            CursorPaginationRepository.findCursorPage(pageSize = 2, cursor = 1L) {
                CursorPaginationTable.active eq true
            }

            val selectStatements = sqlStatements.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
            selectStatements.size shouldBeEqualTo 1
            sqlStatements.none { it.contains("count(", ignoreCase = true) }.shouldBeTrue()
            val sql = selectStatements.single().lowercase()
            sql.contains("> ").shouldBeTrue()
            sql.contains("active").shouldBeTrue()
            sql.contains("order by").shouldBeTrue()
            sql.contains("limit").shouldBeTrue()
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.seed(vararg records: CursorRecord) {
        records.forEach(CursorPaginationRepository::insert)
    }
}
