package io.bluetape4k.exposed.jdbc.caffeine.snapshot.readme

// README-CANONICAL-JDBC-BEGIN
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.JdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.jdbcCaffeineSnapshotCache
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageInvalidation
import io.bluetape4k.exposed.jdbc.caffeine.snapshot.stageSnapshot
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.io.Serializable

data class JdbcOrderRow(val id: Long, val description: String)

data class JdbcOrderSnapshot(val id: Long, val description: String) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private val jdbcOrderSnapshotCache = jdbcCaffeineSnapshotCache<Long, JdbcOrderSnapshot>(
    CaffeineSnapshotCacheConfig(
        snapshot = SnapshotCacheConfig(namespace = "orders:v1", schemaVersion = "order-dto-v1"),
    ),
)

fun JdbcTransaction.cacheOrderSnapshot(
    id: Long,
    loadFromDatabase: JdbcTransaction.(Long) -> JdbcOrderRow,
): CacheSnapshot<JdbcOrderSnapshot> {
    val lookup = jdbcOrderSnapshotCache.lookup(id)
    lookup.snapshot?.let { return it }
    val row = loadFromDatabase(id)
    return stageSnapshot(
        cache = jdbcOrderSnapshotCache,
        miss = requireNotNull(lookup.miss),
        source = row,
        mapper = CacheSnapshotMapper { CacheSnapshot(JdbcOrderSnapshot(it.id, it.description)) },
    )
}

fun JdbcTransaction.invalidateOrderSnapshot(id: Long) {
    stageInvalidation(jdbcOrderSnapshotCache, id)
}
// README-CANONICAL-JDBC-END
