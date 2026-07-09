package io.bluetape4k.exposed.examples.modulith.orders.internal

import io.bluetape4k.exposed.examples.modulith.orders.Order
import io.bluetape4k.exposed.examples.modulith.orders.OrderId
import io.bluetape4k.exposed.examples.modulith.orders.OrderStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
class OrderRepository {

    val table: OrderTable = OrderTable

    fun save(order: Order): Order = transaction {
        table.insert { row ->
            row[id] = order.id.value
            row[orderKey] = order.orderKey
            row[customerId] = order.customerId
            row[note] = order.note
            row[status] = order.status.name
            row[acceptedAt] = order.acceptedAt
        }
        order
    }

    fun findByOrderId(orderId: OrderId): Order? =
        transaction {
            table.selectAll()
            .where { table.id eq orderId.value }
            .singleOrNull()
            ?.toOrder()
        }

    fun count(): Long =
        transaction { table.selectAll().count() }

    fun deleteAll() {
        transaction { table.deleteAll() }
    }

    private fun ResultRow.toOrder(): Order =
        Order(
            id = OrderId(this[table.id]),
            orderKey = this[table.orderKey],
            customerId = this[table.customerId],
            note = this[table.note],
            status = OrderStatus.valueOf(this[table.status]),
            acceptedAt = this[table.acceptedAt],
        )
}

object OrderTable : Table("DDD_MODULITH_ORDERS") {
    val id = varchar("ORDER_ID", 80)
    val orderKey = varchar("ORDER_KEY", 120)
    val customerId = varchar("CUSTOMER_ID", 120)
    val note = varchar("NOTE", 240).nullable()
    val status = varchar("STATUS", 32)
    val acceptedAt = timestamp("ACCEPTED_AT")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

@Component
class OrderSchemaInitializer(
    private val orderRepository: OrderRepository,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        transaction {
            SchemaUtils.create(orderRepository.table)
        }
    }
}
