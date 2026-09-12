package io.bluetape4k.exposed.starrocks

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/** StarRocks DDL 보정이 SQL 구조 밖의 텍스트를 훼손하지 않는지 검증한다. */
class StarRocksDdlSanitizerTest {

    companion object: KLogging()

    @Test
    fun `DEFAULT literal과 주석 및 인용 식별자의 키워드를 보존한다`() {
        val sql = """
            CREATE TABLE `quoted NULL` (
                `PRIMARY KEY` VARCHAR(100) DEFAULT 'x NULL PRIMARY KEY ENGINE=fake' /* NULL PRIMARY KEY ENGINE=fake */ NULL,
                value BIGINT -- NULL PRIMARY KEY ENGINE=fake
            )
            """.trimIndent()

        sanitizeForTest(sql) shouldBeEqualTo """
            CREATE TABLE `quoted NULL` (
                `PRIMARY KEY` VARCHAR(100) DEFAULT 'x NULL PRIMARY KEY ENGINE=fake' /* NULL PRIMARY KEY ENGINE=fake */,
                value BIGINT -- NULL PRIMARY KEY ENGINE=fake
            ) ENGINE=OLAP PROPERTIES ("replication_num" = "1")
            """.trimIndent()
    }

    @Test
    fun `실제 PRIMARY KEY와 nullable clause만 제거한다`() {
        val sql = """
            CREATE TABLE t (
                id BIGINT NOT NULL PRIMARY KEY,
                note VARCHAR(100) DEFAULT 'NULL PRIMARY KEY ENGINE=fake',
                CONSTRAINT pk PRIMARY KEY (id)
            )
        """.trimIndent()

        sanitizeForTest(sql) shouldBeEqualTo """
            CREATE TABLE t (
                id BIGINT NOT NULL,
                note VARCHAR(100) DEFAULT 'NULL PRIMARY KEY ENGINE=fake'
            ) ENGINE=OLAP PROPERTIES ("replication_num" = "1")
            """.trimIndent()
    }

    @Test
    fun `ENGINE literal은 실제 ENGINE clause로 오인하지 않고 기본 engine을 추가한다`() {
        val sql = "CREATE TABLE t (value VARCHAR(100) DEFAULT 'ENGINE=fake')"

        sanitizeForTest(sql) shouldBeEqualTo
                "$sql ENGINE=OLAP PROPERTIES (\"replication_num\" = \"1\")"
    }

    @Test
    fun `실제 ENGINE clause가 있으면 기본 engine을 중복 추가하지 않는다`() {
        val sql = "CREATE TABLE t (value VARCHAR(100)) ENGINE=OLAP"

        sanitizeForTest(sql) shouldBeEqualTo sql
    }

    @Test
    fun `doubled quote와 backslash escape 안의 DDL 키워드를 보존한다`() {
        val sqls = listOf(
            """CREATE TABLE t (value VARCHAR(100) DEFAULT 'x ''NULL PRIMARY KEY ENGINE=fake')""",
            """CREATE TABLE t (value VARCHAR(100) DEFAULT 'x \'NULL PRIMARY KEY ENGINE=fake')""",
        )

        sqls.forEach { sql ->
            sanitizeForTest(sql) shouldBeEqualTo
                    "$sql ENGINE=OLAP PROPERTIES (\"replication_num\" = \"1\")"
        }
    }

    private fun sanitizeForTest(sql: String): String {
//        val method = Class.forName("io.bluetape4k.exposed.starrocks.StarRocksTableKt")
//            .getDeclaredMethod("sanitizeForStarRocks", String::class.java)
//        check(method.trySetAccessible()) { "StarRocks sanitizer is not accessible for regression testing." }
//        return method.invoke(null, sql) as String
        return sql.sanitizeForStarRocks().apply {
            log.debug { "sanitized: $this" }
        }
    }
}
