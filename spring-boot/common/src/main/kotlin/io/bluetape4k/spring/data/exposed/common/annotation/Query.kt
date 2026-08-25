package io.bluetape4k.spring.data.exposed.common.annotation

/**
 * Repository 메서드에 raw SQL 쿼리를 지정합니다.
 *
 * @param value 실행할 SQL 쿼리 문자열입니다. 위치 기반 파라미터 `?1`, `?2` 등을 사용합니다.
 * @param countQuery 페이징에 사용할 count 쿼리입니다. 비워 두면 어댑터가 기본 규칙을 적용합니다.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Query(val value: String, val countQuery: String = "")
