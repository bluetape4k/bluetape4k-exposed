package io.bluetape4k.exposed.core.ddd

import java.time.Instant

/**
 * Spring-neutral domain event emitted by an [AggregateRoot].
 *
 * ## Contract
 * Implementations should be deeply immutable after recording or publisher
 * registration and carry only opaque, non-sensitive identifiers and minimal
 * business facts. Do not retain mutable collections or objects that callers can
 * change after registration. Do not put secrets, credentials, tokens, natural
 * keys, or unnecessary personally identifiable information into event
 * payloads. This contract does not publish, persist, replay, or observe events.
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
     * Opaque, non-sensitive identifier of the aggregate that emitted this event.
     *
     * Avoid secrets, credentials, tokens, natural keys, and unnecessary
     * personally identifiable information.
     */
    val aggregateId: ID

    /**
     * Time when the event occurred.
     */
    val occurredAt: Instant
}
