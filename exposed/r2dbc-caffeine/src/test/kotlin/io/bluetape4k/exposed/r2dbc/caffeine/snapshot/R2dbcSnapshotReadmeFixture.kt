package io.bluetape4k.exposed.r2dbc.caffeine.snapshot.readme

// README-CANONICAL-R2DBC-BEGIN
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshot
import io.bluetape4k.exposed.cache.snapshot.CacheSnapshotMapper
import io.bluetape4k.exposed.cache.snapshot.CaffeineSnapshotCacheConfig
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheConfig
import io.bluetape4k.exposed.r2dbc.caffeine.snapshot.R2dbcCaffeineSnapshotCache
import io.bluetape4k.exposed.r2dbc.caffeine.snapshot.r2dbcCaffeineSnapshotCache
import io.bluetape4k.exposed.r2dbc.caffeine.snapshot.stageInvalidation
import io.bluetape4k.exposed.r2dbc.caffeine.snapshot.stageSnapshot
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import java.io.Serializable

data class R2dbcOrderRow(val id: Long, val description: String)

data class R2dbcOrderSnapshot(val id: Long, val description: String) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private val r2dbcOrderSnapshotCache = r2dbcCaffeineSnapshotCache<Long, R2dbcOrderSnapshot>(
    CaffeineSnapshotCacheConfig(
        snapshot = SnapshotCacheConfig(namespace = "orders:v1", schemaVersion = "order-dto-v1"),
    ),
)

suspend fun R2dbcTransaction.cacheOrderSnapshot(
    id: Long,
    loadFromDatabase: suspend R2dbcTransaction.(Long) -> R2dbcOrderRow,
): CacheSnapshot<R2dbcOrderSnapshot> {
    val lookup = r2dbcOrderSnapshotCache.lookup(id)
    lookup.snapshot?.let { return it }
    val row = loadFromDatabase(id)
    return stageSnapshot(
        cache = r2dbcOrderSnapshotCache,
        miss = requireNotNull(lookup.miss),
        source = row,
        mapper = CacheSnapshotMapper { CacheSnapshot(R2dbcOrderSnapshot(it.id, it.description)) },
    )
}

fun R2dbcTransaction.invalidateOrderSnapshot(id: Long) {
    stageInvalidation(r2dbcOrderSnapshotCache, id)
}
// README-CANONICAL-R2DBC-END
