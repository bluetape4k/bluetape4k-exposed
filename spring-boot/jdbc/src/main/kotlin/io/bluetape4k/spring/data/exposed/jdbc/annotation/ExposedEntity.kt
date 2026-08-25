package io.bluetape4k.spring.data.exposed.jdbc.annotation

/**
 * JDBC 저장소의 기존 엔티티 annotation facade입니다.
 *
 * 새 코드에서는 [io.bluetape4k.spring.data.exposed.common.annotation.ExposedEntity]를
 * 사용하십시오. JDBC 스캐너는 두 annotation을 모두 인식합니다.
 */
@Deprecated(
    message = "common.annotation.ExposedEntity를 사용하십시오.",
    replaceWith = ReplaceWith("ExposedEntity", "io.bluetape4k.spring.data.exposed.common.annotation"),
)
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ExposedEntity
