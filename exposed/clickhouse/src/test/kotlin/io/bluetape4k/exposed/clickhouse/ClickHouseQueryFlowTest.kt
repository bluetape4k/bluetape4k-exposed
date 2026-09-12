package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeIn
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.clickhouse.engine.Memory
import io.bluetape4k.exposed.clickhouse.types.Date32ColumnType
import io.bluetape4k.exposed.clickhouse.types.chString
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.decimalLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseQueryFlowTest: AbstractClickHouseTest() {

    private object Numbers: Table("system.numbers") {
        val number = long("number")
    }

    private object TenantRows: ClickHouseTable("issue857_tenant_rows") {
        val id = long("id")
        val tenant = chString("tenant")

        override val engine = Memory
    }

    @Test
    fun `nullable 날짜 집계 결과를 트랜잭션 밖에서도 읽을 수 있다`() = runSuspendIO {
        val one = object: Table("system.one") {
            val dummy = long("dummy")
        }
        val nullable = CustomFunction<String?>(
            "nullIf", TextColumnType(), stringLiteral("same"), stringLiteral("same"),
        )
        val date = CustomFunction<LocalDate>("toDate", Date32ColumnType(), stringLiteral("2026-09-08"))
        val count = one.dummy.count()
        val values = queryFlow(
            db,
            query = {
                one.select(nullable, date, count)
            },
            mapper = { listOf(it[nullable], it[date], it[count]) }
        ).toList()

        values shouldBeEqualTo listOf(listOf(null, LocalDate.of(2026, 9, 8), 1L))
    }

    @Test
    fun `중복 선택과 별칭 및 decimal 문자열은 기존 조회와 동일하게 변환한다`() = runSuspendIO {
        val label = stringLiteral("행 값").alias("label")
        val amount = decimalLiteral("123.45".toBigDecimal()).alias("amount")
        val numberAlias = Numbers.number.alias("number_alias")
        val values = queryFlow(
            db,
            query = {
                Numbers.select(Numbers.number, Numbers.number, numberAlias, label, amount).limit(3)
            },
            mapper = {
                listOf(it[Numbers.number], it[numberAlias], it[label], it[amount])
            }
        ).toList()
        
        values shouldBeEqualTo (0L..2L).map { listOf(it, it, "행 값", "123.45".toBigDecimal()) }
    }

    @Test
    fun `바인딩 문자열은 SQL 조건으로 실행되지 않는다`() = runSuspendIO {
        transaction(db) {
            SchemaUtils.create(TenantRows)
            TenantRows.batchInsert(listOf("tenant-a", "tenant-b")) { value ->
                this[TenantRows.id] = if (value == "tenant-a") 1L else 2L
                this[TenantRows.tenant] = value
            }
        }
        try {
            for (input in listOf("tenant-a", "tenant-a' OR 1=1 --")) {
                val values = queryFlow(db, query = {
                    TenantRows
                        .select(TenantRows.tenant)
                        .where { TenantRows.tenant eq input }
                        .orderBy(TenantRows.id)
                }, mapper = { it[TenantRows.tenant] }).toList()
                values shouldBeEqualTo if (input == "tenant-a") listOf("tenant-a") else emptyList()
            }
        } finally {
            transaction(db) {
                SchemaUtils.drop(TenantRows)
            }
        }
    }

    @Test
    fun `수집 전에는 조회하지 않고 수집마다 다시 조회한다`() = runSuspendIO {
        val queries = AtomicInteger()
        val rows = queryFlow(db, query = {
            queries.incrementAndGet()
            Numbers.selectAll().limit(3)
        }, mapper = { it[Numbers.number] })

        queries.get() shouldBeEqualTo 0
        rows.toList() shouldBeEqualTo listOf(0L, 1L, 2L)
        rows.toList() shouldBeEqualTo listOf(0L, 1L, 2L)
        queries.get() shouldBeEqualTo 2
    }

    @Test
    fun `take는 전체 결과를 매핑하지 않는다`() = runSuspendIO {
        val mapped = AtomicInteger()
        val values = queryFlow(db, query = { Numbers.selectAll().limit(100_000) }, mapper = {
            mapped.incrementAndGet()
            it[Numbers.number]
        }).take(1).toList()

        values shouldBeEqualTo listOf(0L)
        mapped.get().shouldBeIn(1..2)
    }

    @Test
    fun `빈 결과를 정상 종료한다`() = runSuspendIO {
        queryFlow(
            db,
            query = { Numbers.selectAll().limit(0) },
            mapper = {
                it[Numbers.number]
            }
        ).toList().shouldBeEmpty()
    }

    @Test
    fun `조회와 매핑은 지정한 JDBC 디스패처에서 실행한다`() = runSuspendIO {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clickhouse-stream-test")
        }.asCoroutineDispatcher().use { dispatcher ->
            queryFlow(
                db,
                dispatcher,
                query = {
                    Thread.currentThread().name.startsWith("clickhouse-stream-test").shouldBeTrue()
                    Numbers.selectAll().limit(3)
                },
                mapper = {
                    Thread.currentThread().name.startsWith("clickhouse-stream-test").shouldBeTrue()
                    it[Numbers.number]
                }
            ).toList() shouldBeEqualTo listOf(0L, 1L, 2L)
        }
    }
}
