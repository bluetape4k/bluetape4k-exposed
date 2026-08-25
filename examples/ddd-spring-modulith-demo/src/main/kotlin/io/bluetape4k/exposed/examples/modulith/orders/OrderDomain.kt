package io.bluetape4k.exposed.examples.modulith.orders

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

@JvmInline
value class OrderId(
    val value: String,
) : Serializable {

    init {
        value.requireNotBlank("value")
    }

    companion object {
        private const val serialVersionUID: Long = -5976855105992851231L

        fun newId(): OrderId =
            OrderId("order-${Uuid.V7.nextId()}")
    }
}

data class AcceptOrderCommand(
    val orderKey: String,
    val customerId: String,
    val note: String? = null,
) : Serializable {

    init {
        orderKey.requireNotBlank("orderKey")
        customerId.requireNotBlank("customerId")
    }

    companion object {
        private const val serialVersionUID: Long = -8303444736234811582L
    }
}

enum class OrderStatus {
    ACCEPTED,
}

class Order(
    override val id: OrderId,
    val orderKey: String,
    val customerId: String,
    val note: String?,
    val status: OrderStatus,
    val acceptedAt: Instant,
) : AbstractAggregateRoot<OrderId>(), Serializable {

    companion object {
        private const val serialVersionUID: Long = -2138607313011936949L

        fun accept(command: AcceptOrderCommand): Order {
            val acceptedAt = Instant.now()
            return Order(
                id = OrderId.newId(),
                orderKey = command.orderKey,
                customerId = command.customerId,
                note = command.note,
                status = OrderStatus.ACCEPTED,
                acceptedAt = acceptedAt,
            ).also { order ->
                order.recordDomainEvent(
                    OrderAcceptedEvent(
                        aggregateId = order.id,
                        eventId = "event-${Uuid.V7.nextId()}",
                        occurredAt = acceptedAt,
                    )
                )
            }
        }
    }
}

class OrderHandoffFailedException(
    val aggregate: Order,
    cause: Throwable,
) : IllegalStateException("order-event-handoff-failed", cause)
