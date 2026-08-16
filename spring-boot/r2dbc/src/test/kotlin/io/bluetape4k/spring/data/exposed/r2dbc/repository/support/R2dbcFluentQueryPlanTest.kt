package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.Sort
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class R2dbcFluentQueryPlanTest {

    @Test
    fun `plan transitions are immutable and use last-wins rules`() {
        val snapshot = R2dbcExampleSnapshot(
            expectedDomainType = User::class,
            matchingAll = true,
            nullHandler = ExampleMatcher.NullHandler.IGNORE,
            ignoredPaths = emptySet(),
            properties = listOf(
                R2dbcExamplePropertySnapshot(
                    property = "name",
                    value = "alpha",
                    stringMatcher = ExampleMatcher.StringMatcher.EXACT,
                    ignoreCase = false,
                    includeNull = false,
                ),
            ),
        )
        val original = R2dbcFluentQueryPlan(snapshot, User::class)
        val changed = original
            .sortBy(Sort.by("name"))
            .sortBy(Sort.by(Sort.Direction.DESC, "age"))
            .limit(10)
            .asType(NameView::class)
            .project("name")

        assertNotSame(original, changed)
        val sortProperties: List<String> = changed.sort.toList().map { order -> order.property }
        assertEquals(listOf("name", "age"), sortProperties)
        assertEquals(10, changed.limit)
        assertEquals(NameView::class, changed.resultType)
        assertEquals(listOf("name"), changed.projectedProperties)
        assertEquals(null, original.limit)
        assertEquals(null, original.projectedProperties)
    }

    @Test
    fun `zero limit resets and empty projection resets to automatic selection`() {
        val plan = R2dbcFluentQueryPlan(testSnapshot(), User::class)
            .limit(3)
            .project("name")

        assertEquals(null, plan.limit(0).limit)
        assertEquals(null, plan.project().projectedProperties)
        assertFailsWith<IllegalArgumentException> { plan.limit(-1) }
        assertFailsWith<IllegalArgumentException> { plan.sortBy(Sort.unsorted()) }
    }

    private fun testSnapshot() = R2dbcExampleSnapshot(
        expectedDomainType = User::class,
        matchingAll = true,
        nullHandler = ExampleMatcher.NullHandler.IGNORE,
        ignoredPaths = emptySet(),
        properties = emptyList(),
    )

    private interface NameView {
        val name: String
    }
}
