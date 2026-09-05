package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** generic 옵션을 SQL로 렌더링하거나 transaction을 찾기 전에 거부하는지 검증한다. */
class ClickHouseTableOptionsTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ENGINE=InnoDB", "ENGINE = MergeTree()", "",
            "SETTINGS x=1; DROP TABLE victims", "-- comment\nNULL",
        ]
    )
    fun `raw table option은 engine DSL을 우회할 수 없다`(sql: String) {
        val table = object: ClickHouseTable("clickhouse_options") {
            val id = long("id")
            override val options = listOf(RawTableOption(sql))
        }
        assertFailsWith<IllegalArgumentException> { table.createStatement() }
            .message.orEmpty() shouldContain "options"
    }

    @Test
    fun `typed table option도 MySQL 상속 지원으로 오인하지 않는다`() {
        val table = object: ClickHouseTable("clickhouse_typed_options") {
            override val options = listOf(EngineOption(TableEngine.INNODB), CharsetOption("utf8mb4"))
        }
        assertFailsWith<IllegalArgumentException> { table.createStatement() }
            .message.orEmpty() shouldContain "options"
    }

    @ParameterizedTest
    @ValueSource(strings = ["fillfactor=70", "", "x=1); DROP TABLE victims; --"])
    fun `storage parameter는 PostgreSQL WITH 절로 유출되지 않는다`(sql: String) {
        val table = object: ClickHouseTable("clickhouse_storage") {
            val id = long("id")
            override val storageParameters = listOf(FillFactorParameter(70), RawTableStorageParameter(sql))
        }
        assertFailsWith<IllegalArgumentException> { table.createStatement() }
            .message.orEmpty() shouldContain "storageParameters"
    }

    @Test
    fun `사용자 option renderer는 거부 과정에서 실행하지 않는다`() {
        var calls = 0
        val option = object: Table.TableOption() {
            override fun toSQL(): String { calls++; return "ENGINE = MergeTree()" }
        }
        val table = object: ClickHouseTable("clickhouse_custom_option") {
            override val options = listOf(option)
        }
        assertFailsWith<IllegalArgumentException> { table.createStatement() }
        calls shouldBeEqualTo 0
    }
}
