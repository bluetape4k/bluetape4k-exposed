package io.bluetape4k.exposed.core

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * [ExposedCursorPage]의 커서 진행 상태 불변식을 검증합니다.
 */
class ExposedCursorPageTest {
    @Test
    fun `마지막 페이지는 다음 커서 없이 반환된다`() {
        val page = ExposedCursorPage(content = listOf("a", "b"), nextCursor = null, hasNext = false)

        page.content shouldBeEqualTo listOf("a", "b")
        page.nextCursor shouldBeEqualTo null
        page.hasNext.shouldBeFalse()
    }

    @Test
    fun `다음 페이지가 있으면 비어 있지 않은 content와 커서가 필요하다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(content = emptyList<String>(), nextCursor = 1L, hasNext = true)
        }
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(content = listOf("a"), nextCursor = null, hasNext = true)
        }
    }

    @Test
    fun `다음 페이지가 없으면 nextCursor는 null이어야 한다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedCursorPage(content = listOf("a"), nextCursor = 1L, hasNext = false)
        }
    }

    @Test
    fun `다음 페이지는 마지막 반환 행의 커서와 함께 전달된다`() {
        val page = ExposedCursorPage(content = listOf("a", "b"), nextCursor = 20L, hasNext = true)

        page.content shouldBeEqualTo listOf("a", "b")
        page.nextCursor shouldBeEqualTo 20L
        page.hasNext.shouldBeTrue()
    }
}
