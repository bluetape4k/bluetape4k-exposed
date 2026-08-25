@file:Suppress("ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")

package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.api.ExposedSavepoint
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.statements.api.R2dbcExposedConnection

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
 * - 현재 R2DBC transaction에 savepoint를 만들고 [block] 실행 실패를 기대합니다.
 * - [block]이 실패하지 않으면 assertion 실패로 처리합니다.
 * - 검증 후에는 항상 savepoint rollback/release를 `NonCancellable` context에서
 *   실행합니다. R2DBC의 `commit()`은 auto-commit을 켜므로, JDBC helper처럼
 *   시작 시 commit한 뒤 transaction rollback을 호출하면 block DML을 되돌릴 수
 *   없습니다.
 * - [block]의 `CancellationException`은 cleanup 뒤 원래 instance로 재전파합니다.
 * - rollback 또는 release 실패는 primary failure의 `suppressed`에 추가합니다.
 *
 * ```kotlin
 * assertFailAndRollback("duplicate key") {
 *     error("boom")
 * }
 * // rollback 수행됨
 * ```
 */
suspend fun R2dbcTransaction.assertFailAndRollback(message: String, block: suspend () -> Unit) {
    val connection = connection()
    val savepoint = connection.setSavepoint("bluetape_assert_fail_and_rollback")
    preserveFailure(
        block = {
            val failed = try {
                block()
                false
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                true
            }

            if (!failed) {
                throw AssertionError("Failed on ${currentDialectTest.name}. $message")
            }
        },
        cleanup = { rollbackAndRelease(connection, savepoint) },
    )
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
 *
 * [T]가 [CancellationException]이 아니면 cancellation을 assertion failure로
 * 바꾸지 않고 즉시 재전파합니다. cancellation을 검증하려면
 * `expectExceptionSuspending<CancellationException>`처럼 타입을 명시해야 합니다.
 */
suspend inline fun <reified T: Throwable> expectExceptionSuspending(crossinline body: suspend () -> Unit) {
    val expectedType = T::class
    val acceptsCancellation = CancellationException::class.java.isAssignableFrom(expectedType.java)
    val thrown = try {
        body()
        null
    } catch (failure: CancellationException) {
        if (!acceptsCancellation) throw failure
        failure
    } catch (failure: Throwable) {
        failure
    }

    when {
        thrown == null -> {
            throw AssertionError(
                "Failed on ${currentDialectTest.name}. Expected exception ${expectedType.simpleName}.",
            )
        }

        !expectedType.isInstance(thrown) -> {
            throw AssertionError(
                "Failed on ${currentDialectTest.name}. Unexpected exception type: ${thrown::class}",
                thrown,
            )
        }
    }
}

/**
 * 실행 실패와 suspend cleanup 실패를 하나의 primary/suppressed 계약으로 보존합니다.
 *
 * cancellation이 발생해도 cleanup은 [NonCancellable] context에서 완료한 뒤 원래
 * `CancellationException`을 재전파합니다. cleanup만 실패하면 cleanup failure가
 * 그대로 primary가 되고, 실행 실패와 cleanup 실패가 함께 있으면 cleanup failure를
 * primary의 `suppressed`에 추가합니다.
 */
internal suspend fun preserveFailure(
    block: suspend () -> Unit,
    cleanup: suspend () -> Unit,
) {
    var primaryFailure: Throwable? = null
    try {
        block()
    } catch (failure: CancellationException) {
        primaryFailure = failure
    } catch (failure: Throwable) {
        primaryFailure = failure
    }

    try {
        withContext(NonCancellable) {
            cleanup()
        }
    } catch (cleanupFailure: CancellationException) {
        primaryFailure = primaryFailure.appendSuppressed(cleanupFailure)
    } catch (cleanupFailure: Throwable) {
        primaryFailure = primaryFailure.appendSuppressed(cleanupFailure)
    }

    primaryFailure?.let { throw it }
}

private fun Throwable?.appendSuppressed(failure: Throwable): Throwable {
    return this?.also { it.addSuppressed(failure) } ?: failure
}

private suspend fun rollbackAndRelease(
    connection: R2dbcExposedConnection<*>,
    savepoint: ExposedSavepoint,
) {
    var cleanupFailure: Throwable? = null
    try {
        connection.rollback(savepoint)
    } catch (failure: Throwable) {
        cleanupFailure = failure
    }

    try {
        connection.releaseSavepoint(savepoint)
    } catch (failure: Throwable) {
        cleanupFailure = cleanupFailure.appendSuppressed(failure)
    }

    cleanupFailure?.let { throw it }
}

private inline fun Transaction.withDialectAssertion(block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("Failed on $failedOn", e)
    }
}
