# exposed-r2dbc-caffeine

English | [한국어](./README.ko.md)

Exposed R2DBC repository with Caffeine local (in-process) cache. No JDBC dependency -- only `exposed-cache` is referenced.

> **See also**: [exposed-cache — Full Module Ecosystem & Interface Hierarchy](../exposed-cache/README.md)

## Architecture

The architecture view separates the coroutine-facing repository contract, the local `AsyncCache`, the Exposed R2DBC transaction path, and the write-behind worker. Use it to decide where cache configuration, table mapping, and database writes are owned.

![R2DBC Caffeine local cache architecture diagram](../../docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.png)

The sequence view follows the real message order: read-through hit and miss branches, write-through waiting for the DB write, write-behind returning after the queue send, and `close()` draining the final batch.

![R2DBC Caffeine cache sequence diagram](../../docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.png)

## Features

- **Read-Through**: Cache miss triggers DB load via R2DBC `suspendTransaction`, result cached in Caffeine
- **Write-Through**: `put()` updates both Caffeine and DB synchronously
- **Write-Behind**: `put()` updates Caffeine immediately, DB write is batched asynchronously via `Channel`
- **No JDBC dependency**: Pure R2DBC with `exposed-cache` interfaces only
- **Caffeine AsyncCache**: Non-blocking cache backed by `CompletableFuture`
- **Coroutine-native**: All DB operations use `suspendTransaction`

<!-- R2DBC-SNAPSHOT-CACHE -->
## Commit-safe R2DBC snapshot cache (opt-in)

`R2dbcCaffeineSnapshotCache` is an opt-in cache-only facade, separate from the repository cache above. It accepts
detached immutable DTOs and publishes a staged `CacheSnapshot` only after the current root `R2dbcTransaction` commits.
Rollback discards staged work, last mutation wins for a repeated key, and a process-local fence rejects a late fill
after a newer local mutation. Existing repository caches are not migrated.

Perform `lookup` before the database read so outstanding-miss capacity fails before R2DBC work. The returned
`SnapshotCacheMiss` is one-shot even when mapping or staging fails. `stageSnapshot` maps inside the current root
transaction and rejects nested/savepoint transactions. Snapshot fill requires `maxAttempts = 1`; application retry must
wrap the complete lookup + `suspendTransaction` + database-read sequence and obtain a fresh lookup each time.
`stageInvalidation` remains attempt-local and publishes once after the successful retry.

Post-transaction callbacks are non-suspending, cache-only, and perform no database writes. An earlier failing callback
can prevent publication and leave a stale value. Observe the bounded `SnapshotCacheFailureBuffer` and keep an
application-owned outbox or repair path. Commit-safe is not database/cache atomicity or crash durability.

### Canonical R2DBC example

The English and Korean blocks below are byte-for-byte equal to a compiled source-usage fixture.

<!-- README-CANONICAL-R2DBC-BEGIN -->
```kotlin
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
```
<!-- README-CANONICAL-R2DBC-END -->

## Usage

```kotlin
class ActorRepository(
    config: LocalCacheConfig = LocalCacheConfig.WRITE_THROUGH,
) : AbstractR2dbcCaffeineRepository<Long, ActorRecord>(config) {

    override val table = ActorTable

    override suspend fun ResultRow.toEntity() = toActorRecord()

    override fun UpdateStatement.updateEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
        this[ActorTable.email] = entity.email
    }

    override fun BatchInsertStatement.insertEntity(entity: ActorRecord) {
        this[ActorTable.firstName] = entity.firstName
        this[ActorTable.lastName] = entity.lastName
        this[ActorTable.email] = entity.email
    }

    override fun extractId(entity: ActorRecord) = entity.id
}

// Read-Through (cache miss -> DB load)
val actor = repository.get(1L)

// Write-Through (cache + DB)
repository.put(1L, updatedActor)

// Write-Behind (cache immediate, DB async batch)
val behindConfig = LocalCacheConfig(writeMode = CacheWriteMode.WRITE_BEHIND)
val behindRepo = ActorRepository(behindConfig)
behindRepo.put(1L, updatedActor)  // returns immediately
```

## Dependencies

| Dependency | Purpose |
|---|---|
| `exposed-r2dbc` | Exposed R2DBC transaction support |
| `exposed-cache` | `R2dbcCacheRepository`, `LocalCacheConfig`, `CacheMode` |
| `bluetape4k-coroutines` | Coroutines utilities |
| `com.github.ben-manes.caffeine:caffeine` | In-process async cache |

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-caffeine")
}
```

The application owns the `bluetape4k-dependencies` BOM version, so the module coordinate is intentionally versionless.
