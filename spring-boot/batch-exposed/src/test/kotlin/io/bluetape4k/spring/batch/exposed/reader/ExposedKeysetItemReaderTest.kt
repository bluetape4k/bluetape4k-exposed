package io.bluetape4k.spring.batch.exposed.reader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.spring.batch.exposed.AbstractExposedBatchTest
import io.bluetape4k.spring.batch.exposed.SourceRecord
import io.bluetape4k.spring.batch.exposed.SourceTable
import io.bluetape4k.spring.batch.exposed.insertTestData
import io.bluetape4k.spring.batch.exposed.partition.ExposedRangePartitioner
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.batch.infrastructure.item.ExecutionContext

class ExposedKeysetItemReaderTest : AbstractExposedBatchTest() {

    private object DuplicateKeySourceTable : LongIdTable("duplicate_key_source") {
        val groupKey = long("group_key")
        val name = varchar("name", 32)

        init {
            index("idx_duplicate_key_source_cursor", isUnique = false, groupKey, id)
        }
    }

    private class SelectLog : SqlLogger {
        val entries = mutableListOf<String>()

        override fun log(context: StatementContext, transaction: Transaction) {
            context.sql(transaction)
                .takeIf { it.startsWith("SELECT", ignoreCase = true) }
                ?.let(entries::add)
        }
    }

    private fun createReader(testDB: TestDB): ExposedKeysetItemReader<SourceRecord> =
        ExposedKeysetItemReader.forEntityId(
            table = SourceTable,
            pageSize = 10,
            rowMapper = { row ->
                SourceRecord(
                    id = row[SourceTable.id].value,
                    name = row[SourceTable.name],
                    value = row[SourceTable.value],
                )
            },
            database = testDB.db,
        )

    private fun createDuplicateKeyReader(testDB: TestDB): ExposedKeysetItemReader<String> =
        ExposedKeysetItemReader.forColumnWithEntityIdTieBreaker(
            database = testDB.db,
            pageSize = 2,
            column = DuplicateKeySourceTable.groupKey,
            table = DuplicateKeySourceTable,
            rowMapper = { it[DuplicateKeySourceTable.name] },
        )

    private fun createUnsafeDuplicateKeyReader(testDB: TestDB): ExposedKeysetItemReader<String> =
        ExposedKeysetItemReader(
            database = testDB.db,
            pageSize = 2,
            column = DuplicateKeySourceTable.groupKey,
            table = DuplicateKeySourceTable,
            rowMapper = { it[DuplicateKeySourceTable.name] },
        )

    private fun insertDuplicateKeyRows() {
        listOf(
            1L to "a",
            1L to "b",
            1L to "c",
            2L to "d",
        ).forEach { (groupKey, name) ->
            DuplicateKeySourceTable.insert {
                it[DuplicateKeySourceTable.groupKey] = groupKey
                it[DuplicateKeySourceTable.name] = name
            }
        }
    }

    private fun duplicateKeyContext(): ExecutionContext = ExecutionContext().apply {
        putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
        putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 2L)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `중복 key가 page 경계를 넘어도 모든 행을 한 번씩 읽는다`(testDB: TestDB) {
        withTables(testDB, DuplicateKeySourceTable) {
            insertDuplicateKeyRows()

            val reader = createDuplicateKeyReader(testDB)
            reader.open(duplicateKeyContext())

            val results = generateSequence { reader.read() }.toList()

            results shouldBeEqualTo listOf("a", "b", "c", "d")
            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `고유하지 않은 single-column cursor는 조용히 행을 누락하지 않고 실패한다`(testDB: TestDB) {
        withTables(testDB, DuplicateKeySourceTable) {
            insertDuplicateKeyRows()
            val reader = createUnsafeDuplicateKeyReader(testDB)
            reader.open(duplicateKeyContext())

            val error = assertFailsWith<IllegalStateException> {
                reader.read()
            }

            error.message shouldBeEqualTo
                "Keyset column must be strictly unique; use forColumnWithEntityIdTieBreaker for duplicate keys"
            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `중복 key 중간 checkpoint에서 재시작해도 남은 행을 정확히 읽는다`(testDB: TestDB) {
        withTables(testDB, DuplicateKeySourceTable) {
            insertDuplicateKeyRows()
            val context = duplicateKeyContext()
            val reader = createDuplicateKeyReader(testDB)
            reader.open(context)

            reader.read() shouldBeEqualTo "a"
            reader.read() shouldBeEqualTo "b"
            reader.update(context)
            reader.close()

            val restartReader = createDuplicateKeyReader(testDB)
            restartReader.open(context)
            val remaining = generateSequence { restartReader.read() }.toList()

            remaining shouldBeEqualTo listOf("c", "d")
            restartReader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `복합 cursor는 tie-breaker 없는 기존 checkpoint를 거부한다`(testDB: TestDB) {
        withTables(testDB, DuplicateKeySourceTable) {
            val legacyContext = duplicateKeyContext().apply {
                putLong("lastKey", 1L)
            }

            val error = assertFailsWith<IllegalStateException> {
                createDuplicateKeyReader(testDB).open(legacyContext)
            }

            error.message shouldBeEqualTo "Composite keyset checkpoint requires lastTieBreaker"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `복합 cursor SQL은 group_key와 id 순서로 정렬하고 offset을 사용하지 않는다`(testDB: TestDB) {
        val selectLog = SelectLog()
        withTables(
            testDB,
            DuplicateKeySourceTable,
            configure = { sqlLogger = selectLog },
        ) {
            insertDuplicateKeyRows()
            val reader = createDuplicateKeyReader(testDB)
            reader.open(duplicateKeyContext())

            reader.read()

            val sql = selectLog.entries.single().lowercase()
            val orderBy = sql.substringAfter(" order by ")
            val groupKeyIndex = orderBy.indexOf("group_key")
            (groupKeyIndex >= 0).shouldBeTrue()
            orderBy.indexOf("id").let { idIndex ->
                (idIndex >= 0).shouldBeTrue()
                (groupKeyIndex < idIndex).shouldBeTrue()
            }
            sql.contains(" offset ").shouldBeFalse()
            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Spring lifecycle을 우회해도 pageSize overflow를 거부한다`(testDB: TestDB) {
        withBatchTables(testDB) {
            val reader = ExposedKeysetItemReader.forEntityId(
                table = SourceTable,
                pageSize = Int.MAX_VALUE,
                rowMapper = { it[SourceTable.id].value },
                database = testDB.db,
            )
            reader.open(ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 1L)
            })

            val error = assertFailsWith<IllegalArgumentException> {
                reader.read()
            }

            error.message shouldBeEqualTo "pageSize must be less than Int.MAX_VALUE"
            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `정상적으로 모든 레코드를 keyset 페이징으로 읽기`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(25)

            val reader = createReader(testDB)
            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 25L)
            }
            reader.open(context)

            val results = mutableListOf<SourceRecord>()
            var item = reader.read()
            while (item != null) {
                results.add(item)
                item = reader.read()
            }

            results shouldHaveSize 25
            results.first().id shouldBeEqualTo 1L
            results.last().id shouldBeEqualTo 25L

            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `빈 파티션에서 즉시 null 반환`(testDB: TestDB) {
        withBatchTables(testDB) {
            val reader = createReader(testDB)
            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 100L)
            }
            reader.open(context)
            reader.read().shouldBeNull()
            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `restart 시 lastKey부터 이어서 읽기`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(50)

            val reader = createReader(testDB)
            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 50L)
            }
            reader.open(context)

            repeat(15) { reader.read().shouldNotBeNull() }
            reader.update(context)
            reader.close()

            val restartReader = createReader(testDB)
            restartReader.open(context)

            val remaining = mutableListOf<SourceRecord>()
            var item = restartReader.read()
            while (item != null) {
                remaining.add(item)
                item = restartReader.read()
            }

            remaining shouldHaveSize 35
            remaining.first().id shouldBeEqualTo 16L

            restartReader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `파티션 범위 내 데이터만 읽기`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(100)

            val reader = createReader(testDB)
            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 21L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 40L)
            }
            reader.open(context)

            val results = mutableListOf<SourceRecord>()
            var item = reader.read()
            while (item != null) {
                results.add(item)
                item = reader.read()
            }

            results shouldHaveSize 20
            results.first().id shouldBeEqualTo 21L
            results.last().id shouldBeEqualTo 40L

            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `additionalCondition으로 value 필터링`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(50)

            // value >= 25인 레코드만 읽기 (item-25 ~ item-50)
            val reader = ExposedKeysetItemReader.forEntityId(
                table = SourceTable,
                pageSize = 10,
                rowMapper = { row ->
                    SourceRecord(
                        id = row[SourceTable.id].value,
                        name = row[SourceTable.name],
                        value = row[SourceTable.value],
                    )
                },
                additionalCondition = { SourceTable.value greaterEq 25 },
                database = testDB.db,
            )

            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 50L)
            }
            reader.open(context)

            val results = mutableListOf<SourceRecord>()
            var item = reader.read()
            while (item != null) {
                results.add(item)
                item = reader.read()
            }

            results shouldHaveSize 26  // value 25..50
            results.all { it.value >= 25 }.shouldBeTrue()

            reader.close()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `close 후 재사용 시 상태가 초기화된다`(testDB: TestDB) {
        withBatchTables(testDB) {
            insertTestData(10)

            val reader = createReader(testDB)
            val context = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 10L)
            }
            reader.open(context)

            // 일부만 읽고 close
            repeat(5) { reader.read().shouldNotBeNull() }
            reader.close()

            // 다시 open 시 처음부터 읽어야 함
            val newContext = ExecutionContext().apply {
                putLong(ExposedRangePartitioner.PARTITION_MIN_ID, 1L)
                putLong(ExposedRangePartitioner.PARTITION_MAX_ID, 10L)
            }
            reader.open(newContext)

            val results = mutableListOf<SourceRecord>()
            var item = reader.read()
            while (item != null) {
                results.add(item)
                item = reader.read()
            }

            results shouldHaveSize 10
            results.first().id shouldBeEqualTo 1L

            reader.close()
        }
    }
}
