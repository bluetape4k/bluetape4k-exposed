package io.bluetape4k.spring.modulith.exposed

import io.bluetape4k.support.requireNotBlank
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import java.time.Instant

/**
 * Creates a Spring Modulith [PublicationTargetIdentifier] with Kotlin-style factory syntax.
 *
 * Contract:
 * - Rejects blank listener ids before delegating to Spring Modulith.
 * - Keeps Kotlin call sites free from `PublicationTargetIdentifier.of(...)`.
 *
 * Example:
 * ```kotlin
 * val targetIdentifier = publicationTargetIdentifierOf("listener.order-submitted")
 * ```
 */
fun publicationTargetIdentifierOf(value: String): PublicationTargetIdentifier =
    PublicationTargetIdentifier.of(value.requireNotBlank("value"))

/**
 * Creates a Spring Modulith [TargetEventPublication] with Kotlin-style factory syntax.
 *
 * Contract:
 * - Delegates to Spring Modulith's Java static factory.
 * - Keeps call sites in Kotlin code free from `TargetEventPublication.of(...)`.
 *
 * Example:
 * ```kotlin
 * val publication = targetEventPublicationOf(
 *     event = "order-1",
 *     targetIdentifier = publicationTargetIdentifierOf("listener.order-submitted"),
 *     publicationDate = Instant.now(),
 * )
 * ```
 */
fun targetEventPublicationOf(
    event: Any,
    targetIdentifier: PublicationTargetIdentifier,
    publicationDate: Instant,
): TargetEventPublication =
    TargetEventPublication.of(event, targetIdentifier, publicationDate)
