package io.bluetape4k.exposed.cache.snapshot

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test

class SnapshotCacheFailureTest {

    @Test
    fun `bounded buffer preserves FIFO order and counts dropped failures`() {
        val buffer = snapshotCacheFailureBuffer(capacity = 2)
        val first = failure(SnapshotCacheOutcome.FAILED, affectedCount = 1)
        val second = failure(SnapshotCacheOutcome.NOT_ATTEMPTED, affectedCount = 2)

        buffer.recordFailure(first)
        buffer.recordFailure(second)
        buffer.recordFailure(failure(SnapshotCacheOutcome.REJECTED, affectedCount = 3))

        buffer.capacity shouldBeEqualTo 2
        buffer.size shouldBeEqualTo 2
        buffer.droppedCount shouldBeEqualTo 1L
        buffer.poll() shouldBeEqualTo first
        buffer.poll() shouldBeEqualTo second
        buffer.poll().shouldBeNull()
    }

    @Test
    fun `observer exception consumes current failure and stops caller-thread drain`() {
        val buffer = snapshotCacheFailureBuffer(capacity = 3)
        repeat(3) { buffer.recordFailure(failure(SnapshotCacheOutcome.FAILED, it + 1)) }
        var observed = 0

        val result = buffer.drainTo(
            observer = SnapshotCacheFailureObserver {
                observed++
                if (observed == 2) throw ObserverFailure("secret-observer-message")
            },
        )

        result shouldBeEqualTo SnapshotCacheDrainResult(
            deliveredCount = 1,
            observerFailedCount = 1,
            remainingCount = 1,
            observerExceptionType = ObserverFailure::class.java.name,
        )
        buffer.observerFailureCount shouldBeEqualTo 1L
        buffer.size shouldBeEqualTo 1
    }

    @Test
    fun `fatal observer error follows caller thread policy`() {
        val buffer = snapshotCacheFailureBuffer(capacity = 1)
        buffer.recordFailure(failure(SnapshotCacheOutcome.FAILED, 1))

        assertFailsWith<ObserverFatalError> {
            buffer.drainTo(SnapshotCacheFailureObserver { throw ObserverFatalError() })
        }

        buffer.size shouldBeEqualTo 0
        buffer.observerFailureCount shouldBeEqualTo 0L
    }

    @Test
    fun `failure objects retain structural data but no exception payload`() {
        val malicious = MaliciousFailure(
            "password=secret jdbc:postgresql://host/db https://endpoint.example/api select * from credentials",
        )
        val failure = failureFromException(
            storeId = STORE_ID,
            operation = SnapshotCacheOperation.PUT,
            affectedCount = 7,
            exception = malicious,
        )
        val rendered = failure.toString()

        failure.exceptionType shouldBeEqualTo MaliciousFailure::class.java.name
        rendered shouldContain MaliciousFailure::class.java.name
        rendered shouldNotContain malicious.message.orEmpty()
        rendered shouldNotContain "password=secret"
        failure.javaClass.declaredFields
            .map { it.type }
            .any(Throwable::class.java::isAssignableFrom) shouldBeEqualTo false
    }

    @Test
    fun `unicode exception class names remain safe on failure and observer paths`() {
        val failure = failureFromException(
            storeId = STORE_ID,
            operation = SnapshotCacheOperation.INVALIDATE,
            affectedCount = 1,
            exception = 사용자예외(),
        )
        val buffer = snapshotCacheFailureBuffer(1)
        buffer.recordFailure(failure)

        val drained = buffer.drainTo(SnapshotCacheFailureObserver { throw 사용자예외() })

        failure.exceptionType shouldBeEqualTo 사용자예외::class.java.name
        drained.observerExceptionType shouldBeEqualTo 사용자예외::class.java.name
        drained.observerFailedCount shouldBeEqualTo 1
    }

    @Test
    fun `exception type sanitizer accepts safe unicode and drops oversized or malformed names`() {
        val unicodeName = "예외.사용자예외"
        val oversizedName = "a".repeat(256) + "." + "b".repeat(256)

        sanitizeExceptionType(unicodeName) shouldBeEqualTo unicodeName
        sanitizeExceptionType(oversizedName).shouldBeNull()
        sanitizeExceptionType("generated/Failure").shouldBeNull()
        sanitizeExceptionType(" ").shouldBeNull()
    }

    @Test
    fun `buffer and count inputs are validated`() {
        assertFailsWith<IllegalArgumentException> { snapshotCacheFailureBuffer(0) }
        assertFailsWith<IllegalArgumentException> {
            snapshotCacheFailureBuffer(1).drainTo(SnapshotCacheFailureObserver {}, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheFailure(STORE_ID, SnapshotCacheOperation.PUT, SnapshotCacheOutcome.FAILED, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheFailure(
                STORE_ID,
                SnapshotCacheOperation.PUT,
                SnapshotCacheOutcome.FAILED,
                1,
                "https://secret.example/failure",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SnapshotCacheDrainResult(-1, 0, 0)
        }
    }

    @Test
    fun `logging observer accepts sanitized failure`() {
        loggingSnapshotCacheFailureObserver().onFailure(failure(SnapshotCacheOutcome.FAILED, 4))
    }

    private fun failure(outcome: SnapshotCacheOutcome, affectedCount: Int): SnapshotCacheFailure =
        SnapshotCacheFailure(
            storeId = STORE_ID,
            operation = SnapshotCacheOperation.INVALIDATE,
            outcome = outcome,
            affectedCount = affectedCount,
            exceptionType = if (outcome == SnapshotCacheOutcome.FAILED) MaliciousFailure::class.java.name else null,
        )

    private class ObserverFailure(message: String) : RuntimeException(message)

    private class MaliciousFailure(message: String) : RuntimeException(message)

    private class ObserverFatalError : Error()

    private class 사용자예외 : RuntimeException()

    companion object {
        private val STORE_ID = SnapshotStoreId("local", "orders:v1")
    }
}
