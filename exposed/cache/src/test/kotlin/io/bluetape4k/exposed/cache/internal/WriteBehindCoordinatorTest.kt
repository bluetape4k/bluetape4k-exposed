package io.bluetape4k.exposed.cache.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import org.junit.jupiter.api.Test

/**
 * DB와 channel을 사용하지 않고 write-behind lifecycle 계약만 고정하는 conformance test입니다.
 */
class WriteBehindCoordinatorTest {

    @Test
    fun `admission은 한 번만 settle되고 accepted entry만 depth를 증가시킨다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.WRITE_BEHIND)

        val accepted = coordinator.reserveAdmission()
        coordinator.markEnqueued(accepted)
        coordinator.settleEnqueue(accepted, accepted = true)
        coordinator.snapshot().queueDepth shouldBeEqualTo 1
        coordinator.snapshot().workerState shouldBeEqualTo CacheWorkerState.RUNNING

        assertFailsWith<IllegalStateException> {
            coordinator.settleEnqueue(accepted, accepted = true)
        }

        val rejected = coordinator.reserveAdmission()
        coordinator.settleEnqueue(rejected, accepted = false)
        coordinator.snapshot().queueDepth shouldBeEqualTo 1
    }

    @Test
    fun `flush 성공은 depth 범위를 검증하고 failure kind를 회복한다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.WRITE_BEHIND)
        val token = coordinator.reserveAdmission()
        coordinator.markEnqueued(token)
        coordinator.settleEnqueue(token, accepted = true)
        coordinator.onFlushFailed()
        coordinator.snapshot().failureKind shouldBeEqualTo WriteBehindFailureKind.FLUSH

        coordinator.onFlushSucceeded(1)
        val snapshot = coordinator.snapshot()
        snapshot.queueDepth shouldBeEqualTo 0
        snapshot.failureKind.shouldBeNull()

        assertFailsWith<IllegalArgumentException> { coordinator.onFlushSucceeded(1) }
        assertFailsWith<IllegalArgumentException> { coordinator.onFlushSucceeded(-1) }
    }

    @Test
    fun `close owner는 fresh identity와 단일 publication을 요구한다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.WRITE_BEHIND)
        val owner = coordinator.beginClose()
        val typedOwner = owner as CloseLease.Owner

        assertFailsWith<IllegalStateException> {
            coordinator.publishCloseCompletion(
                CloseLease.Owner.mint(Any()),
                CloseCompletion(
                    CloseCompletionKind.COMPLETED,
                    CacheWorkerState.STOPPED,
                    queueDepth = 0,
                ),
            )
        }

        coordinator.publishCloseCompletion(
            typedOwner,
            CloseCompletion(
                CloseCompletionKind.COMPLETED,
                CacheWorkerState.STOPPED,
                queueDepth = 0,
            ),
        )
        coordinator.snapshot().workerState shouldBeEqualTo CacheWorkerState.STOPPED

        assertFailsWith<IllegalStateException> {
            coordinator.publishCloseCompletion(
                typedOwner,
                CloseCompletion(
                    CloseCompletionKind.COMPLETED,
                    CacheWorkerState.STOPPED,
                    queueDepth = 0,
                ),
            )
        }
    }

    @Test
    fun `close 이후 late callback은 terminal state를 되돌리지 않는다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.WRITE_BEHIND)
        val token = coordinator.reserveAdmission()
        coordinator.markEnqueued(token)
        coordinator.settleEnqueue(token, accepted = true)
        val owner = coordinator.beginClose() as CloseLease.Owner
        coordinator.onCloseFailed(WriteBehindFailureKind.CLOSE_TIMEOUT)
        coordinator.onFlushSucceeded(1)
        coordinator.onWorkerCompleted(WriteBehindWorkerCompletion.DRAINED)

        coordinator.snapshot().workerState shouldBeEqualTo CacheWorkerState.FAILED
        coordinator.snapshot().queueDepth shouldBeEqualTo 1
        coordinator.publishCloseCompletion(
            owner,
            CloseCompletion(
                CloseCompletionKind.FAILED,
                CacheWorkerState.FAILED,
                queueDepth = 1,
            ),
        )
        coordinator.snapshot().workerState shouldBeEqualTo CacheWorkerState.FAILED
    }

    @Test
    fun `close gate가 먼저 이기면 handoff된 token은 depth를 증가시키지 않는다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.WRITE_BEHIND)
        val token = coordinator.reserveAdmission()
        coordinator.markEnqueued(token)
        coordinator.beginClose()

        coordinator.settleEnqueue(token, accepted = true) shouldBeEqualTo false
        coordinator.snapshot().queueDepth shouldBeEqualTo 0
        coordinator.snapshot().workerState shouldBeEqualTo CacheWorkerState.DRAINING

        assertFailsWith<IllegalStateException> { coordinator.reserveAdmission() }
    }

    @Test
    fun `non write-behind mode는 admission을 시작하지 않는다`() {
        val coordinator = WriteBehindCoordinator(CacheWriteMode.READ_ONLY)
        val snapshot = coordinator.snapshot()
        snapshot.workerState shouldBeEqualTo CacheWorkerState.NOT_APPLICABLE
        snapshot.queueDepth shouldBeEqualTo 0
        assertFailsWith<IllegalStateException> { coordinator.reserveAdmission() }
    }

    @Test
    fun `flush retry policy는 8회 상한과 capped exponential backoff를 가진다`() {
        MAX_FLUSH_RETRY_ATTEMPTS shouldBeEqualTo 8
        flushRetryBackoffMillis(1) shouldBeEqualTo 10L
        flushRetryBackoffMillis(2) shouldBeEqualTo 20L
        flushRetryBackoffMillis(7) shouldBeEqualTo 640L
        flushRetryBackoffMillis(8) shouldBeEqualTo 1_000L
    }
}
