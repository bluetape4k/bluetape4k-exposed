package io.bluetape4k.exposed.core.ddd

/**
 * Spring-neutral DDD aggregate root contract.
 *
 * ## Contract
 * The aggregate owns an in-memory event buffer only. The buffer is not a durable
 * outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
 * `EntityCache`, in-memory queue, or Spring Modulith publication store.
 * Repository adapters may hand a snapshot to a transaction-aware publisher
 * while the command transaction is active, but they must retain the aggregate
 * buffer until committed completion. A durable outbox or persisted retry queue
 * is a separate integration choice. Existing repositories remain unaffected
 * unless callers explicitly adopt these contracts.
 */
interface AggregateRoot<ID : Any> {

    /**
     * Stable aggregate identifier.
     */
    val id: ID

    /**
     * Returns a side-effect-free immutable snapshot of recorded domain events.
     *
     * Each call returns a distinct list in recording order. Event object
     * references remain unchanged until [clearDomainEvents] succeeds. Calling
     * this method does not clear or mutate the aggregate event buffer.
     */
    fun domainEvents(): List<DomainEvent<ID>>

    /**
     * Clears recorded domain events without returning them.
     *
     * Use this for caller-owned discard or committed-completion cleanup. It is
     * forbidden between registration with `ExposedAggregateEventPublisher` and
     * transaction completion because rollback and unknown completion must
     * preserve the buffer.
     */
    fun clearDomainEvents()

    /**
     * Hands recorded domain events to [handoff] in recording order and clears
     * the buffer only after [handoff] returns successfully.
     *
     * Use this only after the caller is ready to move events into a durable
     * owner such as an outbox or persisted retry queue. This method is
     * incompatible with `ExposedAggregateEventPublisher`: it clears immediately
     * after [handoff], before transaction completion. It is a local buffer
     * operation, not a publish or persistence boundary. If [handoff] throws,
     * the buffer remains intact.
     */
    fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>>
}
