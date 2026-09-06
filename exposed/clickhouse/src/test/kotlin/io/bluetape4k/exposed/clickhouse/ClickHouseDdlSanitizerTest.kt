package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ClickHouseDdlSanitizerTest {

    @Test
    fun `Exposed 순서의 DEFAULT 뒤 NULL 제약만 제거한다`() {
        listOf("NULL", "'hello NULL'", "[NULL, NULL]", "if(1 IS NULL, NULL, NULL)").forEach { expression ->
            sanitizeForClickHouse("CREATE TABLE t (a Nullable(String) DEFAULT $expression NULL)") shouldBeEqualTo
                "CREATE TABLE t (a Nullable(String) DEFAULT $expression)"
        }
    }

    @Test
    fun `문자열 리터럴의 제약 키워드를 보존한다`() {
        listOf(
            "hello NULL world", "NOT NULL", "PRIMARY KEY", "REFERENCES parent(id)",
            "CONSTRAINT pk PRIMARY KEY (id)",
        ).forEach { value ->
            val sql = "CREATE TABLE t (s String DEFAULT '$value' NOT NULL)"
            sanitizeForClickHouse(sql) shouldBeEqualTo "CREATE TABLE t (s String DEFAULT '$value')"
        }
    }

    @Test
    fun `escaped quote 뒤의 리터럴도 보존한다`() {
        listOf("it''s NULL", """it\'s NOT NULL""", """path\\ NULL""").forEach { value ->
            val sql = "CREATE TABLE t (s String DEFAULT '$value' NOT NULL)"
            sanitizeForClickHouse(sql) shouldBeEqualTo "CREATE TABLE t (s String DEFAULT '$value')"
        }
    }

    @Test
    fun `인용 식별자와 Nullable 타입을 보존한다`() {
        val sql = """CREATE TABLE "a NULL" ("NOT NULL" Nullable(String) NULL, `PRIMARY KEY` Int64 NOT NULL)"""
        sanitizeForClickHouse(sql) shouldBeEqualTo
            """CREATE TABLE "a NULL" ("NOT NULL" Nullable(String), `PRIMARY KEY` Int64)"""
    }

    @Test
    fun `DEFAULT NULL과 식 내부의 NULL을 보존한다`() {
        val sql = "CREATE TABLE t (a Nullable(String) NULL DEFAULT NULL, " +
            "b String NOT NULL DEFAULT if(1 IS NULL, 'NULL', 'x'))"
        sanitizeForClickHouse(sql) shouldBeEqualTo
            ("CREATE TABLE t (a Nullable(String) DEFAULT NULL, " +
                "b String DEFAULT if(1 IS NULL, 'NULL', 'x'))")
    }

    @Test
    fun `인용된 PK 이름과 인라인 참조 대상의 제약을 제거한다`() {
        val sql = """CREATE TABLE t (id Int64 NOT NULL, p Int64 REFERENCES "parent table" ("id value"), """ +
            """CONSTRAINT "pk name" PRIMARY KEY (id))"""
        sanitizeForClickHouse(sql) shouldBeEqualTo "CREATE TABLE t (id Int64, p Int64)"
    }

    @Test
    fun `DEFAULT CASE 식의 NULL과 IS NOT NULL을 보존한다`() {
        val sql = "CREATE TABLE t (a String NOT NULL DEFAULT CASE WHEN 1 IS NOT NULL THEN NULL ELSE NULL END)"
        sanitizeForClickHouse(sql) shouldBeEqualTo
            "CREATE TABLE t (a String DEFAULT CASE WHEN 1 IS NOT NULL THEN NULL ELSE NULL END)"
    }

    @Test
    fun `주석의 제약 키워드와 괄호는 원문 그대로 보존한다`() {
        val sql = "CREATE TABLE t (a String /* NULL ( PRIMARY KEY */ NOT NULL,\n-- NOT NULL )\nb Int64 NOT NULL)"
        sanitizeForClickHouse(sql) shouldBeEqualTo
            "CREATE TABLE t (a String /* NULL ( PRIMARY KEY */,\n-- NOT NULL )\nb Int64)"
    }

    @Test
    fun `FK의 다중 단어 삭제 및 갱신 동작을 함께 제거한다`() {
        val sql = "CREATE TABLE t (p Int64 REFERENCES parent(id) ON DELETE SET NULL ON UPDATE NO ACTION)"
        sanitizeForClickHouse(sql) shouldBeEqualTo "CREATE TABLE t (p Int64)"
    }
}
