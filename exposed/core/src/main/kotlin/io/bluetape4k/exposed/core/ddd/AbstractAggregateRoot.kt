package io.bluetape4k.exposed.core.ddd

/**
 * [AggregateRoot]의 event 기록 기능을 제공하는 최소 base 구현입니다.
 *
 * ## 계약
 * 이 class는 의도적으로 thread-safe하지 않습니다. 한 시점에는 하나의 command transaction만
 * aggregate instance와 event buffer를 소유해야 하며, 소유 범위가 겹치는 동시 사용이나 재사용은
 * 지원하지 않습니다. [recordDomainEvent], [domainEvents], [clearDomainEvents],
 * [drainDomainEvents]는 반드시 이 소유권 경계 안에서만 호출해야 합니다.
 *
 * 이 class는 event를 publish, persist, observe, replay하지 않습니다. 또한 Exposed DAO
 * `EntityCache`, rollback 가능한 database flush, in-memory queue를 durable event 경계로
 * 취급하지 않습니다. Event payload는 [DomainEvent] 지침에 따라 불투명한 비민감 identifier와
 * 최소한의 business fact만 포함해야 합니다.
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
     * 이 aggregate에 [event]를 기록합니다.
     *
     * Event의 aggregate id는 [id]와 같아야 하며, 불일치는 호출자 오류입니다.
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
