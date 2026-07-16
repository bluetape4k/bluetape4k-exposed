package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class OrderCommandServiceTest {

    private val id = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36")
    private val createdAt = Instant.parse("2026-07-17T00:00:00Z")
    private val confirmedAt = Instant.parse("2026-07-17T00:01:00Z")
    private val clock = Clock.fixed(confirmedAt, ZoneOffset.UTC)

    @Test
    fun `successful confirmation persists before publishing and clears events`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val order = DemoOrder.pending(id, createdAt)
        val actions = mutableListOf<String>()
        var published: List<DomainEvent<UUID>> = emptyList()
        coEvery { repository.put(any(), any()) } coAnswers { actions += "put" }
        val publisher = OrderEventPublisher { events ->
            actions += "publish"
            published = events
        }

        val result = service(repository, publisher).confirm(order)

        actions shouldBeEqualTo listOf("put", "publish")
        result shouldBeEqualTo OrderConfirmationResult(order.toRecord(), eventPublished = true)
        published shouldBeEqualTo listOf(OrderConfirmed(id, confirmedAt))
        order.domainEvents() shouldBeEqualTo emptyList()
    }

    @Test
    fun `already confirmed order is an idempotent no-op`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val confirmed = OrderRecord(id, OrderStatus.CONFIRMED, confirmedAt)
        coEvery { repository.get(id) } returns confirmed
        val publisher = mockk<OrderEventPublisher>(relaxed = true)

        val result = service(repository, publisher).confirm(id)

        result shouldBeEqualTo OrderConfirmationResult(confirmed, eventPublished = false)
        coVerify(exactly = 0) { repository.put(any(), any()) }
        coVerify(exactly = 0) { repository.invalidate(any()) }
        io.mockk.verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `write failure invalidates cache retains event and exposes the original cause`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val order = DemoOrder.pending(id, createdAt)
        val writeFailure = IllegalStateException("write failed")
        coEvery { repository.put(any(), any()) } throws writeFailure
        val publisher = mockk<OrderEventPublisher>(relaxed = true)

        val failure = assertFailsWith<OrderPersistenceException> {
            service(repository, publisher).confirm(order)
        }

        assertSame(writeFailure, failure.cause)
        order.domainEvents() shouldBeEqualTo listOf(OrderConfirmed(id, confirmedAt))
        coVerify(exactly = 1) { repository.invalidate(id) }
        io.mockk.verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `invalidation failure is suppressed on the write failure`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val writeFailure = IllegalStateException("write failed")
        val cleanupFailure = IllegalArgumentException("invalidate failed")
        coEvery { repository.put(any(), any()) } throws writeFailure
        coEvery { repository.invalidate(id) } throws cleanupFailure

        val failure = assertFailsWith<OrderPersistenceException> {
            service(repository, OrderEventPublisher {}).confirm(DemoOrder.pending(id, createdAt))
        }

        assertSame(writeFailure, failure.cause)
        writeFailure.suppressed.single().javaClass shouldBeEqualTo cleanupFailure.javaClass
        writeFailure.suppressed.single().message shouldBeEqualTo cleanupFailure.message
    }

    @Test
    fun `publisher failure keeps persisted data and retains the event`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val order = DemoOrder.pending(id, createdAt)
        val publishFailure = IllegalStateException("publish failed")
        val publisher = OrderEventPublisher { throw publishFailure }

        val failure = assertFailsWith<OrderEventHandoffException> {
            service(repository, publisher).confirm(order)
        }

        assertSame(publishFailure, failure.cause)
        order.domainEvents() shouldBeEqualTo listOf(OrderConfirmed(id, confirmedAt))
        coVerify(exactly = 0) { repository.invalidate(any()) }
    }

    @Test
    fun `repository cancellation invalidates in non-cancellable context and rethrows the same instance`() =
        runTest(timeout = 30.seconds) {
            val repository = repository()
            val cancellation = CancellationException("write cancelled")
            coEvery { repository.put(any(), any()) } throws cancellation

            val failure = assertFailsWith<CancellationException> {
                service(repository, OrderEventPublisher {}).confirm(DemoOrder.pending(id, createdAt))
            }

            assertSame(cancellation, failure)
            coVerify(exactly = 1) { repository.invalidate(id) }
        }

    @Test
    fun `invalidation failure is suppressed on the same cancellation instance`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val cancellation = CancellationException("write cancelled")
        val cleanupFailure = IllegalStateException("invalidate failed")
        coEvery { repository.put(any(), any()) } throws cancellation
        coEvery { repository.invalidate(id) } throws cleanupFailure

        val failure = assertFailsWith<CancellationException> {
            service(repository, OrderEventPublisher {}).confirm(DemoOrder.pending(id, createdAt))
        }

        assertSame(cancellation, failure)
        cancellation.suppressed.single().javaClass shouldBeEqualTo cleanupFailure.javaClass
        cancellation.suppressed.single().message shouldBeEqualTo cleanupFailure.message
    }

    @Test
    fun `cancellation before write invalidates without persisting or publishing`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val cancellation = CancellationException("cancel before write")
        coEvery { repository.get(id) } coAnswers {
            currentCoroutineContext().cancel(cancellation)
            null
        }
        val publisher = mockk<OrderEventPublisher>(relaxed = true)

        supervisorScope {
            val command = async { service(repository, publisher).confirm(id) }
            val failure = assertFailsWith<CancellationException> { command.await() }
            assertSame(cancellation, failure.cause)
        }

        coVerify(exactly = 0) { repository.put(any(), any()) }
        coVerify(exactly = 1) { repository.invalidate(id) }
        io.mockk.verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `cancellation immediately after write invalidates and skips publishing`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val cancellation = CancellationException("cancel after write")
        coEvery { repository.get(id) } returns null
        coEvery { repository.put(any(), any()) } coAnswers {
            currentCoroutineContext().cancel(cancellation)
        }
        val publisher = mockk<OrderEventPublisher>(relaxed = true)

        supervisorScope {
            val command = async { service(repository, publisher).confirm(id) }
            val failure = assertFailsWith<CancellationException> { command.await() }
            assertSame(cancellation, failure.cause)
        }

        coVerify(exactly = 1) { repository.put(any(), any()) }
        coVerify(exactly = 1) { repository.invalidate(id) }
        io.mockk.verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `lookup failure is reported as persistence failure without compensation`() = runTest(timeout = 30.seconds) {
        val repository = repository()
        val lookupFailure = IllegalStateException("lookup failed")
        coEvery { repository.get(id) } throws lookupFailure

        val failure = assertFailsWith<OrderPersistenceException> {
            service(repository, OrderEventPublisher {}).confirm(id)
        }

        assertSame(lookupFailure, failure.cause)
        coVerify(exactly = 0) { repository.put(any(), any()) }
        coVerify(exactly = 0) { repository.invalidate(any()) }
    }

    @Test
    fun `two concurrent confirmations characterize the demo's last-write-wins limitation`() =
        runTest(timeout = 30.seconds) {
            val repository = repository()
            val readCount = AtomicInteger()
            val writes = AtomicInteger()
            val publishes = AtomicInteger()
            val releaseReads = CompletableDeferred<Unit>()
            val pending = OrderRecord(id, OrderStatus.PENDING, createdAt)
            coEvery { repository.get(id) } coAnswers {
                if (readCount.incrementAndGet() == 2) releaseReads.complete(Unit)
                releaseReads.await()
                pending
            }
            coEvery { repository.put(id, any()) } coAnswers { writes.incrementAndGet() }
            val publisher = OrderEventPublisher { publishes.incrementAndGet() }
            val commandService = service(repository, publisher)

            val results = listOf(
                async { commandService.confirm(id) },
                async { commandService.confirm(id) },
            ).awaitAll()

            results.map { it.eventPublished } shouldBeEqualTo listOf(true, true)
            writes.get() shouldBeEqualTo 2
            publishes.get() shouldBeEqualTo 2
        }

    @Suppress("UNCHECKED_CAST")
    private fun repository(): R2dbcCaffeineRepository<UUID, OrderRecord> = mockk(relaxed = true)

    private fun service(
        repository: R2dbcCaffeineRepository<UUID, OrderRecord>,
        publisher: OrderEventPublisher,
    ): OrderCommandService = OrderCommandService(repository, publisher, clock)
}
