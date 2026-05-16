package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfiguration
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer

/**
 * Auto-configuration for the Exposed-backed Spring Modulith event publication
 * repository.
 */
@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.modulith.events.core.EventPublicationRepository",
        "org.jetbrains.exposed.v1.jdbc.Database",
    ]
)
@ConditionalOnBean(name = ["springTransactionManager"])
@EnableConfigurationProperties(ExposedModulithProperties::class)
@Configuration(proxyBeanMethods = false)
class ExposedModulithAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["eventPublicationTable"])
    fun eventPublicationTable(properties: ExposedModulithProperties): ExposedEventPublicationTable =
        ExposedEventPublicationTable(properties.tableName)

    @Bean
    @ConditionalOnMissingBean(name = ["eventPublicationArchiveTable"])
    fun eventPublicationArchiveTable(properties: ExposedModulithProperties): ExposedEventPublicationTable =
        ExposedEventPublicationTable(properties.archiveTableName)

    @Bean
    @ConditionalOnMissingBean(EventPublicationRepository::class)
    @ConditionalOnBean(EventSerializer::class)
    fun exposedEventPublicationRepository(
        eventPublicationTable: ExposedEventPublicationTable,
        eventPublicationArchiveTable: ExposedEventPublicationTable,
        serializer: EventSerializer,
        properties: ExposedModulithProperties,
    ): ExposedEventPublicationRepository =
        ExposedEventPublicationRepository(
            table = eventPublicationTable,
            archiveTable = eventPublicationArchiveTable,
            serializer = serializer,
            completionMode = properties.completionMode,
        )

    @Bean
    @ConditionalOnProperty(
        prefix = "bluetape4k.spring.modulith.exposed",
        name = ["initialize-schema"],
        havingValue = "true",
    )
    fun exposedEventPublicationSchemaInitializer(
        repository: ExposedEventPublicationRepository,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton { repository.createSchema() }
}
