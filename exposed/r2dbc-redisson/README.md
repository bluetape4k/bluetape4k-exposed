# Module exposed-r2dbc-redisson

English | [한국어](./README.ko.md)

Combines Exposed R2DBC with Redisson caching to implement coroutine-friendly Read-Through, Write-Through, and Write-Behind cache patterns.

## Overview

`exposed-r2dbc-redisson` integrates Exposed R2DBC with the [Redisson](https://github.com/redisson/redisson) Redis client, so repositories can cache database rows in Redis without leaving a coroutine-first API. Repository operations stay `suspend`, while Redisson `MapLoaderAsync` and `MapWriterAsync` adapters bridge cache misses and cache writes into Exposed R2DBC `suspendTransaction` blocks.

### Key Features

- **Async MapLoader/MapWriter support**: Integration with Redisson `AsyncMapLoader`/`AsyncMapWriter`
    - `loadAllKeys()` iterates reliably in ascending primary key order
- **Repository abstraction**: Common cache + DB access pattern (`R2dbcRedissonRepository`)
- **Coroutines-native repository API**: Cache and repository calls are `suspend` functions; Redisson SPI adapters remain async internally
- **Near Cache support**: Two-tier Local Cache + Redis caching
- **Read-Through/Write-Through/Write-Behind**: Multiple cache patterns supported

## Adding Dependencies

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-redisson:${version}")
    implementation("org.redisson:redisson:3.37.0")

    // R2DBC driver
    implementation("org.postgresql:r2dbc-postgresql:1.0.5.RELEASE")
}
```

## Architecture Overview

The architecture view separates the suspend repository API, writer-backed Redisson maps, the cache-only invalidation path, and the R2DBC loader/writer adapters. It also highlights the main durability rule: invalidation removes Redis state by default and deletes database rows only when `deleteFromDBOnInvalidate` is enabled.

![R2DBC Redisson coroutine cache architecture diagram](../../docs/images/readme-diagrams/exposed-r2dbc-redisson-diagram-01.png)

## Class Diagrams

### R2DBC Redisson Repository Hierarchy

The class diagram is limited to repository contracts and adapter responsibilities. Concrete repositories provide the table mapping, ID extraction, row-to-entity mapping, and write DSL hooks for their own serializable DTOs.

![R2DBC Redisson repository hierarchy diagram](../../docs/images/readme-diagrams/exposed-r2dbc-redisson-diagram-02.png)

## Basic Usage

### 1. Implementing R2dbcRedissonRepository

Extend `AbstractR2dbcRedissonRepository` to implement an async cache Repository.

```kotlin
import io.bluetape4k.exposed.r2dbc.redisson.repository.AbstractR2dbcRedissonRepository
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.redisson.api.RedissonClient

// Entity (must implement java.io.Serializable)
data class UserRecord(
    val id: Long,
    val name: String,
    val email: String,
): java.io.Serializable

object UserTable: LongIdTable("users") {
    val name = varchar("name", 100)
    val email = varchar("email", 200)
}

class UserR2dbcRedissonRepository(
    redissonClient: RedissonClient,
    config: RedissonCacheConfig,
): AbstractR2dbcRedissonRepository<Long, UserRecord>(
    redissonClient = redissonClient,
    config = config,
) {
    override val table = UserTable

    override fun extractId(entity: UserRecord): Long = entity.id

    override suspend fun ResultRow.toEntity() = UserRecord(
        id    = this[UserTable.id].value,
        name  = this[UserTable.name],
        email = this[UserTable.email],
    )

    // Required for Write-Through mode
    override fun UpdateStatement.updateEntity(entity: UserRecord) {
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }

    override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }
}

// Usage (all methods are suspend)
val repo = UserR2dbcRedissonRepository(redissonClient, RedissonCacheConfig.readOnly())

// Retrieve from cache (auto-loads from DB on miss)
val user = repo.get(1L)

// Bypass cache and query DB directly
val freshUser = repo.findByIdFromDb(1L)

// Load from DB and populate cache
val all = repo.findAll(limit = 100)

// Store in cache
user?.let { repo.put(it.id, it.copy(name = "Jane")) }
val usersById = users.associateBy { it.id }
repo.putAll(usersById, batchSize = 100)
repo.upsertAll(usersById, batchSize = 100)

// Invalidate cache
repo.invalidate(1L)
repo.invalidateAll(listOf(1L, 2L, 3L))
repo.clear()
repo.invalidateByPattern("user:*")
```

### 2. Cache pattern configuration

```kotlin
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig

// Read-Through Only
val readOnlyConfig = RedissonCacheConfig.readOnly(
    ttl = Duration.ofMinutes(30),
)

// Read-Through + Write-Through
val readWriteConfig = RedissonCacheConfig.readWrite(
    ttl = Duration.ofMinutes(30),
    writeMode = WriteMode.WRITE_THROUGH,
)

// Enable Near Cache (Local + Redis two-tier)
val nearCacheConfig = RedissonCacheConfig.readOnly(
    ttl = Duration.ofMinutes(30),
    nearCacheEnabled = true,
)
```

## Cache Patterns

### Read-Through (R2DBC + suspend)

On `get(id)` or `getAll(ids)`, Redisson serves cache hits directly. A miss invokes `R2dbcExposedEntityMapLoader`, which loads rows from the database through an R2DBC `suspendTransaction`.

![R2DBC Redisson read-through sequence diagram](../../docs/images/readme-diagrams/exposed-r2dbc-redisson-sequence-01.png)

### Write-Through (R2DBC + suspend)

In `WRITE_THROUGH` mode, `put(id, entity)`, `putAll(...)`, and `upsertAll(...)` wait for Redisson `writerAsync` to complete its R2DBC `suspendTransaction` write before the repository call resumes.

![R2DBC Redisson write-through sequence diagram](../../docs/images/readme-diagrams/exposed-r2dbc-redisson-sequence-02.png)

### Write-Behind (R2DBC + suspend + async DB)

In `WRITE_BEHIND` mode, `put(id, entity)` and bulk writes return after Redisson accepts the cache update. The database batch write happens later, so read-after-write durability is eventual rather than immediate.

![R2DBC Redisson write-behind sequence diagram](../../docs/images/readme-diagrams/exposed-r2dbc-redisson-sequence-03.png)

## R2dbcRedissonRepository Key Methods

| Method                                  | Description                                                |
|-----------------------------------------|------------------------------------------------------------|
| `containsKey(id)`                            | Check ID existence in cache (suspend)                      |
| `get(id)`                               | Retrieve entity from cache, load from DB on miss (suspend) |
| `getAll(ids, batchSize)`                | Batch retrieve from cache (suspend)                        |
| `findByIdFromDb(id)`                    | Bypass cache, query DB directly (suspend)                  |
| `findAllFromDb(ids)`                    | Bypass cache, batch query DB (suspend)                     |
| `findAll(limit, offset, sortBy, where)` | Load from DB and sync cache (suspend)                      |
| `put(id, entity)`                       | Store one entity in cache; writer behavior depends on cache mode (suspend) |
| `putAll(entities, batchSize)`           | Store an ID-to-entity map in cache; writer behavior depends on cache mode (suspend) |
| `upsertAll(entities, batchSize)`        | Explicit bulk cache upsert with batched map writes (suspend) |
| `invalidate(id)`                        | Remove one cache entry; database delete is opt-in via `deleteFromDBOnInvalidate` (suspend) |
| `invalidateAll(ids)`                    | Remove multiple cache entries; database delete is opt-in via `deleteFromDBOnInvalidate` (suspend) |
| `clear()`                               | Clear map entries; the default path uses writerless cache-only removal (suspend) |
| `invalidateByPattern(pattern, count)`   | Remove cache entries matching a pattern (suspend)          |

## Cache Configuration Constants (`RedissonCacheConfig`)

Commonly used cache mode constants are provided as named constants.

| Constant                                              | Description                      |
|-------------------------------------------------------|----------------------------------|
| `RedissonCacheConfig.READ_ONLY`                          | Read-Through only (remote cache) |
| `RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE`          | Read-Through + Near Cache        |
| `RedissonCacheConfig.READ_WRITE_THROUGH`                 | Read-Through + Write-Through     |
| `RedissonCacheConfig.READ_WRITE_THROUGH_WITH_NEAR_CACHE` | Read-Write-Through + Near Cache  |
| `RedissonCacheConfig.WRITE_BEHIND`                       | Write-Behind (remote cache)      |
| `RedissonCacheConfig.WRITE_BEHIND_WITH_NEAR_CACHE`       | Write-Behind + Near Cache        |

## Key Files and Classes

### Repository (repository/)

| File                                 | Description                                    |
|--------------------------------------|------------------------------------------------|
| `R2dbcRedissonRepository.kt`         | R2DBC async cache Repository interface         |
| `AbstractR2dbcRedissonRepository.kt` | R2DBC async cache Repository abstract class    |

### Map (map/)

| File                             | Description                                                           |
|----------------------------------|-----------------------------------------------------------------------|
| `R2dbcEntityMapLoader.kt`        | R2DBC async MapLoader base implementation (`MapLoaderAsync`)          |
| `R2dbcEntityMapWriter.kt`        | R2DBC async MapWriter base implementation (`MapWriterAsync`)          |
| `R2dbcExposedEntityMapLoader.kt` | Exposed IdTable-based MapLoader implementation                        |
| `R2dbcExposedEntityMapWriter.kt` | Exposed IdTable-based MapWriter implementation (Write-Through/Behind) |
| `AsyncIteratorSupport.kt`        | Extension to collect a Redisson `AsyncIterator` into a `List`         |

## Testing

```bash
./gradlew :bluetape4k-exposed-r2dbc-redisson:test
```

## References

- [JetBrains Exposed R2DBC](https://github.com/JetBrains/Exposed)
- [Redisson](https://github.com/redisson/redisson)
- [Redisson AsyncMapLoader](https://www.javadoc.io/doc/org.redisson/redisson/latest/org/redisson/api/map/MapLoaderAsync.html)
- [exposed-r2dbc](../exposed-r2dbc)
- [exposed-jdbc-redisson](../exposed-jdbc-redisson)
