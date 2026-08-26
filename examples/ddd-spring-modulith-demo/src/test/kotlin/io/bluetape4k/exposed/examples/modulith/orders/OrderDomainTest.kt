package io.bluetape4k.exposed.examples.modulith.orders

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import org.junit.jupiter.api.Test
import java.util.UUID

class OrderDomainTest {

    @Test
    fun `aggregate and event IDs use canonical UUID v7 values`() {
        val order = Order.accept(
            AcceptOrderCommand(
                orderKey = "order-key",
                customerId = "customer",
            )
        )
        val aggregateId = order.id.value.parsePrefixedUuid("order-")
        val event = order.domainEvents().single() as OrderAcceptedEvent
        val eventId = event.eventId.parsePrefixedUuid("event-")

        aggregateId.version() shouldBeEqualTo 7
        eventId.version() shouldBeEqualTo 7
    }

    @Test
    fun `aggregate and event UUID v7 values are unique and timestamp monotonic`() {
        val orders = (1..64).map { index ->
            Order.accept(
                AcceptOrderCommand(
                    orderKey = "order-key-$index",
                    customerId = "customer-$index",
                )
            )
        }
        val aggregateIds = orders.map { it.id.value.parsePrefixedUuid("order-") }
        val eventIds = orders.map {
            (it.domainEvents().single() as OrderAcceptedEvent).eventId
                .parsePrefixedUuid("event-")
        }

        aggregateIds.toSet().size shouldBeEqualTo aggregateIds.size
        eventIds.toSet().size shouldBeEqualTo eventIds.size
        aggregateIds.map { it.uuidV7Timestamp() }.zipWithNext().all { (previous, next) ->
            previous <= next
        }.shouldBeTrue()
        eventIds.map { it.uuidV7Timestamp() }.zipWithNext().all { (previous, next) ->
            previous <= next
        }.shouldBeTrue()
    }

    private fun String.parsePrefixedUuid(prefix: String): UUID {
        startsWith(prefix).shouldBeTrue()
        val canonical = removePrefix(prefix)
        val uuid = UUID.fromString(canonical)
        uuid.toString() shouldBeEqualTo canonical
        return uuid
    }

    private fun UUID.uuidV7Timestamp(): Long =
        (mostSignificantBits ushr 16) and 0xFFFFFFFFFFFFL
}
