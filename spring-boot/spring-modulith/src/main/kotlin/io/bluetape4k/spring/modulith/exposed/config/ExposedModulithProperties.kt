package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.events.support.CompletionMode

/**
 * Configuration properties for the Exposed-backed Spring Modulith event
 * publication repository.
 */
@ConfigurationProperties(prefix = "bluetape4k.spring.modulith.exposed")
data class ExposedModulithProperties(
    /**
     * Active event publication table name.
     */
    val tableName: String = ExposedEventPublicationTable.DEFAULT_TABLE_NAME,

    /**
     * Archive table name used when Spring Modulith completion mode is ARCHIVE.
     */
    val archiveTableName: String = ExposedEventPublicationTable.DEFAULT_ARCHIVE_TABLE_NAME,

    /**
     * Completion behavior. Defaults to Spring Modulith's standard UPDATE mode.
     */
    val completionMode: CompletionMode = CompletionMode.UPDATE,

    /**
     * Whether to create the publication table with Exposed SchemaUtils at startup.
     * Production applications should usually prefer Flyway or Liquibase.
     */
    val initializeSchema: Boolean = false,
)
