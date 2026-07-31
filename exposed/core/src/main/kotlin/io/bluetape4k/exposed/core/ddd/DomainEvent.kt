package io.bluetape4k.exposed.core.ddd

import java.time.Instant

/**
 * [AggregateRoot]가 발생시키는 Spring 중립적인 domain event입니다.
 *
 * ## 계약
 * 구현은 기록되거나 publisher에 등록된 뒤 깊은 불변성을 유지해야 하며, 불투명한 비민감
 * identifier와 최소한의 business fact만 포함해야 합니다. 호출자가 등록 후 변경할 수 있는
 * mutable collection이나 object를 보관하면 안 됩니다. Event payload에는 secret, credential,
 * token, natural key, 불필요한 personally identifiable information을 넣지 않습니다.
 * 이 계약 자체는 event를 publish, persist, replay, observe하지 않습니다.
 *
 * ```kotlin
 * import io.bluetape4k.exposed.core.ddd.DomainEvent
 * import java.io.Serializable
 * import java.time.Instant
 *
 * @JvmInline
 * value class OrderId(val value: Long) : Serializable {
 *     companion object {
 *         private const val serialVersionUID: Long = 1L
 *     }
 * }
 *
 * data class OrderPlaced(
 *     override val aggregateId: OrderId,
 *     override val occurredAt: Instant = Instant.now(),
 * ) : DomainEvent<OrderId>, Serializable {
 *     companion object {
 *         private const val serialVersionUID: Long = 1L
 *     }
 * }
 * ```
 */
interface DomainEvent<ID : Any> {

    /**
     * 이 event를 발생시킨 aggregate의 불투명한 비민감 identifier입니다.
     *
     * Secret, credential, token, natural key, 불필요한 personally identifiable information은
     * 사용하지 않습니다.
     */
    val aggregateId: ID

    /**
     * Event가 발생한 시각입니다.
     */
    val occurredAt: Instant
}
