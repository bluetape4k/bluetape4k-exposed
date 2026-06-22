package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * DDL rendering tests for [StarRocksTable].
 */
class StarRocksTableTest: AbstractStarRocksTest() {

    private object SimpleTable: StarRocksTable("starrocks_table_rendering") {
        val id = long("id")
        val name = varchar("name", 100).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun `createStatement removes generic primary key syntax and appends StarRocks options`() {
        transaction(db) {
            val ddl = SimpleTable.createStatement().single()

            ddl.contains("PRIMARY KEY", ignoreCase = true).shouldBeFalse()
            ddl shouldContain "ENGINE=OLAP"
            ddl shouldContain "\"replication_num\" = \"1\""
        }
    }

    @Test
    fun `StarRocksTable DDL can create and drop simple table`() {
        transaction(db) {
            SimpleTable.createStatement().shouldNotBeEmpty()
            exec("DROP TABLE IF EXISTS starrocks_table_rendering")
            SimpleTable.createStatement().forEach { exec(it) }
            exec("DROP TABLE IF EXISTS starrocks_table_rendering")
        }
    }
}
