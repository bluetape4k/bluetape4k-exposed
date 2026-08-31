package io.bluetape4k.batch

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test

class BatchParameterHashTest {

    @Test
    fun `versioned hash는 key 순서와 UTF-8을 고정한다`() {
        val first: Map<String, Any> = linkedMapOf(
            "region" to "KR",
            "date" to "2026-04-10",
        )
        val reordered: Map<String, Any> = linkedMapOf(
            "date" to "2026-04-10",
            "region" to "KR",
        )

        BatchParameterHash.VERSION shouldBeEqualTo 2
        BatchParameterHash.hash(first) shouldBeEqualTo
            "90aebf7c6f3dd0fc7f971830c2e2f72bb08d3fa633ee06047daa30b9e3e9c576"
        BatchParameterHash.hash(first) shouldBeEqualTo BatchParameterHash.hash(reordered)
        BatchParameterHash.hash(mapOf("지역" to "대한민국", "date" to "2026-04-10")) shouldBeEqualTo
            "0a045260ff22be1e00ea0e2b82eaa39e95f6396d7d39e37f28cbff396c75b18c"
    }

    @Test
    fun `versioned hash는 delimiter와 runtime type collision을 구분한다`() {
        BatchParameterHash.hash(mapOf("a" to "1&b=2")) shouldNotBeEqualTo
            BatchParameterHash.hash(mapOf("a" to "1", "b" to "2"))
        BatchParameterHash.hash(mapOf("x" to 1)) shouldNotBeEqualTo
            BatchParameterHash.hash(mapOf("x" to "1"))
    }

    @Test
    fun `잘못된 UTF-16 surrogate는 물음표와 충돌하기 전에 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("value" to "\uD800"))
        }
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("\uD800" to "value"))
        }

        BatchParameterHash.hash(mapOf("value" to "?")) shouldBeEqualTo
            "5e54fba180869eaed6ecf9e9f9222e7e16a3e85614f46559f9b4aa5c29fdf1e6"
    }

    @Test
    fun `empty map은 기존 빈 hash 계약을 유지한다`() {
        BatchParameterHash.hash(emptyMap()) shouldBeEqualTo ""

        val actual = mapOf<String, Any>("value" to 1)
        val changingView = object : Map<String, Any> by actual {
            override val size: Int = 0

            override fun isEmpty(): Boolean = true
        }
        BatchParameterHash.hash(changingView) shouldBeEqualTo BatchParameterHash.hash(actual)
    }

    @Test
    fun `재현 가능한 표현이 없는 임의 객체는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("value" to Any()))
        }
    }

    @Test
    fun `nested collection은 semantic order와 container type을 보존한다`() {
        val first = mapOf<String, Any>(
            "nested" to linkedMapOf("b" to 2, "a" to 1),
            "set" to linkedSetOf("second", "first"),
        )
        val reordered = mapOf<String, Any>(
            "set" to linkedSetOf("first", "second"),
            "nested" to linkedMapOf("a" to 1, "b" to 2),
        )

        BatchParameterHash.hash(first) shouldBeEqualTo BatchParameterHash.hash(reordered)
        BatchParameterHash.hash(mapOf("values" to listOf(1, 2))) shouldNotBeEqualTo
            BatchParameterHash.hash(mapOf("values" to arrayOf(1, 2)))
    }

    @Test
    fun `순서가 보장되지 않는 generic Iterable은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("values" to sequenceOf(1, 2).asIterable()))
        }
    }

    @Test
    fun `순환 참조와 과도한 중첩은 hash 계산 전에 거부한다`() {
        val cycle = mutableListOf<Any>()
        cycle.add(cycle)

        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("cycle" to cycle))
        }

        var nested: Any = "leaf"
        repeat(BatchParameterHash.MAX_NESTING_DEPTH + 1) {
            nested = listOf(nested)
        }

        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("nested" to nested))
        }
    }

    @Test
    fun `container와 전체 value 상한을 초과하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(
                mapOf("values" to List(BatchParameterHash.MAX_CONTAINER_ITEMS + 1) { it }),
            )
        }

        val growingView = object : java.util.AbstractList<Int>() {
            override val size: Int = 0

            override fun get(index: Int): Int = index

            override fun iterator(): MutableIterator<Int> =
                (0..BatchParameterHash.MAX_CONTAINER_ITEMS).toMutableList().iterator()
        }
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("values" to growingView))
        }

        val manyValues = List(BatchParameterHash.MAX_CONTAINER_ITEMS) { it }
        val parameters = (0..BatchParameterHash.MAX_TOTAL_VALUES / BatchParameterHash.MAX_CONTAINER_ITEMS)
            .associate { index -> "values-$index" to manyValues }

        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(parameters)
        }
    }

    @Test
    fun `scalar와 canonical UTF-8 byte 상한을 초과하면 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(
                mapOf("value" to "a".repeat(BatchParameterHash.MAX_SCALAR_UTF8_BYTES + 1)),
            )
        }

        val boundedScalar = "가".repeat(BatchParameterHash.MAX_SCALAR_UTF8_BYTES / 3)
        val parameters = (1..5).associate { index -> "value-$index" to boundedScalar }

        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(parameters)
        }

        var visited = 0
        val lazyOversizedView = object : java.util.AbstractList<String>() {
            override val size: Int = 0

            override fun get(index: Int): String = boundedScalar

            override fun iterator(): MutableIterator<String> = object : MutableIterator<String> {
                private var index = 0

                override fun hasNext(): Boolean = index < 100

                override fun next(): String {
                    index += 1
                    visited += 1
                    return boundedScalar
                }

                override fun remove() = throw UnsupportedOperationException()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            BatchParameterHash.hash(mapOf("values" to lazyOversizedView))
        }
        (visited < 100) shouldBeEqualTo true
    }
}
