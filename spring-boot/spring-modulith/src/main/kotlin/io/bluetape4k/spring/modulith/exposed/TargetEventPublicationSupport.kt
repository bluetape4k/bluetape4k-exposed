package io.bluetape4k.spring.modulith.exposed

import io.bluetape4k.support.requireNotBlank
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import java.time.Instant

/**
 * Kotlin 스타일 팩토리 문법으로 Spring Modulith [PublicationTargetIdentifier]를 생성합니다.
 *
 * 계약:
 * - Spring Modulith에 위임하기 전에 빈 listener ID를 거부합니다.
 * - Kotlin 호출부에서 `PublicationTargetIdentifier.of(...)`를 직접 사용하지 않게 합니다.
 *
 * 예:
 * ```kotlin
 * val targetIdentifier = publicationTargetIdentifierOf("listener.order-submitted")
 * ```
 */
fun publicationTargetIdentifierOf(value: String): PublicationTargetIdentifier =
    PublicationTargetIdentifier.of(value.requireNotBlank("value"))

/**
 * Kotlin 스타일 팩토리 문법으로 Spring Modulith [TargetEventPublication]을 생성합니다.
 *
 * 계약:
 * - Spring Modulith의 Java 정적 팩토리에 위임합니다.
 * - Kotlin 호출부에서 `TargetEventPublication.of(...)`를 직접 사용하지 않게 합니다.
 *
 * 예:
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
