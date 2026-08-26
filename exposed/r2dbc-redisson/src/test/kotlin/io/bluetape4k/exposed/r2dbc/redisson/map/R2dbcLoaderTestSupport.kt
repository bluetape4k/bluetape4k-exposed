package io.bluetape4k.exposed.r2dbc.redisson.map

/**
 * 테스트가 생성한 loader의 producer/transaction lifecycle을 항상 정리합니다.
 *
 * `withTables`가 테이블을 삭제하기 전에 loader의 child job이 끝나야 다음 테스트가
 * 공유 H2 데이터베이스를 안전하게 사용할 수 있습니다.
 */
internal suspend inline fun <ID: Any, E: Any, T> R2dbcEntityMapLoader<ID, E>.useLoader(
    block: suspend () -> T,
): T = try {
    block()
} finally {
    closeAndJoin()
}
