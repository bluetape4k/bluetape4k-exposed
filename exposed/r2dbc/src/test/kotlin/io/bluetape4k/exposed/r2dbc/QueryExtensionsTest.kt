package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test

class QueryExtensionsTest {

    @Test
    fun `any는 요소가 있으면 true를 반환한다`() = runSuspendIO {
        flowOf(1, 2, 3).any().shouldBeTrue()
    }

    @Test
    fun `any는 요소가 없으면 false를 반환한다`() = runSuspendIO {
        flowOf<Int>().any().shouldBeFalse()
    }

    @Test
    fun `any는 단일 요소 Flow에서 true를 반환한다`() = runSuspendIO {
        flowOf(42).any().shouldBeTrue()
    }

    @Test
    fun `sorted는 오름차순 정렬된 리스트를 반환한다`() = runSuspendIO {
        val result = flowOf(3, 1, 2).sorted()
        result shouldBeEqualTo listOf(1, 2, 3)
    }

    @Test
    fun `sorted는 빈 Flow에서 빈 리스트를 반환한다`() = runSuspendIO {
        val result = emptyFlow<Int>().sorted()
        result shouldBeEqualTo emptyList()
    }

    @Test
    fun `sorted는 단일 요소 Flow에서 동일한 리스트를 반환한다`() = runSuspendIO {
        val result = flowOf(7).sorted()
        result shouldBeEqualTo listOf(7)
    }

    @Test
    fun `distinct는 전체 중복을 제거한 리스트를 반환한다`() = runSuspendIO {
        val result = flowOf(1, 2, 1, 3, 2, 3, 3).distinct()
        result shouldBeEqualTo listOf(1, 2, 3)
    }

    @Test
    fun `distinct는 빈 Flow에서 빈 리스트를 반환한다`() = runSuspendIO {
        val result = emptyFlow<Int>().distinct()
        result shouldBeEqualTo emptyList()
    }

    @Test
    fun `distinct는 중복 없는 Flow에서 원본 순서를 유지한다`() = runSuspendIO {
        val result = flowOf("b", "a", "c").distinct()
        result shouldBeEqualTo listOf("b", "a", "c")
    }
}
