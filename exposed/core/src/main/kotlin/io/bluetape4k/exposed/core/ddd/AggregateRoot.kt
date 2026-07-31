package io.bluetape4k.exposed.core.ddd

/**
 * Spring에 의존하지 않는 DDD aggregate root 계약입니다.
 *
 * ## 계약
 * Aggregate는 in-memory event buffer만 소유합니다. 이 buffer는 durable outbox,
 * publisher adapter, Exposed DAO lifecycle hook, Exposed DAO `EntityCache`,
 * in-memory queue, Spring Modulith publication store가 아닙니다.
 *
 * Command transaction이 활성화된 동안 repository adapter가 transaction-aware publisher에
 * snapshot을 전달할 수 있지만, commit 완료 전까지 aggregate buffer를 유지해야 합니다.
 * Durable outbox나 영속 retry queue는 별도의 integration 선택 사항입니다. 호출자가 이 계약을
 * 명시적으로 채택하지 않는 한 기존 repository에는 영향을 주지 않습니다.
 */
interface AggregateRoot<ID : Any> {

    /**
     * Aggregate의 안정적인 identifier입니다.
     */
    val id: ID

    /**
     * 기록된 domain event의 side effect 없는 read-only snapshot을 반환합니다.
     *
     * 비어 있지 않은 호출 결과는 매번 기록 순서를 유지한 독립 list입니다. 이 method는 aggregate
     * event buffer를 비우거나 변경하지 않습니다. 참조를 검증하는 transaction-aware publisher와
     * 함께 사용하는 구현은 [clearDomainEvents]가 성공할 때까지 event object reference를
     * 보존해야 합니다.
     */
    fun domainEvents(): List<DomainEvent<ID>>

    /**
     * 기록된 domain event를 반환하지 않고 비웁니다.
     *
     * 호출자가 소유한 폐기 작업이나 commit 완료 후 정리에 사용합니다. Transaction-aware
     * publisher가 등록된 snapshot을 소유하는 동안에는 rollback이나 완료 여부를 알 수 없는
     * 상황에서도 buffer를 보존해야 하므로 호출하면 안 됩니다.
     */
    fun clearDomainEvents()

    /**
     * 기록된 domain event를 기록 순서대로 [handoff]에 전달하고, [handoff]가 성공적으로 반환된
     * 뒤에만 buffer를 비웁니다.
     *
     * 호출자가 event를 outbox나 영속 retry queue 같은 durable owner로 이동할 준비가 된 뒤에만
     * 사용합니다. [handoff] 직후 buffer를 비우므로 transaction 완료까지 snapshot 소유권을
     * 유지하는 publisher와는 함께 사용할 수 없습니다. 이 method는 local buffer 연산이며 publish
     * 또는 persistence 경계가 아닙니다. [handoff]가 예외를 던지면 buffer는 그대로 유지됩니다.
     */
    fun drainDomainEvents(handoff: (List<DomainEvent<ID>>) -> Unit): List<DomainEvent<ID>>
}
