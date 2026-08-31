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
    fun `empty map은 기존 빈 hash 계약을 유지한다`() {
        BatchParameterHash.hash(emptyMap()) shouldBeEqualTo ""
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
}
