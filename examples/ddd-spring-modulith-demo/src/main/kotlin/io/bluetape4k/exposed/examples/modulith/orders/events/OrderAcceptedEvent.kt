package io.bluetape4k.exposed.examples.modulith.orders.events

import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.exposed.examples.modulith.orders.OrderId
import java.io.Serializable
import java.time.Instant

data class OrderAcceptedEvent(
    override val aggregateId: OrderId,
    val eventId: String,
    override val occurredAt: Instant,
) : DomainEvent<OrderId>, Serializable {

    companion object {
        private const val serialVersionUID: Long = -1468532318012213445L
    }
}
