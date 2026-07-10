package io.bluetape4k.spring.data.exposed.jdbc.ddd

import io.bluetape4k.exposed.core.ddd.AggregateRoot
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Hands aggregate domain events to Spring inside the current command transaction. */
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {

    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>) {
        val events = aggregate.domainEvents()
        if (events.isEmpty()) return

        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Domain event handoff requires active transaction synchronization"
        }
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Domain event handoff requires an actual active transaction"
        }

        TransactionSynchronizationManager.registerSynchronization(
            MinimalAggregateCompletionSynchronization(aggregate)
        )
        events.forEach(applicationEventPublisher::publishEvent)
    }
}

private class MinimalAggregateCompletionSynchronization(
    private val aggregate: AggregateRoot<*>,
) : TransactionSynchronization {

    override fun afterCompletion(status: Int) {
        if (status == TransactionSynchronization.STATUS_COMMITTED) {
            aggregate.clearDomainEvents()
        }
    }
}
