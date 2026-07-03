package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

@Suppress("UnusedReceiverParameter")
private val Transaction.failedOn: String
    get() = currentTestDB?.name ?: currentDialectTest.name

/**
 * 현재 테스트 방언 정보를 포함한 실패 메시지로 `true` 검증을 수행합니다.
 */
fun Transaction.assertTrue(actual: Boolean) = withDialectAssertion {
    actual.shouldBeTrue()
}

/**
 * 현재 테스트 방언 정보를 포함한 실패 메시지로 `false` 검증을 수행합니다.
 */
fun Transaction.assertFalse(actual: Boolean) = withDialectAssertion {
    actual.shouldBeFalse()
}

/**
 * 현재 테스트 방언 정보를 포함한 실패 메시지로 동등성 검증을 수행합니다.
 */
fun <T> Transaction.assertEquals(exp: T, act: T) = withDialectAssertion {
    act shouldBeEqualTo exp
}

/**
 * 단일 원소 컬렉션과 기대값을 비교합니다.
 */
fun <T> Transaction.assertEquals(exp: T, act: Collection<T>) =
    withDialectAssertion {
        act.single() shouldBeEqualTo exp
    }

/**
 * 현재 테스트 방언 정보를 포함한 실패 메시지로 비동등성 검증을 수행합니다.
 *
 * [exp]와 [act]가 같으면 assertion 실패로 처리됩니다.
 */
fun <T> Transaction.assertNotEquals(exp: T, act: T) =
    withDialectAssertion {
        act shouldNotBeEqualTo exp
    }

/**
 * [block]이 실패하는지 확인하고, 실행 후 현재 트랜잭션을 롤백합니다.
 *
 * ## 동작/계약
 * - 먼저 `commit()`으로 현재 상태를 확정한 뒤 [block] 실행 실패를 기대합니다.
 * - [block]이 실패하지 않으면 assertion 실패로 처리합니다.
 * - 검증 후에는 항상 `rollback()`을 호출합니다.
 *
 * ```kotlin
 * assertFailAndRollback("duplicate key") {
 *     error("boom")
 * }
 * // rollback 수행됨
 * ```
 */
suspend fun R2dbcTransaction.assertFailAndRollback(message: String, block: suspend () -> Unit) {
    commit()
    var failed = false
    try {
        block()
        commit()
    } catch (_: Throwable) {
        failed = true
    } finally {
        rollback()
    }
    if (!failed) {
        throw AssertionError("Failed on ${currentDialectTest.name}. $message")
    }
}

/**
 * 지정한 예외 타입 발생을 검증합니다.
 */
inline fun <reified T: Throwable> expectException(crossinline body: () -> Unit) {
    assertFailsWith<T>("Failed on ${currentDialectTest.name}") {
        body()
    }
}

/**
 * suspend 블록이 [T] 예외를 던지는지 검사합니다.
 */
suspend inline fun <reified T: Throwable> expectExceptionSuspending(crossinline body: suspend () -> Unit) {
    val thrown = try {
        body()
        null
    } catch (ex: Throwable) {
        ex
    }

    if (thrown == null) {
        throw AssertionError("Failed on ${currentDialectTest.name}. Expected exception ${T::class.simpleName}.")
    }
    if (thrown !is T) {
        throw AssertionError("Failed on ${currentDialectTest.name}. Unexpected exception type: ${thrown::class}", thrown)
    }
}

private inline fun Transaction.withDialectAssertion(block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("Failed on $failedOn", e)
    }
}
