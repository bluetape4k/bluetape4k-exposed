package io.bluetape4k.exposed.jdbc.caffeine.repository

import java.io.Serializable

/**
 * Describes one cache write that has reached the JDBC persistence boundary.
 *
 * Instances are created after the database write or write-behind flush commits.
 * The value is passed to post-persistence hooks in the same order as the
 * repository flushed accepted write items. Duplicate identifiers are preserved
 * as separate writes.
 *
 * @param ID persisted identifier type
 * @param E persisted entity type
 * @property id identifier accepted by the cache repository
 * @property entity entity written for [id]
 */
data class CachePersistedWrite<ID: Any, E: Serializable>(
    val id: ID,
    val entity: E,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8453867830790425321L
    }
}
