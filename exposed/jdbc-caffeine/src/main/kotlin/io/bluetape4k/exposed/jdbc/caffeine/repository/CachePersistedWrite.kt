package io.bluetape4k.exposed.jdbc.caffeine.repository

import java.io.Serializable

/**
 * JDBC persistence boundary에 도달한 하나의 cache write를 표현합니다.
 *
 * Database write 또는 write-behind flush가 commit된 뒤 생성됩니다.
 * Repository가 접수된 write 항목을 flush한 순서대로 post-persistence hook에 전달하며,
 * 중복 identifier도 각각 별도의 write로 유지합니다.
 *
 * @param ID 영속화된 identifier type입니다.
 * @param E 영속화된 entity type입니다.
 * @property id cache repository가 접수한 identifier입니다.
 * @property entity [id]에 대해 기록된 entity입니다.
 */
data class CachePersistedWrite<ID: Any, E: Serializable>(
    /** Cache repository가 영속화한 identifier입니다. */
    val id: ID,
    /** [id]에 연결되어 영속화된 entity입니다. */
    val entity: E,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8453867830790425321L
    }
}
