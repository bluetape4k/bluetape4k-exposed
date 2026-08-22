package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.redisson.api.map.MapLoader

/**
 * JDBC 트랜잭션 안에서 DB 조회 함수를 실행하는 Redisson [MapLoader] 구현입니다.
 *
 * ## 동작/계약
 * - [load]는 `transaction { ... }` 안에서 [loadByIdFromDB]를 실행해 단건 엔티티를 읽습니다.
 * - 기본 [loadAllKeys]는 Exposed transaction의 statement `queryTimeout`을 30초로
 *   설정한 뒤 [loadAllIdsFromDB]를 실행합니다. Exposed `queryTimeout`의 단위는 초이며,
 *   전체 키 materialization에 적용하는 외부 timeout과는 별개입니다.
 * - 입력/출력 객체를 mutate하지 않고, DB 조회 결과를 그대로 반환합니다.
 * - 운영 로그에는 caller-owned ID나 엔티티 payload를 기록하지 않습니다.
 *
 * ```kotlin
 * val loader = EntityMapLoader<Long, UserRecord>(
 *     loadByIdFromDB = { id -> repo.findByIdFromDb(id) },
 *     loadAllIdsFromDB = { repo.findAllFromDb().map { it.id } }
 * )
 * // loader.loadAllKeys() != null
 * ```
 *
 * @param loadByIdFromDB ID로 엔티티를 로드하는 함수입니다.
 * @param loadAllIdsFromDB 모든 ID를 로드하는 함수입니다.
 */
open class EntityMapLoader<ID: Any, E: Any>(
    private val loadByIdFromDB: (ID) -> E?,
    private val loadAllIdsFromDB: () -> Collection<ID>,
): MapLoader<ID, E> {
    companion object: KLogging() {
        private const val DEFAULT_QUERY_TIMEOUT_SECONDS = 30
    }

    /** 단일 키를 DB에서 로드합니다. */
    override fun load(id: ID): E? =
        transaction {
            log.debug { "DB에서 단건 엔티티 로드를 시작합니다." }
            loadByIdFromDB(id)
                .apply {
                    log.debug { "DB에서 단건 엔티티 로드를 완료했습니다. found=${this != null}" }
                }
        }

    /** 모든 키를 DB에서 로드합니다. */
    override fun loadAllKeys(): Iterable<ID>? =
        transaction {
            log.debug { "DB에서 모든 id 를 로드합니다..." }
            queryTimeout = DEFAULT_QUERY_TIMEOUT_SECONDS
            loadAllIdsFromDB()
        }
}
