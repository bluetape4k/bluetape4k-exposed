package io.bluetape4k.examples.exposed.webflux.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.junit.jupiter.api.Test

class DataInitializerLifecycleTest {

    @Test
    fun `repeated start is idempotent and publishes accepting traffic after initialization`() = runTest {
        val publisher = RecordingApplicationEventPublisher()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var initializationCount = 0
        val lifecycle = DataInitializerLifecycle(publisher, dispatcher) {
            initializationCount++
        }

        lifecycle.start()
        lifecycle.start()
        advanceUntilIdle()
        lifecycle.awaitReady()

        initializationCount shouldBeEqualTo 1
        lifecycle.isReady.shouldBeTrue()
        publisher.readinessStates shouldBeEqualTo listOf(
            ReadinessState.REFUSING_TRAFFIC,
            ReadinessState.ACCEPTING_TRAFFIC,
        )

        lifecycle.closeAndJoin()
    }

    @Test
    fun `initialization failure is observable and keeps readiness refusing traffic`() = runTest {
        val publisher = RecordingApplicationEventPublisher()
        val failure = IllegalStateException("seed failed")
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {
            throw failure
        }

        lifecycle.start()
        advanceUntilIdle()

        val observed = assertFailsWith<IllegalStateException> {
            lifecycle.awaitReady()
        }
        observed.message shouldBeEqualTo failure.message
        lifecycle.isReady.shouldBeFalse()
        publisher.readinessStates shouldBeEqualTo listOf(
            ReadinessState.REFUSING_TRAFFIC,
            ReadinessState.REFUSING_TRAFFIC,
        )

        lifecycle.closeAndJoin()
    }

    @Test
    fun `accepting readiness publication failure is observable`() = runTest {
        val publisher = RecordingApplicationEventPublisher().apply {
            throwOnState = ReadinessState.ACCEPTING_TRAFFIC
        }
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {}

        lifecycle.start()
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> {
            lifecycle.awaitReady()
        }
        lifecycle.isReady.shouldBeFalse()
        publisher.readinessStates shouldBeEqualTo listOf(
            ReadinessState.REFUSING_TRAFFIC,
            ReadinessState.REFUSING_TRAFFIC,
        )

        lifecycle.closeAndJoin()
    }

    @Test
    fun `initial refusing readiness publication failure is observable`() = runTest {
        val publisher = RecordingApplicationEventPublisher().apply {
            throwOnState = ReadinessState.REFUSING_TRAFFIC
        }
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {}

        lifecycle.start()

        assertFailsWith<IllegalStateException> {
            lifecycle.awaitReady()
        }
        lifecycle.isReady.shouldBeFalse()
        lifecycle.closeAndJoin()
    }

    @Test
    fun `close waits for the child coroutine to observe cancellation`() = runTest {
        val publisher = RecordingApplicationEventPublisher()
        val cancelled = CompletableDeferred<Unit>()
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        lifecycle.start()
        advanceUntilIdle()
        lifecycle.closeAndJoin()

        cancelled.await()
    }

    @Test
    fun `close before dispatcher runs completes readiness wait with cancellation`() = runTest {
        val publisher = RecordingApplicationEventPublisher()
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {
            awaitCancellation()
        }

        lifecycle.start()
        lifecycle.closeAndJoin()

        assertFailsWith<CancellationException> {
            lifecycle.awaitReady()
        }
    }

    @Test
    fun `premature accepting readiness is rejected until initialization completes`() = runTest {
        val publisher = RecordingApplicationEventPublisher()
        val lifecycle = DataInitializerLifecycle(publisher, StandardTestDispatcher(testScheduler)) {}

        lifecycle.start()
        lifecycle.onReadinessChange(AvailabilityChangeEvent(this, ReadinessState.ACCEPTING_TRAFFIC))
        publisher.readinessStates shouldBeEqualTo listOf(
            ReadinessState.REFUSING_TRAFFIC,
            ReadinessState.REFUSING_TRAFFIC,
        )

        advanceUntilIdle()
        lifecycle.awaitReady()
        publisher.readinessStates.last() shouldBeEqualTo ReadinessState.ACCEPTING_TRAFFIC
        lifecycle.closeAndJoin()
    }

    private class RecordingApplicationEventPublisher : ApplicationEventPublisher {

        var throwOnState: ReadinessState? = null

        val readinessStates: List<ReadinessState>
            get() = events.mapNotNull { (it as? AvailabilityChangeEvent<*>)?.state as? ReadinessState }

        private val events = mutableListOf<Any>()

        override fun publishEvent(event: ApplicationEvent) {
            record(event)
        }

        override fun publishEvent(event: Any) {
            record(event)
        }

        private fun record(event: Any) {
            val state = (event as? AvailabilityChangeEvent<*>)?.state as? ReadinessState
            if (state == throwOnState) {
                throw IllegalStateException("readiness publication failed for $state")
            }
            events += event
        }
    }
}
