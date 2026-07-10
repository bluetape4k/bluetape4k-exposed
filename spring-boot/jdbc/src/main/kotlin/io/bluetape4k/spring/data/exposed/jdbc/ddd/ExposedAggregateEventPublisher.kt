package io.bluetape4k.spring.data.exposed.jdbc.ddd

import io.bluetape4k.exposed.core.ddd.AggregateRoot
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.Ordered
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.IdentityHashMap

private val logger = LoggerFactory.getLogger(ExposedAggregateEventPublisher::class.java)
private val correlationKeys = listOf("traceId", "spanId", "requestId")
private val safeCorrelation = Regex("[A-Za-z0-9._:-]{1,128}")

/**
 * Publishes an aggregate's immutable domain-event snapshot inside the current command transaction.
 *
 * Save the aggregate and call [publishAfterSave] exactly once in the same active transaction:
 * ```kotlin
 * transactionTemplate.executeWithoutResult {
 *     orderRepository.save(order)
 *     aggregateEventPublisher.publishAfterSave(order)
 * }
 * ```
 * Empty aggregates are a no-op. Synchronous listeners run immediately; default `AFTER_COMMIT` listeners run
 * only after commit. Committed completion clears the registered buffer, while rollback or unknown completion
 * preserves it. Publication, duplicate registration, or snapshot mutation poisons the transaction even when
 * caller code catches the immediate failure. Event instances and payloads must remain deeply immutable.
 * Snapshot verification runs at [Ordered.LOWEST_PRECEDENCE]. Any synchronization that can mutate the aggregate
 * must use an earlier order; registering a same-order mutating synchronization after publication is unsupported.
 *
 * A synchronous publication failure is rethrown as the same [Throwable]. If caller code catches a lifecycle or
 * publication failure, the stored poison causes a stable [IllegalStateException] from `beforeCommit`, so the
 * transaction still cannot commit.
 *
 * `PROPAGATION_NESTED`/savepoint rollback and same-instance reuse across overlapping `REQUIRES_NEW` transactions
 * are unsupported. Listener database writes after commit require a new transaction.
 *
 * @throws IllegalStateException when the transaction, identity, or snapshot lifecycle contract is violated.
 */
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {

    /**
     * Hands [aggregate]'s current event snapshot to Spring and retains it until transaction completion.
     *
     * @throws IllegalStateException when the transaction or aggregate lifecycle contract is violated.
     */
    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>) {
        val currentSynchronization = currentSynchronization()
        currentSynchronization?.rejectReserved(aggregate)

        val events = aggregate.domainEvents()
        if (events.isEmpty()) return

        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "Domain event handoff requires active transaction synchronization"
        }
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Domain event handoff requires an actual active transaction"
        }

        val synchronization = currentSynchronization
            ?: AggregateEventTransactionSynchronization(this).also(
                TransactionSynchronizationManager::registerSynchronization
            )
        synchronization.rejectReserved(aggregate)
        synchronization.register(aggregate, events, captureCorrelation())

        try {
            events.forEach(applicationEventPublisher::publishEvent)
        } catch (failure: Throwable) {
            synchronization.poison("Domain event publication failed")
            throw failure
        }
    }

    private fun currentSynchronization(): AggregateEventTransactionSynchronization? {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return null

        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        return synchronizations.firstOrNull {
            it is AggregateEventTransactionSynchronization && it.owner === this
        } as AggregateEventTransactionSynchronization?
    }
}

internal class AggregateEventTransactionSynchronization(
    internal val owner: ExposedAggregateEventPublisher,
) : TransactionSynchronization {

    private val registrations = IdentityHashMap<AggregateRoot<*>, Registration>()
    private var poison: IllegalStateException? = null

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    internal fun rejectReserved(aggregate: AggregateRoot<*>) {
        if (registrations.containsKey(aggregate)) {
            poison("Aggregate domain events were registered more than once in the same transaction")
            throw requireNotNull(poison)
        }
    }

    internal fun register(
        aggregate: AggregateRoot<*>,
        snapshot: List<*>,
        correlation: Map<String, String>,
    ) {
        registrations[aggregate] = Registration(aggregate, snapshot, correlation)
    }

    internal fun poison(reason: String) {
        if (poison == null) {
            poison = IllegalStateException(reason)
        }
    }

    internal fun retainedSnapshotForTest(aggregate: AggregateRoot<*>): List<*>? =
        registrations[aggregate]?.snapshot

    override fun beforeCommit(readOnly: Boolean) {
        poison?.let { throw it }

        registrations.values.forEach { registration ->
            val current = registration.aggregate.domainEvents()
            val unchanged = current.size == registration.snapshot.size &&
                    current.indices.all { current[it] === registration.snapshot[it] }
            if (!unchanged) {
                poison("Aggregate domain events changed after publisher registration")
                throw requireNotNull(poison)
            }
        }
    }

    override fun afterCompletion(status: Int) {
        try {
            when (status) {
                TransactionSynchronization.STATUS_COMMITTED -> clearCommittedRegistrations()
                TransactionSynchronization.STATUS_UNKNOWN -> registrations.values.forEach {
                    logCompletionAnomaly("aggregate-event-completion-unknown", it)
                }
            }
        } finally {
            registrations.clear()
            poison = null
        }
    }

    private fun clearCommittedRegistrations() {
        registrations.values.forEach { registration ->
            try {
                registration.aggregate.clearDomainEvents()
            } catch (_: Throwable) {
                logCompletionAnomaly("aggregate-event-cleanup-failed", registration)
            }
        }
    }
}

private class Registration(
    val aggregate: AggregateRoot<*>,
    val snapshot: List<*>,
    val correlation: Map<String, String>,
)

private fun captureCorrelation(): Map<String, String> = buildMap {
    correlationKeys.forEach { key ->
        MDC.get(key)?.takeIf(safeCorrelation::matches)?.let { put(key, it) }
    }
}

private fun logCompletionAnomaly(category: String, registration: Registration) {
    val eventTypes = LinkedHashSet<String>()
    registration.snapshot.forEach { event ->
        if (event != null) {
            eventTypes += event::class.qualifiedName ?: event.javaClass.name
        }
    }
    val fields = linkedMapOf(
        "category" to category,
        "aggregateType" to (
                registration.aggregate::class.qualifiedName ?: registration.aggregate.javaClass.name
                ),
        "eventType" to eventTypes.joinToString(","),
        "eventCount" to registration.snapshot.size.toString(),
    )
    fields.putAll(registration.correlation)

    withSanitizedMdc(fields) {
        logger.error(category)
    }
}

private inline fun withSanitizedMdc(
    fields: Map<String, String>,
    block: () -> Unit,
) {
    val previous = MDC.getCopyOfContextMap()
    try {
        MDC.clear()
        fields.forEach(MDC::put)
        block()
    } finally {
        MDC.clear()
        if (previous != null) {
            MDC.setContextMap(previous)
        }
    }
}
