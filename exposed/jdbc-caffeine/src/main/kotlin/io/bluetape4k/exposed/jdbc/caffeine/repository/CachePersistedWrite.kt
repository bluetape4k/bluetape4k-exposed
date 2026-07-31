package io.bluetape4k.exposed.jdbc.caffeine.repository

import java.io.Serializable

/**
 * JDBC 영속성 경계에 도달한 cache write 하나를 나타냅니다.
 *
 * database write 또는 write-behind flush가 commit된 뒤 인스턴스를 생성합니다.
 * Repository가 수락한 write item을 flush한 순서 그대로 영속화 이후 hook에 전달합니다.
 * 중복 identifier도 별도의 write로 유지합니다.
 *
 * @param ID 영속화된 identifier 타입
 * @param E 영속화된 entity 타입
 * @property id cache Repository가 수락한 identifier
 * @property entity [id]에 기록한 entity
 */
data class CachePersistedWrite<ID: Any, E: Serializable>(
    val id: ID,
    val entity: E,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8453867830790425321L
    }
}
