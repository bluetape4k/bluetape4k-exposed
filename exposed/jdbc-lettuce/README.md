# Module exposed-jdbc-lettuce

English | [한국어](./README.ko.md)

A Read-through / Write-through / Write-behind cache repository module that combines Exposed JDBC with Lettuce Redis. It provides both synchronous (`JdbcLettuceRepository`) and coroutine-native (`SuspendedJdbcLettuceRepository`) implementations.

## Overview

`exposed-jdbc-lettuce` provides:

- **Read-through cache**: On `findById` cache miss, automatically loads from DB and caches in Redis
- **Write-through / Write-behind**: On `save`, reflects changes in Redis and DB simultaneously (or asynchronously)
- **Synchronous repository**: `JdbcLettuceRepository` / `AbstractJdbcLettuceRepository`
- **Coroutine repository**: `SuspendedJdbcLettuceRepository` / `AbstractSuspendedJdbcLettuceRepository`
- **MapLoader / MapWriter**: Exposed-based implementations for repository loaded-map integration
    - `loadAllKeys()` returns a lazy ascending-PK `Iterable`; ordered scalar IDs use keyset pages and unsupported custom IDs use the legacy offset fallback
    - `loadAllKeysInParallel(ranges, options)` is an opt-in materialized Virtual Thread path for caller-owned disjoint `[lowerInclusive, upperExclusive)` PK ranges; it uses independent JDBC transactions, bounded concurrency, and ordered merge, while the default lazy path remains unchanged. Exposed range predicates require `Comparable` PK boundaries
    - The suspended JDBC loader keeps its existing `List` API and reads the same keyset/fallback pages inside `suspendedTransactionAsync`; it does not expose a new streaming surface
    - The suspended `List` is materialized before it is returned; caller cancellation propagates through the suspended transaction and closes the JDBC work without partial list emission
    - Enumeration is weakly consistent; a page-side delete can skip an unseen row on the custom-ID offset fallback path
    - Each page materializes only `batchSize` IDs and consumes its ResultSet within that page. The suspended loader executes the entire page loop in one `suspendedTransactionAsync`, so its JDBC connection remains held for the enumeration; an ambient caller-owned Exposed transaction remains caller-owned
    - `chunkSize` (writer) and `batchSize` (loader) must be greater than 0

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce:${version}")
}
```

## Architecture Overview

This architecture view separates the blocking repository, the suspend repository, the Redis loaded-map layer, and the Exposed JDBC loader/writer path. NearCache is shown only on the suspend path because the blocking repository goes straight through `ExposedLettuceLoadedMap`.

![JDBC Lettuce Redis cache architecture diagram](../../docs/images/readme-diagrams/exposed-jdbc-lettuce-diagram-01.png)

The sequence view follows the read-through, write-through/write-behind, and invalidation timing shared by the repository contracts. It calls out the optional suspend NearCache step instead of implying that every Lettuce repository has a local front cache.

![JDBC Lettuce cache flow diagram](../../docs/images/readme-diagrams/exposed-jdbc-lettuce-sequence-01.png)

## Basic Usage

### 1. Synchronous Repository (AbstractJdbcLettuceRepository)

```kotlin
import io.bluetape4k.exposed.lettuce.repository.AbstractJdbcLettuceRepository
import io.bluetape4k.exposed.lettuce.repository.ExposedLettuceCodecs
import io.bluetape4k.redis.lettuce.map.LettuceCacheConfig
import io.lettuce.core.RedisClient

data class UserRecord(val id: Long, val name: String, val email: String)

class UserLettuceRepository(redisClient: RedisClient):
    AbstractJdbcLettuceRepository<Long, UserRecord>(
        client = redisClient,
        config = LettuceCacheConfig.READ_WRITE_THROUGH,
        valueCodec = ExposedLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
    override val table = UserTable

    override fun ResultRow.toEntity() = UserRecord(
        id = this[UserTable.id].value,
        name = this[UserTable.name],
        email = this[UserTable.email],
    )

    override fun UpdateStatement.updateEntity(entity: UserRecord) {
        this[UserTable.name] = entity.name
        this[UserTable.email] = entity.email
    }

    override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
        this[UserTable.id] = entity.id
        this[UserTable.name] = entity.name
        this[UserTable.email] = entity.email
    }

    override fun extractId(entity: UserRecord) = entity.id
}

// Usage
val repo = UserLettuceRepository(redisClient)
repo.save(1L, UserRecord(1L, "Hong Gildong", "hong@example.com"))
val user = repo.findById(1L)   // On cache miss, loads from DB and caches
repo.delete(1L)                // Deletes from both Redis and DB
```

### 2. Coroutine Repository (AbstractSuspendedJdbcLettuceRepository)

```kotlin
import io.bluetape4k.exposed.lettuce.repository.AbstractSuspendedJdbcLettuceRepository

class UserSuspendedRepository(redisClient: RedisClient):
    AbstractSuspendedJdbcLettuceRepository<Long, UserRecord>(
        client = redisClient,
        config = LettuceCacheConfig.READ_WRITE_THROUGH,
        valueCodec = ExposedLettuceCodecs.jackson3(UserRecord::class.java),
    ) {
    override val table = UserTable
    override fun ResultRow.toEntity() = /* ... */
    override fun UpdateStatement.updateEntity(entity: UserRecord) = /* ... */
    override fun BatchInsertStatement.insertEntity(entity: UserRecord) = /* ... */
    override fun extractId(entity: UserRecord) = entity.id
}

// Use as suspend functions
suspend fun example(repo: UserSuspendedRepository) {
    repo.save(1L, UserRecord(1L, "Hong Gildong", "hong@example.com"))
    val user = repo.findById(1L)     // Checks NearCache → Redis → DB in order
    repo.clearCache()                // Clears all Redis cache keys
}
```

## Key Methods of JdbcLettuceRepository

| Method                        | Description                                               |
|-------------------------------|-----------------------------------------------------------|
| `findById(id)`                | Cache lookup → DB Read-through on miss                    |
| `findAll(ids)`                | Batch cache lookup → DB Read-through for missed keys only |
| `findAll(limit, offset, ...)` | DB query with result loaded into cache                    |
| `findByIdFromDb(id)`          | Bypasses cache, queries DB directly                       |
| `findAllFromDb(ids)`          | Bypasses cache, queries DB directly for multiple IDs      |
| `countFromDb()`               | Total record count from DB                                |
| `save(id, entity)`            | Stores in Redis + reflects in DB according to WriteMode   |
| `saveAll(entities)`           | Batch save                                                |
| `delete(id)`                  | Deletes from both Redis and DB simultaneously             |
| `deleteAll(ids)`              | Batch delete                                              |
| `clearCache()`                | Removes all Redis keys (no effect on DB)                  |

## LettuceCacheConfig — Write Modes

| WriteMode            | Behavior                                                           |
|----------------------|--------------------------------------------------------------------|
| `READ_WRITE_THROUGH` | On save, writes to Redis + DB simultaneously (default)             |
| `READ_WRITE_BEHIND`  | On save, writes to Redis immediately; DB is updated asynchronously |
| `READ_ONLY`          | Stores in Redis only; no DB writes                                 |

## Redis Codec Safety

Repository constructors require an explicit `RedisCodec<String, E>` for values. The inherited
Lettuce binary map codec uses LZ4/Fory, so it is not selected by default for repository data.
Use `ExposedLettuceCodecs.jackson3(Entity::class.java)` or provide a reviewed codec for your
entity type. Fory/Kryo-family binary codecs should be used only when Redis contents are fully
trusted and not shared with untrusted writers.

## Key Files / Classes

| File                                                   | Description                                                               |
|--------------------------------------------------------|---------------------------------------------------------------------------|
| `repository/JdbcLettuceRepository.kt`                  | Synchronous cache repository interface                                    |
| `repository/SuspendedJdbcLettuceRepository.kt`         | Coroutine cache repository interface                                      |
| `repository/AbstractJdbcLettuceRepository.kt`          | Synchronous abstract implementation (ExposedLettuceLoadedMap-based)       |
| `repository/AbstractSuspendedJdbcLettuceRepository.kt` | Coroutine abstract implementation (ExposedLettuceSuspendedLoadedMap + NearCache) |
| `repository/ExposedLettuceCodecs.kt`                   | Explicit value codec helpers for repository Redis values                  |
| `map/ExposedLettuceLoadedMap.kt`                       | Synchronous loaded map with caller-supplied value codec                   |
| `map/ExposedLettuceSuspendedLoadedMap.kt`              | Coroutine loaded map with caller-supplied value codec                     |
| `map/EntityMapLoader.kt`                               | Abstract base class for MapLoader                                         |
| `map/EntityMapWriter.kt`                               | Abstract base class for MapWriter (with built-in Resilience4j Retry)      |
| `map/ExposedEntityMapLoader.kt`                        | Exposed DSL-based synchronous MapLoader                                   |
| `map/ExposedEntityMapWriter.kt`                        | Exposed DSL-based synchronous MapWriter                                   |
| `map/SuspendedEntityMapLoader.kt`                      | MapLoader based on `suspendedTransactionAsync`                            |
| `map/SuspendedEntityMapWriter.kt`                      | MapWriter based on `suspendedTransactionAsync` + Retry                    |
| `map/SuspendedExposedEntityMapLoader.kt`               | Coroutine MapLoader based on Exposed DSL                                  |
| `map/SuspendedExposedEntityMapWriter.kt`               | Coroutine MapWriter based on Exposed DSL                                  |

## Testing

```bash
./gradlew :bluetape4k-exposed-jdbc-lettuce:test
```

## References

- [exposed-jdbc](../jdbc)
- [bluetape4k-lettuce](../../infra/lettuce)
- [Lettuce Redis Client](https://lettuce.io)
