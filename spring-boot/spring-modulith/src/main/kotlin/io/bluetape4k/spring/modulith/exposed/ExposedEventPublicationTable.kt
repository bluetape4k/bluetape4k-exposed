package io.bluetape4k.spring.modulith.exposed

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import kotlin.uuid.ExperimentalUuidApi

/**
 * Spring Modulith 이벤트 게시를 위한 Exposed 테이블 모델입니다.
 *
 * 기본 구조는 Spring Modulith 2.x JDBC schema v2를 따릅니다.
 * 따라서 저장 데이터를 변경하지 않고 이 모듈과 Spring Modulith JDBC 사이를 마이그레이션할 수 있습니다.
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
    val completionAttempts = integer("COMPLETION_ATTEMPTS").default(0)
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
