package io.bluetape4k.exposed.core.ddd

/**
 * Minimal base implementation for [AggregateRoot] event recording.
 *
 * ## Contract
 * This class is intentionally not thread-safe. Call [recordDomainEvent],
 * [domainEvents], [clearDomainEvents], and [drainDomainEvents] from one
 * command/transaction boundary at a time. The class does not publish, persist,
 * observe, or replay events, and it does not treat Exposed DAO `EntityCache`, a
 * database flush that can still roll back, or in-memory queues as durable event
 * boundaries. Event payloads should follow the [DomainEvent] guidance for
 * opaque, non-sensitive identifiers and minimal business facts.
 */
abstract class AbstractAggregateRoot<ID : Any> : AggregateRoot<ID> {

    abstract override val id: ID

    private var recordedDomainEvents: MutableList<DomainEvent<ID>>? = null

    override fun domainEvents(): List<DomainEvent<ID>> {
        val events = recordedDomainEvents ?: return emptyList()
        if (events.isEmpty()) return emptyList()
        return events.toList()
    }

    override fun clearDomainEvents() {
        recordedDomainEvents = null
    }

    override fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>> {
        val events = recordedDomainEvents ?: return emptyList()
        if (events.isEmpty()) {
            recordedDomainEvents = null
            return emptyList()
        }

        val snapshot = events.toList()
        handoff(snapshot)
        recordedDomainEvents = null
        return snapshot
    }

    /**
     * Records [event] for this aggregate.
     *
     * The event aggregate id must match [id]. Mismatches are caller errors.
     */
    protected fun recordDomainEvent(event: DomainEvent<ID>) {
        require(event.aggregateId == id) {
            "Domain event aggregateId must match aggregate id"
        }
        val events = recordedDomainEvents ?: mutableListOf<DomainEvent<ID>>().also {
            recordedDomainEvents = it
        }
        events += event
    }
}
