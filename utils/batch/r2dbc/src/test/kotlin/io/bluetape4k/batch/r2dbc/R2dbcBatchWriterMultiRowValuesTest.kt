package io.bluetape4k.batch.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [ExposedR2dbcBatchWriter]의 Exposed 1.5.0 multi-row VALUES 선택 동작을 검증한다. */
class R2dbcBatchWriterMultiRowValuesTest : AbstractBatchR2dbcTest() {

    companion object {
        /** 승인된 H2/PostgreSQL 계약만 검증한다. 다른 방언의 생성 키 계약은 미검증이다. */
        @JvmStatic
        fun multiRowDialects() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    private object WriterTable : LongIdTable("r2dbc_batch_writer_multi_rows") {
        val sourceName = varchar("source_name", 80).uniqueIndex()
        val transformedValue = integer("transformed_value")
        val note = varchar("note", 80).nullable()
    }

    private data class TargetRecord(
        val sourceName: String,
        val transformedValue: Int,
        val note: String? = null,
    ): java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `multi-row writer는 nullable 컬럼을 포함한 하나의 VALUES SQL을 실행한다`(testDB: TestDB) = runSuspendIO {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(db(testDB), useMultiRowValues = true)
            val items = listOf(
                TargetRecord("multi-1", 1),
                TargetRecord("multi-2", 2, "note"),
            )

            writer.write(items)

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 1
            inserts.single().argumentCount shouldBeEqualTo 6
            Regex("\\)\\s*,\\s*\\(").containsMatchIn(inserts.single().sql).shouldBeTrue()
            WriterTable.selectAll().count() shouldBeEqualTo 2L
            WriterTable.selectAll().map { it[WriterTable.note] }.toList()
                .shouldBeEqualTo(listOf(null, "note"))
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `single row multi-row writer는 nullable 값을 저장한다`(testDB: TestDB) = runSuspendIO {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(db(testDB), useMultiRowValues = true)

            writer.write(listOf(TargetRecord("single", 1, "note")))

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 1
            inserts.single().argumentCount shouldBeEqualTo 3
            Regex("\\)\\s*,\\s*\\(").containsMatchIn(inserts.single().sql).not().shouldBeTrue()
            WriterTable.selectAll().map { it[WriterTable.note] }.single() shouldBeEqualTo "note"
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `기존 생성자는 행별 batch 경로를 유지한다`(testDB: TestDB) = runSuspendIO {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(db(testDB))

            writer.write(
                listOf(
                    TargetRecord("legacy-1", 1),
                    TargetRecord("legacy-2", 2),
                )
            )

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 2
            inserts.map(InsertLog.Entry::argumentCount) shouldBeEqualTo listOf(3, 3)
            WriterTable.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `빈 입력은 multi-row writer에서 DB와 바인더를 건드리지 않는다`(testDB: TestDB) = runSuspendIO {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            var bound = 0
            val writer = ExposedR2dbcBatchWriter(
                database = db(testDB),
                table = WriterTable,
                useMultiRowValues = true,
            ) { item: TargetRecord ->
                bound++
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }

            writer.write(emptyList())

            bound shouldBeEqualTo 0
            observer.entries.filter { it.sql.isInsert() } shouldHaveSize 0
            WriterTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `행 수 추정 한도 초과는 바인더와 트랜잭션 전에 거부한다`(testDB: TestDB) = runSuspendIO {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val maxRows = 65_535 / WriterTable.columns.size
            val boundaryItems = (1..maxRows).map { TargetRecord("boundary-$it", it) }
            val boundaryWriter = makeWriter(db(testDB), useMultiRowValues = true)
            boundaryWriter.write(boundaryItems)
            WriterTable.selectAll().count() shouldBeEqualTo maxRows.toLong()

            var oversizedBound = 0
            val oversizedWriter = ExposedR2dbcBatchWriter(
                database = db(testDB),
                table = WriterTable,
                useMultiRowValues = true,
            ) { item: TargetRecord ->
                oversizedBound++
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                oversizedWriter.write(boundaryItems + TargetRecord("secret-payload", maxRows + 1))
            }

            oversizedBound shouldBeEqualTo 0
            failure.message.orEmpty().contains("secret-payload").not().shouldBeTrue()
            observer.entries.filter { it.sql.isInsert() } shouldHaveSize 1
            WriterTable.selectAll().count() shouldBeEqualTo maxRows.toLong()
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `실패한 multi-row write는 자체 트랜잭션을 롤백하고 이전 성공을 보존한다`(testDB: TestDB) = runSuspendIO {
        withWriterTables(testDB, InsertLog()) {
            val writer = makeWriter(db(testDB), useMultiRowValues = true)
            writer.write(listOf(TargetRecord("prior", 1)))

            val failure = assertFailsWith<Exception> {
                writer.write(
                    listOf(
                        TargetRecord("new-1", 10),
                        TargetRecord("prior", 11),
                        TargetRecord("new-2", 12),
                    )
                )
            }

            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<io.r2dbc.spi.R2dbcException>()
                .any { it.sqlState == "23505" }.shouldBeTrue()
            WriterTable.selectAll().count() shouldBeEqualTo 1L
            val names = WriterTable.selectAll().map { it[WriterTable.sourceName] }.toList()
            names shouldBeEqualTo listOf("prior")
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `바인더 진입 시 취소하면 R2DBC write가 취소를 전파하고 INSERT하지 않는다`(testDB: TestDB) = runSuspendIO {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var request: Deferred<Unit>
        var bindCount = 0

        try {
            withWriterTables(testDB, InsertLog()) {
                commit()
                val writer = ExposedR2dbcBatchWriter(
                    database = db(testDB),
                    table = WriterTable,
                    useMultiRowValues = true,
                ) { item: TargetRecord ->
                    bindCount++
                    this[WriterTable.sourceName] = item.sourceName
                    this[WriterTable.transformedValue] = item.transformedValue
                    this[WriterTable.note] = item.note
                    if (entered.complete(Unit)) {
                        release.await(10, TimeUnit.SECONDS).shouldBeTrue()
                        request.ensureActive()
                    }
                }

                request = scope.async(start = CoroutineStart.LAZY) {
                    writer.write(listOf(TargetRecord("cancelled", 1)))
                }
                request.start()
                withTimeout(10_000) { entered.await() }
                request.cancel()
                release.countDown()

                assertFailsWith<CancellationException> { request.await() }
                withTimeout(10_000) { request.join() }
                bindCount shouldBeEqualTo 1
                WriterTable.selectAll().count() shouldBeEqualTo 0L
            }
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private fun makeWriter(
        database: R2dbcDatabase,
        useMultiRowValues: Boolean = false,
    ): ExposedR2dbcBatchWriter<TargetRecord> {
        return if (useMultiRowValues) {
            ExposedR2dbcBatchWriter(
                database = database,
                table = WriterTable,
                useMultiRowValues = true,
            ) { item ->
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
        } else {
            ExposedR2dbcBatchWriter(
                database = database,
                table = WriterTable,
            ) { item ->
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
        }
    }

    private fun db(testDB: TestDB): R2dbcDatabase = checkNotNull(testDB.db) {
        "testDB.db must be initialized for $testDB"
    }

    private suspend fun withWriterTables(
        testDB: TestDB,
        observer: InsertLog,
        statement: suspend R2dbcTransaction.(TestDB) -> Unit,
    ) {
        withTables(
            testDB,
            WriterTable,
            configure = { sqlLogger = observer },
            statement = statement,
        )
    }

    private class InsertLog : SqlLogger {
        val entries = mutableListOf<Entry>()

        class Entry(val sql: String, val argumentCount: Int)

        override fun log(context: StatementContext, transaction: Transaction) {
            val sql = context.sql(transaction)
            if (sql.trimStart().startsWith("INSERT", ignoreCase = true)) {
                entries += Entry(sql, context.args.count())
            }
        }
    }

    private fun String.isInsert(): Boolean = trimStart().startsWith("INSERT", ignoreCase = true)
}
