package io.bluetape4k.spring.data.exposed.jdbc.repository.query

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformation
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.dao.Entity
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

/**
 * 양쪽 adapter에 동일한 SQL corpus를 적용하는 바인더 경계 검증입니다.
 * 방언별 SQL 실행 여부는 별도 repository 통합 테스트가 검증합니다.
 */
class SqlBindingConformanceTest {
    private val query = run {
        val method = mockk<ExposedQueryMethod>()
        every { method.getAnnotatedQuery() } returns "SELECT id FROM sample"
        val information = mockk<ExposedEntityInformation<Entity<Long>, Long>>()
        every { information.entityClass } returns mockk()
        DeclaredExposedQuery(method, information)
    }

    private fun bind(sql: String, values: Array<out Any?>): Pair<String, List<Any?>> {
        val method = query.javaClass.getDeclaredMethod("bindParameters", String::class.java, Array<Any?>::class.java)
        method.isAccessible = true
        val bound = method.invoke(query, sql, values)
        fun field(name: String): Any = bound.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bound)
        val arguments = field("args") as List<*>
        return field("sql") as String to arguments.map { (it as Pair<*, *>).second }
    }

    @Test
    fun `동일 SQL corpus에서 보호 영역과 실제 반복 다자리 바인딩을 구분한다`() {
        val dollar = '$'
        val fragments = listOf(
            "'?99'", "'it''s ?99'", "'it\\'s ?99'", "\"?99\"", "`?99`",
            "\"x\"\"?99\"", "`x``?99`", "/* ?99 /* ?98 */ ?97 */",
            "-- ?99\n", "# ?99\r\n", "${dollar}${dollar}?99${dollar}${dollar}",
            "${dollar}tag${dollar}?99${dollar}tag${dollar}",
            "payload #> '{?99}'", "payload #>> '{?99}'",
        )
        val values = Array<Any?>(10) { "value-${it + 1}" }
        fragments.forEach { fragment ->
            bind("SELECT $fragment, ?10, ?2, ?10, ?1", values) shouldBeEqualTo
                ("SELECT $fragment, ?, ?, ?, ?" to listOf("value-10", "value-2", "value-10", "value-1"))
        }
        bind("SELECT ?1, ?1", arrayOf(null)) shouldBeEqualTo ("SELECT ?, ?" to listOf(null, null))
    }

    @Test
    fun `실제 인덱스가 범위 밖이면 기존 adapter 진단을 유지한다`() {
        listOf("?0", "?2", "?2147483647").forEach { marker ->
            val error = assertFailsWith<InvocationTargetException> { bind("SELECT $marker", arrayOf("one")) }
            (error.cause is IllegalArgumentException) shouldBeEqualTo true
            (error.cause?.message?.startsWith("Query placeholder index out of bounds:") == true) shouldBeEqualTo true
        }
    }
}
