package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.junit.jupiter.api.Test
import java.io.ObjectStreamClass
import java.time.Instant
import java.util.*

class OrderDomainTest {

    companion object: KLogging()

    private val id = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36")
    private val createdAt = Instant.parse("2026-07-17T00:00:00Z")
    private val confirmedAt = Instant.parse("2026-07-17T00:01:00Z")

    @Test
    fun `pending order confirms and records one event`() {
        val order = DemoOrder.pending(id, createdAt)

        val changed = order.confirm(confirmedAt)

        changed shouldBeEqualTo true
        order.status shouldBeEqualTo OrderStatus.CONFIRMED
        order.updatedAt shouldBeEqualTo confirmedAt
        order.domainEvents() shouldBeEqualTo listOf(OrderConfirmed(id, confirmedAt))
    }

    @Test
    fun `confirmed order ignores a repeated confirmation`() {
        val order = DemoOrder.pending(id, createdAt)
        order.confirm(confirmedAt)

        val changed = order.confirm(confirmedAt.plusSeconds(1))

        changed shouldBeEqualTo false
        order.updatedAt shouldBeEqualTo confirmedAt
        order.domainEvents() shouldHaveSize 1
    }

    @Test
    fun `rehydration does not recreate historical events`() {
        val record = OrderRecord(id, OrderStatus.CONFIRMED, confirmedAt)

        val order = DemoOrder.rehydrate(record)

        order.id shouldBeEqualTo id
        order.status shouldBeEqualTo OrderStatus.CONFIRMED
        order.updatedAt shouldBeEqualTo confirmedAt
        order.domainEvents() shouldBeEqualTo emptyList()
        order.toRecord() shouldBeEqualTo record
    }

    @Test
    fun `record serialization id is stable and table uses client UUID`() {
        ObjectStreamClass.lookup(OrderRecord::class.java).serialVersionUID shouldBeEqualTo 1L
        val idColumn: Column<EntityID<UUID>> = DemoOrders.id
        idColumn.name shouldBeEqualTo "id"
        DemoOrders.tableName shouldBeEqualTo "ktor_demo_orders"
    }
}
