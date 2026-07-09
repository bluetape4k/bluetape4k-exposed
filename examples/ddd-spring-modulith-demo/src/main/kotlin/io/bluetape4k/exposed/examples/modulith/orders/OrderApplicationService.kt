package io.bluetape4k.exposed.examples.modulith.orders

import io.bluetape4k.exposed.examples.modulith.orders.internal.OrderRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class OrderApplicationService(
    private val orderRepository: OrderRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    fun accept(
        command: AcceptOrderCommand,
        failAfterPublish: Boolean = false,
    ): Order {
        val order = Order.accept(command)
        val saved = try {
            transactionTemplate.execute {
                orderRepository.save(order)
                order.domainEvents().forEach { event ->
                    eventPublisher.publishEvent(event)
                }
                if (failAfterPublish) {
                    throw IllegalStateException("Failing after event publication for rollback verification")
                }
                order
            }
        } catch (e: Exception) {
            if (order.domainEvents().isNotEmpty()) {
                throw OrderHandoffFailedException(order, e)
            }
            throw e
        } ?: error("Order transaction returned no aggregate")

        saved.clearDomainEvents()
        return saved
    }
}
