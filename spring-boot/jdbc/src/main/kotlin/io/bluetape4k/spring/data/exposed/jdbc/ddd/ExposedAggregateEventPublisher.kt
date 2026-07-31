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
 * 현재 command transaction 안에서 aggregate의 불변 domain-event snapshot을 발행합니다.
 *
 * 동일한 활성 transaction에서 aggregate를 저장하고 [publishAfterSave]를 정확히 한 번 호출합니다.
 * ```kotlin
 * transactionTemplate.executeWithoutResult {
 *     orderRepository.save(order)
 *     aggregateEventPublisher.publishAfterSave(order)
 * }
 * ```
 * event가 없는 aggregate는 아무 작업도 하지 않습니다. synchronous listener는 즉시 실행되고 기본 `AFTER_COMMIT`
 * listener는 commit 후에만 실행됩니다. commit 완료 시 등록 buffer를 비우지만 rollback 또는 알 수 없는 완료 상태에서는
 * 유지합니다. 호출자 코드가 즉시 발생한 실패를 잡더라도 발행, 중복 등록 또는 snapshot 변경은 transaction을 오염시킵니다.
 * event instance와 payload는 깊은 불변성을 유지해야 합니다. 중복 방지는 현재 transaction 안의 aggregate object identity를
 * 사용합니다. 같은 aggregate id를 가진 별도 instance는 독립 등록이며 애플리케이션 수준 idempotency가 필요합니다.
 * snapshot 검증은 [Ordered.LOWEST_PRECEDENCE]에서 실행됩니다. aggregate를 변경할 수 있는 synchronization은 더 이른
 * order를 사용해야 하며 발행 후 같은 order의 변경 synchronization을 등록하는 것은 지원하지 않습니다.
 *
 * synchronous 발행 실패는 같은 [Throwable]로 다시 던집니다. 호출자 코드가 lifecycle 또는 발행 실패를 잡으면 저장된
 * poison이 `beforeCommit`에서 안정적인 [IllegalStateException]을 발생시켜 transaction을 commit할 수 없게 합니다.
 *
 * `PROPAGATION_NESTED`/savepoint rollback과 겹치는 `REQUIRES_NEW` transaction 사이의 동일 instance 재사용은
 * 지원하지 않습니다. commit 후 listener의 database 쓰기에는 새 transaction이 필요합니다.
 *
 * @throws IllegalStateException transaction, identity 또는 snapshot lifecycle 계약을 위반한 경우
 */
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {

    /**
     * [aggregate]의 현재 event snapshot을 Spring에 전달하고 transaction 완료 시까지 유지합니다.
     *
     * @throws IllegalStateException transaction 또는 aggregate lifecycle 계약을 위반한 경우
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
            } catch (_: Exception) {
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
