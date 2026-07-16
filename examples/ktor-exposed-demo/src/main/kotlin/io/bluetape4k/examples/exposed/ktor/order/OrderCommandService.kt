package io.bluetape4k.examples.exposed.ktor.order

import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

fun interface OrderEventPublisher {
    fun publish(events: List<DomainEvent<UUID>>)
}

class InMemoryOrderEventPublisher : OrderEventPublisher {

    @Volatile
    var latestEvents: List<DomainEvent<UUID>> = emptyList()
        private set

    override fun publish(events: List<DomainEvent<UUID>>) {
        latestEvents = events.toList()
    }
}

data class OrderConfirmationResult(
    val record: OrderRecord,
    val eventPublished: Boolean,
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

sealed class OrderCommandException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

class OrderPersistenceException(cause: Throwable) :
    OrderCommandException("Order persistence failed", cause)

class OrderEventHandoffException(cause: Throwable) :
    OrderCommandException("Order event handoff failed", cause)

class OrderCommandService(
    private val repository: R2dbcCaffeineRepository<UUID, OrderRecord>,
    private val publisher: OrderEventPublisher,
    private val clock: Clock,
) {

    suspend fun confirm(orderId: UUID): OrderConfirmationResult {
        val record = try {
            repository.get(orderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OrderPersistenceException(e)
        }
        val order = record?.let(DemoOrder::rehydrate) ?: DemoOrder.pending(orderId, clock.instant())
        return confirm(order)
    }

    internal suspend fun confirm(
        order: DemoOrder,
        occurredAt: Instant = clock.instant(),
    ): OrderConfirmationResult {
        if (!order.confirm(occurredAt)) {
            return OrderConfirmationResult(order.toRecord(), eventPublished = false)
        }

        val record = order.toRecord()
        try {
            currentCoroutineContext().ensureActive()
            repository.put(order.id, record)
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            compensateInvalidation(order.id, e)
            throw e
        } catch (e: Exception) {
            compensateInvalidation(order.id, e)
            throw OrderPersistenceException(e)
        }

        val events = order.domainEvents()
        try {
            publisher.publish(events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OrderEventHandoffException(e)
        }
        order.clearDomainEvents()
        return OrderConfirmationResult(record, eventPublished = true)
    }

    private suspend fun compensateInvalidation(orderId: UUID, failure: Exception) {
        try {
            withContext(NonCancellable) {
                repository.invalidate(orderId)
            }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) {
                failure.addSuppressed(cleanupFailure)
            }
        }
    }
}
