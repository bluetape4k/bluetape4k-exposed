package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class JdbcRepositoryMultiRowValuesTest: AbstractExposedTest() {

    companion object {
        /** 승인된 H2/PostgreSQL 계약만 검증한다. 다른 방언의 생성 키 계약은 미검증이다. */
        @JvmStatic
        fun multiRowDialects() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `native multi-row 부분 충돌의 반환 행 누락을 분리 재현한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            Rows.batchInsert(listOf(Record(name = "dup")), body = bind)
            val input = listOf(Record(name = "new-1"), Record(name = "dup"), Record(name = "new-2"))
            if (testDB == TestDB.H2) {
                assertFailsWith<org.jetbrains.exposed.v1.exceptions.UnsupportedByDialectException> {
                    Rows.batchInsert(input, useMultiRowValues = true, ignore = true, body = bind)
                }
            } else {
                // repository mapper 없이도 upstream이 실제 신규 2행 대신 3행을 반환한다.
                val returned = Rows.batchInsert(input, useMultiRowValues = true, ignore = true, body = bind)
                returned shouldHaveSize 3
                returned.count { it.hasValue(Rows.id) } shouldBeEqualTo 2
                Rows.selectAll().count() shouldBeEqualTo 3L
            }
        }
    }

    private object Rows: LongIdTable("jdbc_repository_multi_rows") {
        val name = varchar("name", 80).uniqueIndex()
        val note = varchar("note", 80).nullable()
    }

    private data class Record(val id: Long = 0, val name: String, val note: String? = null): java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private object Repository: LongJdbcRepository<Record> {
        override val table = Rows
        override fun extractId(entity: Record) = entity.id
        override fun ResultRow.toEntity() =
            Record(this[Rows.id].value, this[Rows.name], this[Rows.note])
    }

    private val bind: BatchInsertStatement.(Record) -> Unit = { row ->
        this[Rows.name] = row.name
        this[Rows.note] = row.note
    }

    private class InsertLog: SqlLogger {
        val entries = mutableListOf<Pair<String, Int>>()
        override fun log(context: StatementContext, transaction: Transaction) {
            val sql = context.sql(transaction)
            if (sql.trimStart().startsWith("INSERT", ignoreCase = true)) {
                entries += sql to context.args.count()
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `기존 positional 호출과 명시적 false는 행별 parameter set을 유지한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            val observer = InsertLog()
            addLogger(observer)
            val old = Repository.batchInsert(listOf(Record(name = "old-1"), Record(name = "old-2")), false, true, bind)
            old shouldHaveSize 2
            observer.entries.map { it.second } shouldBeEqualTo listOf(2, 2)
            observer.entries.clear()

            val saved = Repository.batchInsert(
                sequenceOf(Record(name = "false-1"), Record(name = "false-2")).constrainOnce(),
                useMultiRowValues = false, insertStatement = bind,
            )
            saved shouldHaveSize 2
            observer.entries.map { it.second } shouldBeEqualTo listOf(2, 2)
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `multi-row는 nullable 데이터와 생성 ID 순서를 하나의 parameter set으로 반환한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            val observer = InsertLog()
            addLogger(observer)
            val input = listOf(Record(name = "first"), Record(name = "second", note = "note"))
            val saved = Repository.batchInsert(input, useMultiRowValues = true, insertStatement = bind)

            saved.map { it.name } shouldBeEqualTo input.map { it.name }
            saved.map { it.note } shouldBeEqualTo input.map { it.note }
            saved.all { it.id > 0 }.shouldBeTrue()
            saved.map { it.id }.distinct() shouldHaveSize 2
            observer.entries shouldHaveSize 1
            observer.entries.single().second shouldBeEqualTo 4
            Regex("\\)\\s*,\\s*\\(").containsMatchIn(observer.entries.single().first).shouldBeTrue()
            saved.forEach { Repository.findById(it.id) shouldBeEqualTo it }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `빈 입력은 바인더와 INSERT를 실행하지 않고 단일 Sequence도 지원한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            val observer = InsertLog()
            addLogger(observer)
            Repository.batchInsert(emptyList<Record>(), useMultiRowValues = true) {
                error("empty binder")
            } shouldHaveSize 0
            Repository.batchInsert(emptySequence<Record>(), useMultiRowValues = true) {
                error("empty binder")
            } shouldHaveSize 0
            observer.entries shouldHaveSize 0

            val saved = Repository.batchInsert(
                sequenceOf(Record(name = "single")).constrainOnce(),
                useMultiRowValues = true, insertStatement = bind,
            )
            saved.single().name shouldBeEqualTo "single"
            observer.entries shouldHaveSize 1
            observer.entries.single().second shouldBeEqualTo 2
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `행 수 추정 한도까지 저장하고 초과 입력은 바인더 전에 거부한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            val maxRows = 65_535 / Rows.columns.size
            val input = (1..maxRows).map { Record(name = "row-$it") }
            Repository.batchInsert(input, useMultiRowValues = true, insertStatement = bind) shouldHaveSize maxRows
            Rows.selectAll().count() shouldBeEqualTo maxRows.toLong()

            var bound = 0
            val failure = assertFailsWith<IllegalArgumentException> {
                Repository.batchInsert(input + Record(name = "secret-payload"), useMultiRowValues = true) {
                    bound++
                    bind(it)
                }
            }
            bound shouldBeEqualTo 0
            failure.message.orEmpty().contains("secret-payload").not().shouldBeTrue()
            Rows.selectAll().count() shouldBeEqualTo maxRows.toLong()
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `무한 Sequence는 한도 다음 행까지만 소비하고 선행 쓰기를 보존한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            Repository.batchInsert(listOf(Record(name = "prior")), insertStatement = bind)
            val observer = InsertLog()
            addLogger(observer)
            var consumed = 0
            var bound = 0
            val input = generateSequence { Record(name = "infinite-${++consumed}") }.constrainOnce()
            assertFailsWith<IllegalArgumentException> {
                Repository.batchInsert(input, useMultiRowValues = true) { bound++; bind(it) }
            }
            consumed shouldBeEqualTo 65_535 / Rows.columns.size + 1
            bound shouldBeEqualTo 0
            observer.entries shouldHaveSize 0
            Rows.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `생성 값 요청을 끈 명시 ID 데이터는 별도 SELECT로 확인한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            val input = listOf(Record(101, "explicit-1"), Record(102, "explicit-2", "note"))
            Repository.batchInsert(input, shouldReturnGeneratedValues = false, useMultiRowValues = true) {
                this[Rows.id] = it.id
                bind(it)
            }
            input.forEach { Repository.findById(it.id) shouldBeEqualTo it }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `multi-row와 ignore 조합은 입력 순회와 INSERT 전에 거부한다`(testDB: TestDB) {
        withTables(testDB, Rows) {
            Repository.batchInsert(listOf(Record(name = "dup")), insertStatement = bind)
            val observer = InsertLog()
            addLogger(observer)
            var iterated = 0
            var bound = 0
            val iterable = Iterable<Record> { iterated++; listOf(Record(name = "secret")).iterator() }
            val sequence = Sequence<Record> { iterated++; listOf(Record(name = "secret")).iterator() }
            for (generated in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException> {
                    Repository.batchInsert(iterable, ignore = true, shouldReturnGeneratedValues = generated,
                        useMultiRowValues = true) { bound++; bind(it) }
                }
                assertFailsWith<IllegalArgumentException> {
                    Repository.batchInsert(sequence, ignore = true, shouldReturnGeneratedValues = generated,
                        useMultiRowValues = true) { bound++; bind(it) }
                }
                assertFailsWith<IllegalArgumentException> {
                    Repository.batchInsert(emptyList<Record>(), ignore = true,
                        shouldReturnGeneratedValues = generated, useMultiRowValues = true) { bound++ }
                }
                assertFailsWith<IllegalArgumentException> {
                    Repository.batchInsert(emptySequence<Record>(), ignore = true,
                        shouldReturnGeneratedValues = generated, useMultiRowValues = true) { bound++ }
                }
            }
            iterated shouldBeEqualTo 0
            bound shouldBeEqualTo 0
            observer.entries shouldHaveSize 0
            Rows.selectAll().count() shouldBeEqualTo 1L
        }
    }
}
