package io.bluetape4k.spring.data.exposed.jdbc.annotation

/** JDBC 저장소의 기존 raw SQL annotation facade입니다. */
@Deprecated(
    message = "common.annotation.Query를 사용하십시오.",
    replaceWith = ReplaceWith("Query", "io.bluetape4k.spring.data.exposed.common.annotation"),
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Query(val value: String, val countQuery: String = "")
