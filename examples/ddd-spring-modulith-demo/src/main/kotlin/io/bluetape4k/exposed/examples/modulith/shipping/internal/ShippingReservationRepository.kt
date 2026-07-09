package io.bluetape4k.exposed.examples.modulith.shipping.internal

import io.bluetape4k.exposed.examples.modulith.orders.OrderId
import io.bluetape4k.exposed.examples.modulith.orders.events.OrderAcceptedEvent
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
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
class ShippingReservationRepository {

    val table: ShippingReservationTable = ShippingReservationTable

    fun reserve(event: OrderAcceptedEvent) {
        transaction {
            try {
                table.insert { row ->
                    row[orderId] = event.aggregateId.value
                    row[eventId] = event.eventId
                    row[reservedAt] = event.occurredAt
                }
            } catch (e: ExposedSQLException) {
                if (e.sqlState.startsWith("23")) return@transaction
                throw e
            }
        }
    }

    fun existsByOrderId(orderId: OrderId): Boolean =
        transaction {
            table.selectAll()
                .where { table.orderId eq orderId.value }
                .count() > 0
        }

    fun count(): Long =
        transaction { table.selectAll().count() }

    fun deleteAll() {
        transaction { table.deleteAll() }
    }
}

object ShippingReservationTable : Table("DDD_MODULITH_SHIPPING_RESERVATIONS") {
    val orderId = varchar("ORDER_ID", 80)
    val eventId = varchar("EVENT_ID", 80)
    val reservedAt = timestamp("RESERVED_AT")

    override val primaryKey: PrimaryKey = PrimaryKey(orderId)
}

@Component
class ShippingReservationSchemaInitializer(
    private val shippingReservationRepository: ShippingReservationRepository,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        transaction {
            SchemaUtils.create(shippingReservationRepository.table)
        }
    }
}
