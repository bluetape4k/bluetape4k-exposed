package io.bluetape4k.exposed.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.exists
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** custom OLAP 정책과 구분하여 기본 H2·PostgreSQL Table의 native DDL을 검증한다. */
class TableOptionsCompatibilityTest {

    companion object: KLogging() {
        @JvmStatic
        fun dialects() = TestDB.enabledDialects().filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
    }

    @ParameterizedTest
    @MethodSource("dialects")
    fun `기본 Table은 dialect에 맞는 옵션 순서와 storage parameter를 보존한다`(testDB: TestDB) {

        val table = object: Table("native_table_options_807") {
            val id = integer("id")
            override val options = if (testDB == TestDB.POSTGRESQL) {
                listOf(RawTableOption("USING heap"))
            } else emptyList()
            override val storageParameters = if (testDB == TestDB.POSTGRESQL) {
                listOf(FillFactorParameter(70), AutovacuumEnabledParameter(false))
            } else emptyList()
        }

        withTables(testDB, table) {
            val suffix = if (testDB == TestDB.POSTGRESQL) {
                " USING heap WITH (fillfactor=70, autovacuum_enabled=false)"
            } else ""
            table.createStatement().single() shouldBeEqualTo
                    "CREATE TABLE IF NOT EXISTS ${identity(table)} (${identity(table.id)} INT NOT NULL)$suffix"
            table.exists().shouldBeTrue()

            if (testDB == TestDB.POSTGRESQL) {
                val settings = exec(
                    "SELECT array_to_string(reloptions, ',') FROM pg_class " +
                            "WHERE oid = 'native_table_options_807'::regclass"
                ) {
                    it.next()
                    it.getString(1)
                }
                settings shouldBeEqualTo "fillfactor=70,autovacuum_enabled=false"
            }
        }
    }
}
