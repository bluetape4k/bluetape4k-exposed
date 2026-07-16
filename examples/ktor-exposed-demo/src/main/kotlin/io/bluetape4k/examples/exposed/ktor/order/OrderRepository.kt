package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.cache.LocalCacheConfig
import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.r2dbc.caffeine.repository.AbstractR2dbcCaffeineRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Duration
import java.util.UUID

object DemoOrders : TimebasedUUIDTable("ktor_demo_orders") {
    val status = enumerationByName("status", 16, OrderStatus::class)
    val updatedAt = timestamp("updated_at")
}

class OrderR2dbcCaffeineRepository(
    config: LocalCacheConfig = LocalCacheConfig(
        keyPrefix = "orders",
        maximumSize = 1_000,
        expireAfterWrite = Duration.ofMinutes(10),
        writeMode = CacheWriteMode.WRITE_THROUGH,
    ),
) : AbstractR2dbcCaffeineRepository<UUID, OrderRecord>(config) {

    override val table: IdTable<UUID> = DemoOrders

    override suspend fun ResultRow.toEntity(): OrderRecord = OrderRecord(
        id = this[DemoOrders.id].value,
        status = this[DemoOrders.status],
        updatedAt = this[DemoOrders.updatedAt],
    )

    override fun UpdateStatement.updateEntity(entity: OrderRecord) {
        this[DemoOrders.status] = entity.status
        this[DemoOrders.updatedAt] = entity.updatedAt
    }

    override fun BatchInsertStatement.insertEntity(entity: OrderRecord) {
        this[DemoOrders.id] = entity.id
        this[DemoOrders.status] = entity.status
        this[DemoOrders.updatedAt] = entity.updatedAt
    }

    override fun extractId(entity: OrderRecord): UUID = entity.id
}
