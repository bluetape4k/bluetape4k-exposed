package io.bluetape4k.exposed.examples.modulith.orders

import io.bluetape4k.exposed.examples.modulith.orders.internal.OrderRepository
import io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class OrderApplicationService(
    private val orderRepository: OrderRepository,
    private val aggregateEventPublisher: ExposedAggregateEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    @Suppress("TooGenericExceptionCaught") // 트랜잭션 rollback 경계에서 모든 application failure를 보존한다.
    fun accept(
        command: AcceptOrderCommand,
        failAfterPublish: Boolean = false,
    ): Order {
        val order = Order.accept(command)
        return try {
            transactionTemplate.execute {
                orderRepository.save(order)
                aggregateEventPublisher.publishAfterSave(order)
                if (failAfterPublish) {
                    throw IllegalStateException("Failing after event publication for rollback verification")
                }
                order
            }
        } catch (e: RuntimeException) {
            if (order.domainEvents().isNotEmpty()) {
                return rethrowAfterPublishedEvent(order, e)
            }
            throw e
        }
    }

    private fun rethrowAfterPublishedEvent(order: Order, failure: RuntimeException): Nothing =
        throw OrderHandoffFailedException(order, failure)
}
