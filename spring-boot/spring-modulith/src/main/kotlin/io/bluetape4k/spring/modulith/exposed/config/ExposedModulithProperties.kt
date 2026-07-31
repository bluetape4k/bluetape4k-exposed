package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationTable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.events.support.CompletionMode

/**
 * Exposed 기반 Spring Modulith 이벤트 게시 저장소의 구성 속성입니다.
 */
@ConfigurationProperties(prefix = "bluetape4k.spring.modulith.exposed")
data class ExposedModulithProperties(
    /**
     * 활성 이벤트 게시 테이블 이름입니다.
     */
    val tableName: String = ExposedEventPublicationTable.DEFAULT_TABLE_NAME,

    /**
     * Spring Modulith 완료 모드가 ARCHIVE일 때 사용할 보관 테이블 이름입니다.
     */
    val archiveTableName: String = ExposedEventPublicationTable.DEFAULT_ARCHIVE_TABLE_NAME,

    /**
     * 완료 동작입니다. 기본값은 Spring Modulith 표준 UPDATE 모드입니다.
     */
    val completionMode: CompletionMode = CompletionMode.UPDATE,

    /**
     * 시작 시 Exposed SchemaUtils로 게시 테이블을 생성할지 여부입니다.
     * 운영 애플리케이션에서는 일반적으로 Flyway 또는 Liquibase를 우선 사용해야 합니다.
     */
    val initializeSchema: Boolean = false,
)
