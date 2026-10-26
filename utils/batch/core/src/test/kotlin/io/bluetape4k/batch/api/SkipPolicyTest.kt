package io.bluetape4k.batch.api

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * [SkipPolicy] 단위 테스트.
 *
 * `NONE`, `ALL`, `maxSkips(n)`, 커스텀 람다 시나리오 검증.
 */
class SkipPolicyTest {

    private val anyException = RuntimeException("테스트 예외")

    // ─── NONE ────────────────────────────────────────────────────────────────

    @Test
    fun `NONE - 모든 예외에 대해 false 반환`() {
        SkipPolicy.NONE.shouldSkip(anyException, 0L).shouldBeFalse()
        SkipPolicy.NONE.shouldSkip(anyException, 100L).shouldBeFalse()
        SkipPolicy.NONE.shouldSkip(IllegalArgumentException("x"), 0L).shouldBeFalse()
    }

    // ─── ALL ─────────────────────────────────────────────────────────────────

    @Test
    fun `ALL - 모든 예외에 대해 true 반환`() {
        SkipPolicy.ALL.shouldSkip(anyException, 0L).shouldBeTrue()
        SkipPolicy.ALL.shouldSkip(anyException, Long.MAX_VALUE).shouldBeTrue()
        SkipPolicy.ALL.shouldSkip(Error("fatal"), 0L).shouldBeTrue()
    }

    // ─── maxSkips(n) ─────────────────────────────────────────────────────────

    @Test
    fun `maxSkips - skipCount가 maxSkips 미만이면 true`() {
        val policy = SkipPolicy.maxSkips(5L)
        policy.shouldSkip(anyException, 0L).shouldBeTrue()
        policy.shouldSkip(anyException, 4L).shouldBeTrue()
    }

    @Test
    fun `maxSkips - skipCount가 maxSkips와 같으면 false`() {
        val policy = SkipPolicy.maxSkips(5L)
        policy.shouldSkip(anyException, 5L).shouldBeFalse()
    }

    @Test
    fun `maxSkips - skipCount가 maxSkips 초과이면 false`() {
        val policy = SkipPolicy.maxSkips(3L)
        policy.shouldSkip(anyException, 10L).shouldBeFalse()
    }

    @Test
    fun `maxSkips 0 - 모든 경우 false (스킵 금지)`() {
        val policy = SkipPolicy.maxSkips(0L)
        policy.shouldSkip(anyException, 0L).shouldBeFalse()
    }

    // ─── 커스텀 람다 ──────────────────────────────────────────────────────────

    @Test
    fun `커스텀 fun interface - IllegalArgumentException만 skip 허용`() {
        val policy = SkipPolicy { e, _ -> e is IllegalArgumentException }
        policy.shouldSkip(IllegalArgumentException("ok"), 0L).shouldBeTrue()
        policy.shouldSkip(RuntimeException("not ok"), 0L).shouldBeFalse()
    }

    @Test
    fun `커스텀 fun interface - skipCount 기반 조건`() {
        val policy = SkipPolicy { _, skipCount -> skipCount < 3L }
        policy.shouldSkip(anyException, 0L).shouldBeTrue()
        policy.shouldSkip(anyException, 2L).shouldBeTrue()
        policy.shouldSkip(anyException, 3L).shouldBeFalse()
    }
}
