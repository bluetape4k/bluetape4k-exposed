package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.UUID

private object R2dbcCursorPaginationTable : IdTable<Long>("r2dbc_cursor_pagination_rows") {
    override val id: Column<EntityID<Long>> = long("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val active = bool("active")
}

private data class R2dbcCursorRecord(
    val id: Long,
    val active: Boolean,
)

private object R2dbcCursorPaginationRepository : LongR2dbcRepository<R2dbcCursorRecord> {
    override val table = R2dbcCursorPaginationTable

    override fun extractId(entity: R2dbcCursorRecord): Long = entity.id

    override suspend fun ResultRow.toEntity(): R2dbcCursorRecord = R2dbcCursorRecord(
        id = this[R2dbcCursorPaginationTable.id].value,
        active = this[R2dbcCursorPaginationTable.active],
    )

    suspend fun insert(record: R2dbcCursorRecord) {
        R2dbcCursorPaginationTable.insert {
            it[R2dbcCursorPaginationTable.id] = record.id
            it[R2dbcCursorPaginationTable.active] = record.active
        }
    }
}

private class BlockingR2dbcCursorPaginationRepository(
    private val mapperEntered: CompletableDeferred<Unit>,
    private val releaseMapper: CompletableDeferred<Unit>,
) : LongR2dbcRepository<R2dbcCursorRecord> {
    override val table = R2dbcCursorPaginationTable

    override fun extractId(entity: R2dbcCursorRecord): Long = entity.id

    override suspend fun ResultRow.toEntity(): R2dbcCursorRecord {
        mapperEntered.complete(Unit)
        releaseMapper.await()
        return R2dbcCursorRecord(
            id = this[R2dbcCursorPaginationTable.id].value,
            active = this[R2dbcCursorPaginationTable.active],
        )
    }
}

class R2dbcRepositoryCursorPaginationTest : AbstractExposedR2dbcTest() {
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend 커서 페이지는 sparse ID를 오름차순으로 이어서 조회한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, R2dbcCursorPaginationTable) {
            seed(
                R2dbcCursorRecord(1, true),
                R2dbcCursorRecord(3, true),
                R2dbcCursorRecord(7, true),
                R2dbcCursorRecord(20, true),
            )

            val first = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2)
            first.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(1L, 3L)
            first.nextCursor shouldBeEqualTo 3L
            first.hasNext.shouldBeTrue()

            val second = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2, cursor = first.nextCursor)
            second.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(7L, 20L)
            second.nextCursor.shouldBeNull()
            second.hasNext.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend 커서 페이지는 DESC와 null placement sort를 방향별로 처리한다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, R2dbcCursorPaginationTable) {
            seed(
                R2dbcCursorRecord(1, true),
                R2dbcCursorRecord(3, true),
                R2dbcCursorRecord(7, true),
                R2dbcCursorRecord(20, true),
            )

            listOf(
                SortOrder.DESC,
                SortOrder.DESC_NULLS_FIRST,
                SortOrder.DESC_NULLS_LAST,
            ).forEach { sortOrder ->
                val page = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2, sortOrder = sortOrder)
                page.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(20L, 7L)
                page.nextCursor shouldBeEqualTo 7L
                page.hasNext.shouldBeTrue()
            }

            listOf(
                SortOrder.ASC,
                SortOrder.ASC_NULLS_FIRST,
                SortOrder.ASC_NULLS_LAST,
            ).forEach { sortOrder ->
                val page = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2, sortOrder = sortOrder)
                page.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(1L, 3L)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend predicate는 cursor boundary와 AND로 결합된다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, R2dbcCursorPaginationTable) {
            seed(
                R2dbcCursorRecord(1, false),
                R2dbcCursorRecord(3, true),
                R2dbcCursorRecord(7, false),
                R2dbcCursorRecord(20, true),
            )

            val page = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 10) {
                R2dbcCursorPaginationTable.active eq true
            }

            page.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(3L, 20L)
            page.hasNext.shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend 커서 predicate는 앞에 삽입된 불일치 행을 노출하지 않는다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, R2dbcCursorPaginationTable) {
            seed(
                R2dbcCursorRecord(1, true),
                R2dbcCursorRecord(3, true),
                R2dbcCursorRecord(7, false),
                R2dbcCursorRecord(20, true),
            )

            val first = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 1) {
                R2dbcCursorPaginationTable.active eq true
            }
            R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(2, false))

            val next = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 10, cursor = first.nextCursor) {
                R2dbcCursorPaginationTable.active eq true
            }
            next.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(3L, 20L)
            next.content.map(R2dbcCursorRecord::id).contains(2L).shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend pageSize는 1 이상 10000 이하로 제한된다`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, R2dbcCursorPaginationTable) {
            assertFailsWith<IllegalArgumentException> {
                R2dbcCursorPaginationRepository.findCursorPage(pageSize = 0)
            }
            assertFailsWith<IllegalArgumentException> {
                R2dbcCursorPaginationRepository.findCursorPage(pageSize = -1)
            }
            assertFailsWith<IllegalArgumentException> {
                R2dbcCursorPaginationRepository.findCursorPage(pageSize = 10_001)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `suspend 커서 조회는 count 없이 한 번의 bounded SELECT를 실행한다`(testDB: TestDB) = runSuspendIO {
        Assumptions.assumeTrue(testDB == TestDB.H2)
        withTables(testDB, R2dbcCursorPaginationTable) {
            seed(
                R2dbcCursorRecord(1, true),
                R2dbcCursorRecord(3, true),
                R2dbcCursorRecord(7, true),
                R2dbcCursorRecord(20, true),
            )
            val sqlStatements = mutableListOf<String>()
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    sqlStatements += context.sql(transaction)
                }
            })

            R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2, cursor = 1L) {
                R2dbcCursorPaginationTable.active eq true
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

    @Test
    fun `suspend 커서 행 삭제와 앞뒤 삽입은 별도 커밋 트랜잭션에서 중복 없이 진행된다`() = runSuspendIO {
        val database = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///r2dbc-cursor-mutation-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
        )
        val connectionIds = mutableListOf<Int>()

        try {
            val firstCursor = suspendTransaction(db = database) {
                SchemaUtils.create(R2dbcCursorPaginationTable)
                seed(
                    R2dbcCursorRecord(1, true),
                    R2dbcCursorRecord(3, true),
                    R2dbcCursorRecord(7, true),
                    R2dbcCursorRecord(20, true),
                )
                connectionIds += System.identityHashCode(connection())
                val page = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2)
                commit()
                page.nextCursor
            }

            suspendTransaction(db = database) {
                connectionIds += System.identityHashCode(connection())
                R2dbcCursorPaginationTable.deleteWhere { R2dbcCursorPaginationTable.id eq 3L }
                R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(2, true))
                R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(5, true))
                commit()
            }

            val nextPage = suspendTransaction(db = database) {
                connectionIds += System.identityHashCode(connection())
                val page = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 2, cursor = firstCursor)
                commit()
                page
            }

            nextPage.content.map(R2dbcCursorRecord::id) shouldBeEqualTo listOf(5L, 7L)
            nextPage.nextCursor shouldBeEqualTo 7L
            nextPage.hasNext.shouldBeTrue()
            connectionIds.distinct().size shouldBeEqualTo 3
        } finally {
            suspendTransaction(db = database) {
                SchemaUtils.drop(R2dbcCursorPaginationTable)
                commit()
            }
        }
    }

    @Test
    fun `suspend mapper 취소는 size one pool 연결을 반환하고 미커밋 쓰기를 롤백한다`() = runSuspendIO {
        val url = "r2dbc:h2:mem:///r2dbc-cursor-cancel-${UUID.randomUUID()};DB_CLOSE_DELAY=-1;"
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(ConnectionFactories.get(url))
                .initialSize(1)
                .maxSize(1)
                .build()
        )
        val database = R2dbcDatabase.connect(
            pool,
            R2dbcDatabaseConfig.Builder().apply { setUrl(url) },
        )
        val mapperEntered = CompletableDeferred<Unit>()
        val releaseMapper = CompletableDeferred<Unit>()
        val blockingRepository = BlockingR2dbcCursorPaginationRepository(mapperEntered, releaseMapper)

        try {
            suspendTransaction(db = database) {
                SchemaUtils.create(R2dbcCursorPaginationTable)
                R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(1, true))
                R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(3, true))
                commit()
            }

            val request = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async(
                start = CoroutineStart.UNDISPATCHED,
            ) {
                suspendTransaction(db = database) {
                    R2dbcCursorPaginationRepository.insert(R2dbcCursorRecord(99, true))
                    blockingRepository.findCursorPage(pageSize = 2)
                }
            }

            mapperEntered.await()
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }

            val idsAfterCancellation = withTimeout(5_000L) {
                suspendTransaction(db = database) {
                    val ids = R2dbcCursorPaginationRepository.findCursorPage(pageSize = 100).content
                        .map(R2dbcCursorRecord::id)
                    commit()
                    ids
                }
            }
            idsAfterCancellation shouldBeEqualTo listOf(1L, 3L)
        } finally {
            releaseMapper.complete(Unit)
            runCatching {
                suspendTransaction(db = database) {
                    SchemaUtils.drop(R2dbcCursorPaginationTable)
                    commit()
                }
            }
            pool.dispose()
        }
    }

    private suspend fun org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction.seed(
        vararg records: R2dbcCursorRecord,
    ) {
        records.forEach { R2dbcCursorPaginationRepository.insert(it) }
    }
}
