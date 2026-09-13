package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** OLAP 기본 engine과 충돌하는 generic 옵션을 SQL 생성 전에 거부하는지 검증한다. */
class StarRocksTableOptionsTest {

    companion object: KLogging()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ENGINE=InnoDB", "ENGINE=OLAP", "",
            "PROPERTIES (\"replication_num\"=\"1\"); DROP TABLE victims", "-- comment\nNULL",
        ]
    )
    fun `raw table option은 OLAP 기본값을 덮어쓸 수 없다`(sql: String) {
        val table = object: StarRocksTable("starrocks_options") {
            val id = long("id")
            override val options = listOf(RawTableOption(sql))
        }
        assertFailsWith<IllegalArgumentException> {
            table.createStatement()
        }.message.orEmpty() shouldContain "options"
    }

    @Test
    fun `typed MySQL option은 StarRocks 지원으로 오인하지 않는다`() {
        val table = object: StarRocksTable("starrocks_typed_options") {
            override val options = listOf(EngineOption(TableEngine.INNODB), CharsetOption("utf8mb4"))
        }
        assertFailsWith<IllegalArgumentException> {
            table.createStatement()
        }.message.orEmpty() shouldContain "options"
    }

    @ParameterizedTest
    @ValueSource(strings = ["fillfactor=70", "", "x=1); DROP TABLE victims; --"])
    fun `storage parameter는 OLAP properties로 암묵 변환하지 않는다`(sql: String) {
        val table = object: StarRocksTable("starrocks_storage") {
            val id = long("id")
            override val storageParameters = listOf(FillFactorParameter(70), RawTableStorageParameter(sql))
        }
        assertFailsWith<IllegalArgumentException> {
            table.createStatement()
        }.message.orEmpty() shouldContain "storageParameters"
    }

    @Test
    fun `사용자 storage renderer는 거부 과정에서 실행하지 않는다`() {
        var calls = 0
        val parameter = object: Table.TableStorageParameter() {
            override fun toSQL(): String {
                calls++
                return "replication_num=1"
            }
        }
        val table = object: StarRocksTable("starrocks_custom_storage") {
            override val storageParameters = listOf(parameter)
        }

        assertFailsWith<IllegalArgumentException> {
            table.createStatement()
        }
        calls shouldBeEqualTo 0
    }
}
