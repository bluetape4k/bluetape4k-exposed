package io.bluetape4k.spring.data.exposed.common.repository.query

/**
 * 엔티티 쿼리의 NULL ID를 JDBC·R2DBC 어댑터에서 같은 계약으로 거부합니다.
 *
 * 드라이버의 ID 타입 변환과 결과 컬럼 조회는 각 어댑터가 담당합니다.
 */
fun requireEntityQueryId(value: Any?, methodName: String): Any =
    requireNotNull(value) { "@Query method '$methodName' returned null entity id" }
