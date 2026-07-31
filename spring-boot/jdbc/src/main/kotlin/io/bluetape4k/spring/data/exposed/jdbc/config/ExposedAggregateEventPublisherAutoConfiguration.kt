package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.exposed.core.ddd.AggregateRoot
import io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisher
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 하나의 transaction manager를 선택할 수 있을 때 transaction-aware aggregate event 전달을 자동 설정합니다.
 *
 * manager가 하나이거나 여러 manager 중 정확히 하나에 `@Primary`가 지정된 context에는
 * [ExposedAggregateEventPublisher]를 제공합니다. 여러 manager가 모호한 context는 publisher를 명시적으로 선언해야 합니다.
 * publisher는 manager를 선택하거나 보관하지 않으며, 호출자가 일치하는 repository와 command transaction 경계를
 * 사용할 책임을 집니다.
 */
@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])
@ConditionalOnClass(
    AggregateRoot::class,
    ApplicationEventPublisher::class,
    TransactionSynchronizationManager::class,
)
@ConditionalOnSingleCandidate(PlatformTransactionManager::class)
class ExposedAggregateEventPublisherAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun exposedAggregateEventPublisher(
        applicationEventPublisher: ApplicationEventPublisher,
    ): ExposedAggregateEventPublisher = ExposedAggregateEventPublisher(applicationEventPublisher)
}
