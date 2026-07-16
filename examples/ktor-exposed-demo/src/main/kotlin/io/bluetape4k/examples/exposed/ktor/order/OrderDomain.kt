package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class OrderStatus {
    PENDING,
    CONFIRMED,
}

class DemoOrder private constructor(
    override val id: UUID,
    status: OrderStatus,
    updatedAt: Instant,
) : AbstractAggregateRoot<UUID>(), Serializable {

    var status: OrderStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun confirm(occurredAt: Instant): Boolean {
        if (status == OrderStatus.CONFIRMED) return false

        status = OrderStatus.CONFIRMED
        updatedAt = occurredAt
        recordDomainEvent(OrderConfirmed(id, occurredAt))
        return true
    }

    fun toRecord(): OrderRecord = OrderRecord(id, status, updatedAt)

    companion object {
        private const val serialVersionUID: Long = 1L

        fun pending(id: UUID, createdAt: Instant): DemoOrder =
            DemoOrder(id, OrderStatus.PENDING, createdAt)

        fun rehydrate(record: OrderRecord): DemoOrder =
            DemoOrder(record.id, record.status, record.updatedAt)
    }
}

data class OrderConfirmed(
    override val aggregateId: UUID,
    override val occurredAt: Instant,
) : DomainEvent<UUID>, Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class OrderRecord(
    val id: UUID,
    val status: OrderStatus,
    val updatedAt: Instant,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
