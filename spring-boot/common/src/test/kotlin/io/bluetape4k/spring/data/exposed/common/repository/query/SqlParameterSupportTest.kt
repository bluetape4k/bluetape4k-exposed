package io.bluetape4k.spring.data.exposed.common.repository.query

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class SqlParameterSupportTest {

    @Test
    fun `PostgreSQL JSON 경로 연산자 뒤의 파라미터를 치환한다`() {
        listOf("#>", "#>>").forEach { operator ->
            val sql = "SELECT payload $operator '{name}' FROM users WHERE name = ?1 # ?2\nAND age = ?2"
            val numbers = mutableListOf<Int>()
            replaceSqlParameters(sql) { number ->
                numbers += number
                "?"
            } shouldBeEqualTo "SELECT payload $operator '{name}' FROM users WHERE name = ? # ?2\nAND age = ?"
            numbers shouldBeEqualTo listOf(1, 2)
        }
    }

    @Test
    fun `실제 파라미터만 출현 순서대로 치환하고 반복 및 두 자리 번호를 보존한다`() {
        val numbers = mutableListOf<Int>()
        replaceSqlParameters("SELECT ?10, ?2, ?10, ?1") { number ->
            numbers += number
            "?"
        } shouldBeEqualTo "SELECT ?, ?, ?, ?"
        numbers shouldBeEqualTo listOf(10, 2, 10, 1)
    }

    @Test
    fun `문자열 식별자 주석과 dollar quote는 바이트를 유지한다`() {
        val protected = listOf(
            "'?2'", "'it''s ?2'", "'it\\'s ?2'", "\"?2\"", "`?2`", "\"x\"\"?2\"", "`x``?2`",
            "/* ?2 /* ?3 */ ?4 */", "-- ?2\n", "# ?2\r\n",
            "${'$'}${'$'}?2${'$'}${'$'}", "${'$'}tag${'$'}?2 '${'$'}${'$'} ?3${'$'}tag${'$'}",
        )
        protected.forEach { fragment ->
            val sql = "SELECT $fragment, ?1"
            val numbers = mutableListOf<Int>()
            replaceSqlParameters(sql) { number ->
                numbers += number
                "?"
            } shouldBeEqualTo "SELECT $fragment, ?"
            numbers shouldBeEqualTo listOf(1)
        }
    }

    @Test
    fun `닫히지 않은 인용과 주석은 SQL 오류 판단을 드라이버에 맡긴다`() {
        listOf("SELECT '?1", "SELECT /* ?1", "SELECT ${'$'}tag${'$'}?1").forEach { sql ->
            replaceSqlParameters(sql) { error("인용 내부는 파라미터가 아님") } shouldBeEqualTo sql
        }
    }

    @Test
    fun `빈 입력과 번호 없는 물음표 및 dollar 식별자는 유지한다`() {
        replaceSqlParameters("") { error("파라미터가 없음") } shouldBeEqualTo ""
        replaceSqlParameters("SELECT ?, name${'$'}tag${'$'}, ?1") { "bound$it" } shouldBeEqualTo
            "SELECT ?, name${'$'}tag${'$'}, bound1"
    }

    @Test
    fun `범위를 벗어난 숫자는 명확한 예외를 발생시킨다`() {
        assertFailsWith<IllegalArgumentException> {
            replaceSqlParameters("SELECT ?999999999999999999999") { "?" }
        }.message shouldBeEqualTo "Query placeholder index is too large"
    }
}
