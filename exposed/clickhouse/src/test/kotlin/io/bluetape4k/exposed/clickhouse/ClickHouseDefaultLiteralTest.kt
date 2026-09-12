package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.exposed.clickhouse.engine.mergeTree
import io.bluetape4k.exposed.clickhouse.types.ClickHouseInt32ColumnType
import io.bluetape4k.exposed.clickhouse.types.chNullable
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseDefaultLiteralTest: AbstractClickHouseTest() {

    @Test
    fun `nullable 기본값의 최종 NULL 제약이 실제 생성 DDL에서 제거된다`() {
        val table = object: ClickHouseTable("issue821_nullable_default") {
            val id = long("id")
            val value = chNullable("value", ClickHouseInt32ColumnType()).default(42)
            val absent = chNullable("absent", ClickHouseInt32ColumnType()).default(null)
            override val engine = mergeTree { orderBy(id) }
        }
        transaction(db) {
            val ddl = table.createStatement().single()
            ddl shouldContain "Nullable(Int32) DEFAULT 42,"
            ddl shouldContain "Nullable(Int32) DEFAULT NULL)"
            try {
                SchemaUtils.create(table)
                exec("INSERT INTO issue821_nullable_default (id) VALUES (1)")
                val stored = exec("SELECT value, isNull(absent) FROM issue821_nullable_default WHERE id = 1") { rs ->
                    check(rs.next())
                    rs.getInt(1) to rs.getInt(2)
                }
                stored shouldBeEqualTo (42 to 1)
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }

    @Test
    fun `Exposed 기본값 리터럴이 실제 ClickHouse schema와 저장 값에 보존된다`() {
        val defaultValue = "hello NULL NOT NULL PRIMARY KEY REFERENCES parent(id)"
        val table = object: ClickHouseTable("issue821_default_literal") {
            val id = long("id")
            val value = varchar("value", 255).default(defaultValue)
            override val engine = mergeTree { orderBy(id) }
        }
        transaction(db) {
            val ddl = table.createStatement().single()
            ddl shouldContain "'$defaultValue'"
            try {
                SchemaUtils.create(table)
                val defaultExpression = exec(
                    "SELECT default_expression FROM system.columns " +
                        "WHERE database = currentDatabase() AND table = 'issue821_default_literal' AND name = 'value'",
                ) { rs -> check(rs.next()); rs.getString(1) }
                defaultExpression shouldBeEqualTo "'$defaultValue'"
                // JDBC INSERT 경로 차이와 분리하여 서버가 실제 DEFAULT를 적용하는지 검증합니다.
                exec("INSERT INTO issue821_default_literal (id) VALUES (1)")
                val stored = exec("SELECT value FROM issue821_default_literal WHERE id = 1") { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
                stored shouldBeEqualTo defaultValue
            } finally {
                SchemaUtils.drop(table)
            }
        }
    }
}
