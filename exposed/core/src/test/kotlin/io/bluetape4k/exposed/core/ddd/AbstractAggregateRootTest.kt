package io.bluetape4k.exposed.core.ddd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.time.Instant

class AbstractAggregateRootTest {

    @Test
    fun `domainEvents returns empty immutable snapshot before recording`() {
        val order = TestOrder(OrderId(1L))

        val events = order.domainEvents()

        events.isEmpty().shouldBeTrue()
    }

    @Test
    fun `recordDomainEvent stores event without clearing it`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)

        order.place(event)

        order.domainEvents() shouldBeEqualTo listOf(event)
        order.domainEvents() shouldBeEqualTo listOf(event)
    }

    @Test
    fun `domainEvents returns defensive snapshot`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        order.place(event)

        val first = order.domainEvents()
        order.place(OrderConfirmed(order.id))

        first shouldBeEqualTo listOf(event)
        order.domainEvents() shouldHaveSize 2
    }

    @Test
    fun `domainEvents preserves recording order without clearing buffer`() {
        val order = TestOrder(OrderId(1L))
        val placed = OrderPlaced(order.id)
        val confirmed = OrderConfirmed(order.id)

        order.place(placed)
        order.place(confirmed)

        order.domainEvents() shouldBeEqualTo listOf(placed, confirmed)
        order.domainEvents() shouldBeEqualTo listOf(placed, confirmed)
    }

    @Test
    fun `drainDomainEvents hands off ordered events and clears after success`() {
        val order = TestOrder(OrderId(1L))
        val placed = OrderPlaced(order.id)
        val confirmed = OrderConfirmed(order.id)
        val handedOff = mutableListOf<List<DomainEvent<OrderId>>>()
        order.place(placed)
        order.place(confirmed)

        val drained = order.drainDomainEvents { events ->
            handedOff += events
        }

        drained shouldBeEqualTo listOf(placed, confirmed)
        handedOff shouldBeEqualTo listOf(listOf(placed, confirmed))
        order.domainEvents().isEmpty().shouldBeTrue()
        order.drainDomainEvents {
            error("Empty drain should not invoke handoff")
        }.isEmpty().shouldBeTrue()
        order.domainEvents().isEmpty().shouldBeTrue()
    }

    @Test
    fun `drainDomainEvents keeps events when handoff fails`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        order.place(event)

        assertFailsWith<IllegalStateException> {
            order.drainDomainEvents {
                throw IllegalStateException("handoff failed")
            }
        }

        order.domainEvents() shouldBeEqualTo listOf(event)
    }

    @Test
    fun `clearDomainEvents discards pending events`() {
        val order = TestOrder(OrderId(1L))
        order.place(OrderPlaced(order.id))

        order.clearDomainEvents()

        order.domainEvents().isEmpty().shouldBeTrue()
        order.drainDomainEvents {
            error("Empty drain should not invoke handoff")
        }.isEmpty().shouldBeTrue()
    }

    @Test
    fun `recordDomainEvent rejects events for another aggregate id`() {
        val order = TestOrder(OrderId(1L))

        val error = assertFailsWith<IllegalArgumentException> {
            order.place(OrderPlaced(OrderId(2L)))
        }

        error.message shouldBeEqualTo "Domain event aggregateId must match aggregate id"
    }

    @Test
    fun `aggregate and event ids stay type specific at compile time`() {
        val order = TestOrder(OrderId(1L))
        val event = OrderPlaced(order.id)
        val customerEvent = CustomerRegistered(CustomerId(1L))

        order.place(event)

        order.id shouldBeEqualTo OrderId(1L)
        event.aggregateId shouldBeEqualTo order.id
        customerEvent.aggregateId shouldBeEqualTo CustomerId(1L)
        // A DomainEvent<CustomerId> cannot be passed to TestOrder.place(DomainEvent<OrderId>).
    }

    @JvmInline
    value class OrderId(val value: Long) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @JvmInline
    value class CustomerId(val value: Long) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class TestOrder(
        override val id: OrderId,
    ) : AbstractAggregateRoot<OrderId>() {

        fun place(event: DomainEvent<OrderId>) {
            recordDomainEvent(event)
        }
    }

    private data class OrderPlaced(
        override val aggregateId: OrderId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:00:00Z"),
    ) : DomainEvent<OrderId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class OrderConfirmed(
        override val aggregateId: OrderId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:01:00Z"),
    ) : DomainEvent<OrderId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class CustomerRegistered(
        override val aggregateId: CustomerId,
        override val occurredAt: Instant = Instant.parse("2026-07-09T00:02:00Z"),
    ) : DomainEvent<CustomerId>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
