package io.bluetape4k.exposed.core.ddd

/**
 * Spring-neutral DDD aggregate root contract.
 *
 * ## Contract
 * The aggregate owns an in-memory event buffer only. The buffer is not a durable
 * outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
 * `EntityCache`, in-memory queue, or Spring Modulith publication store.
 * Repository adapters should snapshot events, commit the aggregate state, wait
 * for an after-transaction-commit or equivalent durability boundary, hand the
 * snapshot to a durable owner such as an outbox, persisted retry queue, or
 * transactionally recorded handoff, and only then clear or drain the buffer.
 * Existing repositories remain unaffected unless callers explicitly adopt these
 * contracts.
 */
interface AggregateRoot<ID : Any> {

    /**
     * Stable aggregate identifier.
     */
    val id: ID

    /**
     * Returns an immutable snapshot of currently recorded domain events.
     *
     * Calling this method does not clear the aggregate event buffer.
     */
    fun domainEvents(): List<DomainEvent<ID>>

    /**
     * Clears recorded domain events without returning them.
     *
     * Use this for caller-owned discard or rollback cleanup. Normal successful
     * persistence flows should not clear events before commit and durable
     * handoff acceptance.
     */
    fun clearDomainEvents()

    /**
     * Hands recorded domain events to [handoff] in recording order and clears
     * the buffer only after [handoff] returns successfully.
     *
     * Use this only after the caller is ready to move events into a durable
     * owner such as an outbox, persisted retry queue, or transactionally
     * recorded handoff. This method is a local buffer operation, not a publish
     * or persistence boundary. If [handoff] throws, the buffer remains intact.
     */
    fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>>
}
