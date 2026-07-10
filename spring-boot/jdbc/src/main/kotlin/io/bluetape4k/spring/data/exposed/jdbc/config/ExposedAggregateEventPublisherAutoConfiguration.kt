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
 * Auto-configures transaction-aware aggregate event handoff when one transaction manager is selectable.
 *
 * A context with one manager, or multiple managers with exactly one `@Primary`, receives an
 * [ExposedAggregateEventPublisher]. Ambiguous multi-manager contexts must declare the publisher explicitly.
 * The publisher does not select or retain a manager; callers remain responsible for using matching repository
 * and command transaction boundaries.
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
