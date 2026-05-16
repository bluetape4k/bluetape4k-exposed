package io.bluetape4k.spring.modulith.exposed

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exposed table model for Spring Modulith event publications.
 *
 * The default shape follows Spring Modulith 2.x JDBC schema v2 so applications
 * can migrate between this module and Spring Modulith JDBC without changing
 * stored data.
 */
@OptIn(ExperimentalUuidApi::class)
class ExposedEventPublicationTable(
    tableName: String = DEFAULT_TABLE_NAME,
) : Table(tableName) {

    val id = uuid("ID")
    val listenerId = text("LISTENER_ID")
    val eventType = text("EVENT_TYPE")
    val serializedEvent = text("SERIALIZED_EVENT")
    val publicationDate = timestamp("PUBLICATION_DATE")
    val completionDate = timestamp("COMPLETION_DATE").nullable()
    val status = varchar("STATUS", 20).nullable()
    val completionAttempts = integer("COMPLETION_ATTEMPTS").nullable()
    val lastResubmissionDate = timestamp("LAST_RESUBMISSION_DATE").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    init {
        index(customIndexName = "${tableName}_completion_date_idx", columns = arrayOf(completionDate))
    }

    companion object {
        const val DEFAULT_TABLE_NAME: String = "EVENT_PUBLICATION"
        const val DEFAULT_ARCHIVE_TABLE_NAME: String = "EVENT_PUBLICATION_ARCHIVE"
    }
}
