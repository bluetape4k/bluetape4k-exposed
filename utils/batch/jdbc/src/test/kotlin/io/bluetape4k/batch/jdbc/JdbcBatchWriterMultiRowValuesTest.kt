package io.bluetape4k.batch.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** [ExposedJdbcBatchWriter]의 Exposed 1.5.0 multi-row VALUES 선택 동작을 검증한다. */
class JdbcBatchWriterMultiRowValuesTest : AbstractBatchJdbcTest() {

    companion object {
        /** 승인된 H2/PostgreSQL 계약만 검증한다. 다른 방언의 생성 키 계약은 미검증이다. */
        @JvmStatic
        fun multiRowDialects() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    private object WriterTable : LongIdTable("batch_writer_multi_rows") {
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
    fun `multi-row writer는 nullable 컬럼을 포함한 하나의 VALUES SQL을 실행한다`(testDB: TestDB) {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(testDB, useMultiRowValues = true)
            val items = listOf(
                TargetRecord("multi-1", 1),
                TargetRecord("multi-2", 2, "note"),
            )

            runSuspendIO { writer.write(items) }

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 1
            inserts.single().argumentCount shouldBeEqualTo 6
            Regex("\\)\\s*,\\s*\\(").containsMatchIn(inserts.single().sql).shouldBeTrue()
            countTarget(testDB) shouldBeEqualTo 2L
            WriterTable.selectAll().map { it[WriterTable.note] }
                .shouldBeEqualTo(listOf(null, "note"))
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `single row multi-row writer는 nullable 값을 저장한다`(testDB: TestDB) {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(testDB, useMultiRowValues = true)

            runSuspendIO { writer.write(listOf(TargetRecord("single", 1, "note"))) }

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 1
            inserts.single().argumentCount shouldBeEqualTo 3
            Regex("\\)\\s*,\\s*\\(").containsMatchIn(inserts.single().sql).not().shouldBeTrue()
            WriterTable.selectAll().single()[WriterTable.note] shouldBeEqualTo "note"
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `기존 생성자는 행별 batch 경로를 유지한다`(testDB: TestDB) {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val writer = makeWriter(testDB)

            runSuspendIO {
                writer.write(
                    listOf(
                        TargetRecord("legacy-1", 1),
                        TargetRecord("legacy-2", 2),
                    )
                )
            }

            val inserts = observer.entries.filter { it.sql.isInsert() }
            inserts shouldHaveSize 2
            inserts.map(InsertLog.Entry::argumentCount) shouldBeEqualTo listOf(3, 3)
            countTarget(testDB) shouldBeEqualTo 2L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `빈 입력은 multi-row writer에서 DB와 바인더를 건드리지 않는다`(testDB: TestDB) {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            var bound = 0
            val writer = ExposedJdbcBatchWriter(
                database = database(testDB),
                table = WriterTable,
                useMultiRowValues = true,
            ) { item: TargetRecord ->
                bound++
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }

            runSuspendIO { writer.write(emptyList()) }

            bound shouldBeEqualTo 0
            observer.entries.filter { it.sql.isInsert() } shouldHaveSize 0
            countTarget(testDB) shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `행 수 추정 한도 초과는 바인더와 트랜잭션 전에 거부한다`(testDB: TestDB) {
        val observer = InsertLog()
        withWriterTables(testDB, observer) {
            val maxRows = 65_535 / WriterTable.columns.size
            val boundaryItems = (1..maxRows).map { TargetRecord("boundary-$it", it) }
            val boundaryWriter = makeWriter(testDB, useMultiRowValues = true)
            runSuspendIO { boundaryWriter.write(boundaryItems) }
            countTarget(testDB) shouldBeEqualTo maxRows.toLong()

            var oversizedBound = 0
            val oversizedWriter = ExposedJdbcBatchWriter(
                database = database(testDB),
                table = WriterTable,
                useMultiRowValues = true,
            ) { item: TargetRecord ->
                oversizedBound++
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                runSuspendIO {
                    oversizedWriter.write(boundaryItems + TargetRecord("secret-payload", maxRows + 1))
                }
            }

            oversizedBound shouldBeEqualTo 0
            failure.message.orEmpty().contains("secret-payload").not().shouldBeTrue()
            observer.entries.filter { it.sql.isInsert() } shouldHaveSize 1
            countTarget(testDB) shouldBeEqualTo maxRows.toLong()
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `실패한 multi-row write는 자체 트랜잭션을 롤백하고 이전 성공을 보존한다`(testDB: TestDB) {
        withWriterTables(testDB, InsertLog()) {
            val writer = makeWriter(testDB, useMultiRowValues = true)
            runSuspendIO { writer.write(listOf(TargetRecord("prior", 1))) }

            val failure = assertFailsWith<Exception> {
                runSuspendIO {
                    writer.write(
                        listOf(
                            TargetRecord("new-1", 10),
                            TargetRecord("prior", 11),
                            TargetRecord("new-2", 12),
                        )
                    )
                }
            }

            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .any { it.sqlState == "23505" }.shouldBeTrue()
            countTarget(testDB) shouldBeEqualTo 1L
            val names = WriterTable.selectAll().map { it[WriterTable.sourceName] }
            names shouldBeEqualTo listOf("prior")
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `multi-row writer는 PostgreSQL ignore와 H2 미지원을 구분한다`(testDB: TestDB) {
        withWriterTables(testDB, InsertLog()) {
            val writer = makeWriter(testDB, ignore = true, useMultiRowValues = true)
            if (testDB == TestDB.H2) {
                assertFailsWith<org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException> {
                    runSuspendIO { writer.write(listOf(TargetRecord("unsupported", 1))) }
                }
                countTarget(testDB) shouldBeEqualTo 0L
                return@withWriterTables
            }
            runSuspendIO { writer.write(listOf(TargetRecord("prior", 1))) }
            runSuspendIO {
                writer.write(
                    listOf(
                        TargetRecord("new-1", 10),
                        TargetRecord("prior", 11),
                        TargetRecord("new-2", 12, "note"),
                    )
                )
            }

            countTarget(testDB) shouldBeEqualTo 3L
            WriterTable.selectAll().map { it[WriterTable.sourceName] }.toSet()
                .shouldBeEqualTo(setOf("prior", "new-1", "new-2"))
        }
    }

    private fun makeWriter(
        testDB: TestDB,
        ignore: Boolean = false,
        useMultiRowValues: Boolean = false,
    ): ExposedJdbcBatchWriter<TargetRecord> {
        return if (useMultiRowValues) {
            ExposedJdbcBatchWriter(
                database = database(testDB),
                table = WriterTable,
                ignore = ignore,
                useMultiRowValues = true,
            ) { item ->
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
        } else {
            ExposedJdbcBatchWriter(
                database = database(testDB),
                table = WriterTable,
                ignore = ignore,
            ) { item ->
                this[WriterTable.sourceName] = item.sourceName
                this[WriterTable.transformedValue] = item.transformedValue
                this[WriterTable.note] = item.note
            }
        }
    }

    private fun database(testDB: TestDB): Database = checkNotNull(testDB.db) {
        "testDB.db must be initialized for $testDB"
    }

    private fun countTarget(testDB: TestDB): Long =
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(database(testDB)) {
            WriterTable.selectAll().count()
        }

    private fun withWriterTables(
        testDB: TestDB,
        observer: InsertLog,
        statement: JdbcTransaction.(TestDB) -> Unit,
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
