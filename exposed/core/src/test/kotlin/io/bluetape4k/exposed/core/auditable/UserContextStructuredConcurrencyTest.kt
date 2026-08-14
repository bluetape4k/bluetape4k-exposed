package io.bluetape4k.exposed.core.auditable

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.Callable
import java.util.concurrent.StructuredTaskScope

/**
 * JDK 25 `StructuredTaskScope` child task 경계에서 [UserContext] 전파와 복원을 검증합니다.
 */
@EnabledForJreRange(min = JRE.JAVA_25)
class UserContextStructuredConcurrencyTest {

    @Test
    fun `withUser는 StructuredTaskScope child task에 사용자명을 전파하고 종료 후 상태를 복원한다`() {
        UserContext.withThreadLocalUser("outer") {
            val childUser = UserContext.withUser("parent") {
                StructuredTaskScope.open<String>().use { scope ->
                    val child = scope.fork(Callable { UserContext.SCOPED_USER.get() })
                    scope.join()
                    child.get()
                }
            }

            childUser shouldBeEqualTo "parent"
            UserContext.SCOPED_USER.isBound.shouldBeFalse()
            UserContext.getCurrentUser() shouldBeEqualTo "outer"
        }

        UserContext.getCurrentUser() shouldBeEqualTo UserContext.DEFAULT_USERNAME
    }

    @Test
    fun `withUser는 child와 parent 예외 이후 사용자 context를 오염시키지 않는다`() {
        UserContext.withThreadLocalUser("outer") {
            val childFailure = assertFailsWith<StructuredTaskScope.FailedException> {
                UserContext.withUser("parent") {
                    StructuredTaskScope.open<Unit>().use { scope ->
                        scope.fork(Callable<Unit> { throw IllegalStateException("child failure") })
                        scope.join()
                    }
                }
            }

            childFailure.cause.shouldNotBeNull().message shouldBeEqualTo "child failure"
            UserContext.SCOPED_USER.isBound.shouldBeFalse()
            UserContext.getCurrentUser() shouldBeEqualTo "outer"

            assertFailsWith<IllegalStateException> {
                UserContext.withUser("parent") {
                    throw IllegalStateException("parent failure")
                }
            }

            UserContext.SCOPED_USER.isBound.shouldBeFalse()
            UserContext.getCurrentUser() shouldBeEqualTo "outer"
        }

        UserContext.getCurrentUser() shouldBeEqualTo UserContext.DEFAULT_USERNAME
    }
}
