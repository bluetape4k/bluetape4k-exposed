package io.bluetape4k.spring.batch.exposed.writer

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.repository.support.ResourcelessJobRepository
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/** 실제 Spring 청크 트랜잭션과 Exposed SQL 계약을 함께 검증한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedItemWriterMultiRowValuesTest {

    companion object {
        @JvmStatic
        fun multiRowDialects() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    private object Rows : Table("spring_batch_multi_rows") {
        val id = long("id").autoIncrement()
        val name = varchar("name", 80).uniqueIndex()
        val note = varchar("note", 80).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private data class Row(val name: String, val note: String? = null) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    @Test
    fun `multi-row opt-in 생성자는 기존 JVM 생성자와 함께 제공한다`() {
        val signatures = ExposedItemWriter::class.java.constructors.map { it.parameterTypes.toList() }
        signatures.find { it == listOf(Table::class.java, Function2::class.java) }.shouldNotBeNull()
        signatures.find {
            it == listOf(Table::class.java, Boolean::class.javaPrimitiveType, Function2::class.java)
        }.shouldNotBeNull()
    }

    @Test
    fun `빈 청크는 기존 경로와 opt-in 모두 트랜잭션 없이 no-op이다`() {
        var calls = 0
        val bind: BatchInsertStatement.(Row) -> Unit = { calls++ }
        ExposedItemWriter(Rows, bind).write(Chunk(emptyList<Row>()))
        ExposedItemWriter(Rows, false, bind).write(Chunk(emptyList<Row>()))
        ExposedItemWriter(Rows, true, bind).write(Chunk(emptyList<Row>()))
        calls shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `기본과 false는 행별 SQL을 유지하고 true만 multi-row SQL을 사용한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, log ->
            val input = listOf(Row("one"), Row("two", "nullable-note"))
            listOf(null, false, true).forEach { mode ->
                val order = mutableListOf<String>()
                tx.executeWithoutResult {
                    val bind: BatchInsertStatement.(Row) -> Unit = { row ->
                        order += row.name
                        this[Rows.name] = "$mode-${row.name}"
                        this[Rows.note] = row.note
                    }
                    log.entries.clear()
                    val writer = if (mode == null) {
                        ExposedItemWriter(Rows, bind)
                    } else {
                        ExposedItemWriter(Rows, mode, bind)
                    }
                    writer.write(Chunk(input))

                    order shouldBeEqualTo listOf("one", "two")
                    log.entries shouldHaveSize if (mode == true) 1 else 2
                    log.entries.forEach { entry ->
                        entry.generatedValues.shouldBeFalse()
                        Regex("\\)\\s*,\\s*\\(").containsMatchIn(entry.sql) shouldBeEqualTo (mode == true)
                        entry.argumentCount shouldBeEqualTo if (mode == true) 4 else 2
                    }
                }
            }
            tx.executeWithoutResult {
                Rows.selectAll().orderBy(Rows.id).map { it[Rows.note] } shouldBeEqualTo
                    listOf(null, "nullable-note", null, "nullable-note", null, "nullable-note")
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `단일 행 opt-in은 생성 키를 요청하지 않는다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, log ->
            tx.executeWithoutResult {
                log.entries.clear()
                writer().write(Chunk(listOf(Row("single"))))
                log.entries shouldHaveSize 1
                log.entries.single().generatedValues.shouldBeFalse()
                log.entries.single().argumentCount shouldBeEqualTo 2
                Rows.selectAll().count() shouldBeEqualTo 1L
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `추정 경계는 성공하고 초과 청크는 바인더와 SQL 전에 거부한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, log ->
            val maxRows = 65_535 / Rows.columns.size
            tx.executeWithoutResult { writer().write(Chunk((1..maxRows).map { Row("boundary-$it") })) }
            var bound = 0
            val oversized = ExposedItemWriter<Row>(Rows, true) { bound++ }
            tx.executeWithoutResult {
                log.entries.clear()
                val failure = assertFailsWith<IllegalArgumentException> {
                    oversized.write(Chunk(List(maxRows + 1) { Row("secret-payload") }))
                }
                bound shouldBeEqualTo 0
                log.entries shouldHaveSize 0
                failure.message.orEmpty().contains("secret-payload").shouldBeFalse()
                Rows.selectAll().count() shouldBeEqualTo maxRows.toLong()
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `Spring rollback-only는 성공한 writer 쓰기까지 롤백한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, _ ->
            tx.executeWithoutResult {
                writer().write(Chunk(listOf(Row("rollback-only"))))
                it.setRollbackOnly()
            }
            tx.executeWithoutResult { Rows.selectAll().count() shouldBeEqualTo 0L }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `실제 청크 step은 nullable 입력을 순서대로 commit한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, _ ->
            val execution = executeStep(tx, listOf(Row("one"), Row("two", "note"), Row("three")), writer())
            execution.status shouldBeEqualTo BatchStatus.COMPLETED
            execution.writeCount shouldBeEqualTo 3L
            tx.executeWithoutResult {
                Rows.selectAll().orderBy(Rows.id).map { it[Rows.name] } shouldBeEqualTo listOf("one", "two", "three")
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `청크 중복 실패는 해당 청크 전체를 롤백하고 이전 commit을 보존한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, _ ->
            val execution = executeStep(tx, listOf(Row("one"), Row("two"), Row("three"), Row("one")), writer())
            execution.status shouldBeEqualTo BatchStatus.FAILED
            execution.rollbackCount shouldBeEqualTo 1L
            execution.failureExceptions.any { failure ->
                generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "23505" }
            }.shouldBeTrue()
            tx.executeWithoutResult {
                Rows.selectAll().orderBy(Rows.id).map { it[Rows.name] } shouldBeEqualTo listOf("one", "two")
            }
        }
    }

    @ParameterizedTest
    @MethodSource("multiRowDialects")
    fun `writer 실행 후 청크 실패도 삽입 결과를 롤백한다`(testDB: TestDB) {
        withSpringTables(testDB) { tx, _ ->
            val delegate = writer()
            val expectedFailure = IllegalStateException("after-write failure")
            val execution = executeStep(tx, listOf(Row("one"), Row("two"), Row("three")), ItemWriter { chunk ->
                delegate.write(chunk)
                if (chunk.items.first().name == "three") throw expectedFailure
            })
            execution.status shouldBeEqualTo BatchStatus.FAILED
            execution.failureExceptions.any { failure ->
                generateSequence(failure) { it.cause }.any { it === expectedFailure }
            }.shouldBeTrue()
            tx.executeWithoutResult {
                Rows.selectAll().orderBy(Rows.id).map { it[Rows.name] } shouldBeEqualTo listOf("one", "two")
            }
        }
    }

    private fun writer(): ExposedItemWriter<Row> = ExposedItemWriter(Rows, useMultiRowValues = true) { row ->
        this[Rows.name] = row.name
        this[Rows.note] = row.note
    }

    /** 메타데이터 저장만 생략하고 실제 청크 처리와 JDBC 트랜잭션은 사용한다. */
    private fun executeStep(tx: TransactionTemplate, rows: List<Row>, writer: ItemWriter<Row>): StepExecution {
        val repository = ResourcelessJobRepository()
        val parameters = JobParameters()
        val instance = repository.createJobInstance("multi-row-job", parameters)
        val job = repository.createJobExecution(instance, parameters, ExecutionContext())
        val execution = repository.createStepExecution("multi-row-step", job)
        val input = rows.iterator()
        val step = StepBuilder("multi-row-step", repository)
            .chunk<Row, Row>(2)
            .transactionManager(checkNotNull(tx.transactionManager))
            .reader(ItemReader { if (input.hasNext()) input.next() else null })
            .writer(writer)
            .build()
        step.execute(execution)
        return execution
    }

    /** TestDB가 소유한 DB에 고유 테이블만 생성하며 별도 Spring 애플리케이션 스캔은 하지 않는다. */
    private fun withSpringTables(testDB: TestDB, block: (TransactionTemplate, InsertLog) -> Unit) {
        testDB.beforeConnection()
        val source = DriverManagerDataSource(testDB.connection(), testDB.user, testDB.pass).apply {
            setDriverClassName(testDB.driver)
        }
        val log = InsertLog()
        val manager = SpringTransactionManager(source, DatabaseConfig { sqlLogger = log }, false)
        val tx = TransactionTemplate(manager)
        tx.executeWithoutResult { SchemaUtils.create(Rows) }
        try {
            block(tx, log)
        } finally {
            tx.executeWithoutResult { SchemaUtils.drop(Rows) }
        }
    }

    /** Exposed StatementContext 수이며 JDBC 왕복 횟수나 성능 배수를 의미하지 않는다. */
    private class InsertLog : SqlLogger {
        class Entry(val sql: String, val argumentCount: Int, val generatedValues: Boolean)
        val entries = mutableListOf<Entry>()

        override fun log(context: StatementContext, transaction: Transaction) {
            val statement = context.statement
            if (statement is BatchInsertStatement) {
                entries += Entry(context.sql(transaction), context.args.count(), statement.shouldReturnGeneratedValues)
            }
        }
    }
}
