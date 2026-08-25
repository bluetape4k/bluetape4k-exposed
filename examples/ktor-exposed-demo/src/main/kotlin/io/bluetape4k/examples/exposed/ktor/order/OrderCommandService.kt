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
        val record = load(orderId)
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
        persist(order.id, record)

        val events = order.domainEvents()
        publish(events)
        order.clearDomainEvents()
        return OrderConfirmationResult(record, eventPublished = true)
    }

    @Suppress("TooGenericExceptionCaught") // repository adapter마다 runtime failure 타입이 다를 수 있다.
    private suspend fun load(orderId: UUID): OrderRecord? = try {
        repository.get(orderId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        throw OrderPersistenceException(e)
    }

    @Suppress("TooGenericExceptionCaught") // persistence와 compensation에서 original failure를 보존한다.
    private suspend fun persist(orderId: UUID, record: OrderRecord) {
        try {
            currentCoroutineContext().ensureActive()
            repository.put(orderId, record)
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            compensateInvalidation(orderId, e)
            throw e
        } catch (e: RuntimeException) {
            compensateInvalidation(orderId, e)
            throw OrderPersistenceException(e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // event adapter마다 runtime failure 타입이 다를 수 있다.
    private fun publish(events: List<DomainEvent<UUID>>) {
        try {
            publisher.publish(events)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            throw OrderEventHandoffException(e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // cache invalidation failure를 primary를 가리지 않고 첨부한다.
    private suspend fun compensateInvalidation(orderId: UUID, failure: Throwable) {
        try {
            withContext(NonCancellable) {
                repository.invalidate(orderId)
            }
        } catch (cleanupFailure: RuntimeException) {
            if (cleanupFailure !== failure) {
                failure.addSuppressed(cleanupFailure)
            }
        }
    }
}
