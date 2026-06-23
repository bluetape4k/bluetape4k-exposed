# Module exposed-jdbc-redisson

English | [한국어](./README.ko.md)

Combines Exposed JDBC with Redisson caching to implement Read-Through/Write-Through cache patterns.

## Overview

`exposed-jdbc-redisson` integrates JetBrains Exposed ORM with the [Redisson](https://github.com/redisson/redisson) Redis client, making it easy to cache database query results in Redis.

### Key Features

- **MapLoader/MapWriter support**: Integration with Redisson Read-Through/Write-Through caching
    - `loadAllKeys()` iterates reliably in ascending primary key order
- **Repository abstraction**: Common cache + DB access patterns (`JdbcRedissonRepository`,
  `SuspendedJdbcRedissonRepository`)
- **Sync and Coroutines implementations**: Choose the right approach for your environment
- **Near Cache support**: Two-tier Local Cache + Redis caching
- **Write-Behind support**: Asynchronous DB persistence pattern

## Adding Dependencies

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson:${version}")
    implementation("org.redisson:redisson:3.37.0")
}
```

## Architecture Overview

The architecture view separates the Redisson map that serves application calls from the map used for cache-only
invalidation. `RedissonCacheConfig` chooses `RMapCache` or `RLocalCachedMap`, attaches a loader in read-only mode, and
adds a writer only for read/write modes.

![JDBC Redisson Redis cache architecture diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-01.png)

## Class Diagrams

### Synchronous Repository Hierarchy

The class diagram focuses on the synchronous repository contract. Coroutine behavior uses the same cache policy, but
the suspend path is easier to read in the sequence diagrams because it awaits Redisson futures and suspended Exposed
transactions.

![JDBC Redisson synchronous repository hierarchy diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-02.png)


## Basic Usage

### 1. Implementing JdbcRedissonRepository (synchronous)

Extend `AbstractJdbcRedissonRepository` to implement a synchronous cache Repository.

```kotlin
import io.bluetape4k.exposed.redisson.repository.AbstractJdbcRedissonRepository
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.update
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

class UserRedissonRepository(
    redissonClient: RedissonClient,
    config: RedissonCacheConfig,
): AbstractJdbcRedissonRepository<Long, UserRecord>(
    redissonClient = redissonClient,
    config = config,
    // Required only when using Fory/Kryo/JDK-family binary codecs with trusted Redis data.
    trustedBinaryCache = true,
) {
    override val table = UserTable

    override fun extractId(entity: UserRecord): Long = entity.id

    override fun ResultRow.toEntity() = UserRecord(
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

// Usage (Read-Through)
val repo = UserRedissonRepository(redissonClient, RedissonCacheConfig.READ_ONLY)

// Retrieve from cache (auto-loads from DB on miss)
val user = repo[1L]

// Check cache key existence by ID (DB Read-Through on cache miss)
val exists = repo.containsKey(1L)

// Bypass cache and query DB directly
val freshUser = repo.findByIdFromDb(1L)

// Batch retrieval of multiple entities
val users = repo.getAll(listOf(1L, 2L, 3L))

// Load from DB and store in cache
val allUsers = repo.findAll(limit = 100)

// Invalidate cache
repo.invalidate(1L)
repo.invalidateAll()
repo.invalidateByPattern("*John*")  // Invalidate by pattern
```

### 2. Implementing SuspendedJdbcRedissonRepository (Coroutines)

Extend `AbstractSuspendedJdbcRedissonRepository` to implement a coroutine-based cache Repository.

```kotlin
import io.bluetape4k.exposed.redisson.repository.AbstractSuspendedJdbcRedissonRepository
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.redisson.api.RedissonClient

class SuspendedUserRedissonRepository(
    redissonClient: RedissonClient,
    config: RedissonCacheConfig,
): AbstractSuspendedJdbcRedissonRepository<Long, UserRecord>(
    redissonClient = redissonClient,
    config = config,
    // Required only when using Fory/Kryo/JDK-family binary codecs with trusted Redis data.
    trustedBinaryCache = true,
) {
    override val table = UserTable

    override fun extractId(entity: UserRecord): Long = entity.id

    override fun ResultRow.toEntity() = UserRecord(
        id    = this[UserTable.id].value,
        name  = this[UserTable.name],
        email = this[UserTable.email],
    )

    override fun UpdateStatement.updateEntity(entity: UserRecord) {
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }

    override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }
}

// Usage (suspend functions)
val repo = SuspendedUserRedissonRepository(redissonClient, RedissonCacheConfig.READ_ONLY)

val user = repo.get(1L)                          // Cache lookup (DB Read-Through on miss)
val exists = repo.containsKey(1L)                     // Check cache key existence
val fresh = repo.findByIdFromDb(1L)              // Bypass cache, query DB directly
val all = repo.findAll(limit = 100)              // Load from DB, populate cache
val batch = repo.getAll(listOf(1L, 2L, 3L))     // Batch retrieval
repo.put(user!!)                                 // Store in cache
repo.putAll(batch)                               // Batch store in cache
repo.upsertAll(batch, batchSize = 100)           // Explicit bulk cache upsert
repo.invalidate(1L)                              // Invalidate single entry
repo.invalidateAll()                             // Invalidate all (returns Boolean)
repo.invalidateByPattern("user:*")               // Invalidate by pattern
```

### 3. Cache pattern configuration

```kotlin
import io.bluetape4k.redis.redisson.cache.RedissonCacheConfig
import org.redisson.api.map.WriteMode

// Read-Through Only (default) — auto-loads from DB on cache miss
val readOnlyConfig = RedissonCacheConfig.READ_ONLY

// Read-Through + Near Cache — two-tier Local Cache + Redis
val readOnlyNearCacheConfig = RedissonCacheConfig.READ_ONLY_WITH_NEAR_CACHE

// Read-Through + Write-Through — synchronously persists to DB on cache write
val writeThroughConfig = RedissonCacheConfig.READ_WRITE_THROUGH

// Read-Through + Write-Through + Near Cache
val writeThroughNearCacheConfig = RedissonCacheConfig.READ_WRITE_THROUGH_WITH_NEAR_CACHE

// Read-Through + Write-Behind — asynchronously persists to DB after cache write
val writeBehindConfig = RedissonCacheConfig.WRITE_BEHIND

// Read-Through + Write-Behind + Near Cache
val writeBehindNearCacheConfig = RedissonCacheConfig.WRITE_BEHIND_WITH_NEAR_CACHE

// Also delete from DB on invalidate (deleteFromDBOnInvalidate=true)
// ⚠️ Use with caution in production.
val deleteFromDbConfig = RedissonCacheConfig.READ_WRITE_THROUGH.copy(
    deleteFromDBOnInvalidate = true,
)
```

## Redis Codec Safety

`RedissonCacheConfig` constants use Fory-family binary codecs by default. Repository constructors
reject Fory/Kryo/JDK-family binary codecs unless `trustedBinaryCache = true` is passed explicitly.
Use that opt-in only for private Redis instances whose contents are not writable by untrusted
clients. For dependency-facing Redis data, provide a reviewed custom codec instead of relying on
the default binary codec.

### 4. Write-Through / Write-Behind Repository implementation

In Write-Through/Write-Behind mode, also implement `UpdateStatement.updateEntity` and
`BatchInsertStatement.insertEntity`.

```kotlin
class UserWriteThroughRepository(
    redissonClient: RedissonClient,
): AbstractJdbcRedissonRepository<Long, UserRecord>(
    redissonClient = redissonClient,
    config = RedissonCacheConfig.READ_WRITE_THROUGH.copy(name = "users:write-through"),
    trustedBinaryCache = true,
) {
    override val table = UserTable

    override fun extractId(entity: UserRecord): Long = entity.id

    override fun ResultRow.toEntity() = UserRecord(
        id    = this[UserTable.id].value,
        name  = this[UserTable.name],
        email = this[UserTable.email],
    )

    // Called on UPDATE of an existing record
    override fun UpdateStatement.updateEntity(entity: UserRecord) {
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }

    // Called on INSERT of a new record (for client-side IDs)
    override fun BatchInsertStatement.insertEntity(entity: UserRecord) {
        this[UserTable.id]    = EntityID(entity.id, UserTable)
        this[UserTable.name]  = entity.name
        this[UserTable.email] = entity.email
    }
}

// Write-Through usage
val repo = UserWriteThroughRepository(redissonClient)
transaction {
    val user = UserRecord(id = 0, name = "Hong Gildong", email = "hong@example.com")
    repo.put(user)                   // Write to cache + synchronously persist to DB
    repo.putAll(listOf(user))        // Batch write to cache + DB
    repo.upsertAll(mapOf(user.id to user)) // Explicit bulk cache upsert + DB writer
    repo.invalidate(user.id)         // Remove from cache (also deletes from DB if deleteFromDBOnInvalidate=true)
}
```

## Cache Patterns

### Read-Through (synchronous)

On a cache miss, `ExposedEntityMapLoader` loads from the DB and Redisson stores the entity in Redis. Invalidating an
entry removes cache data only unless `deleteFromDBOnInvalidate=true`.

![JDBC Redisson read-through sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-01.png)

### Write-Through (synchronous)

On `put()`, Redisson calls `ExposedEntityMapWriter` before the write returns. Existing IDs are updated; non-generated
IDs can be inserted with `BatchInsertStatement.insertEntity`.

![JDBC Redisson write-through sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-02.png)

### Write-Behind (synchronous)

On `put()`, Redis accepts the value first and the writer flushes to the DB later. This mode improves write latency, but
callers that require DB durability must account for the background write window.

![JDBC Redisson write-behind sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-03.png)

### Read-Through (Suspend Coroutines)

`SuspendedJdbcRedissonRepository` exposes the same read-through policy as `suspend` functions. The repository awaits
Redisson async map operations and uses suspended Exposed transactions for DB reads.

![Suspended JDBC Redisson read-through sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-04.png)

### Write-Through (Suspend Coroutines)

The suspend write-through path resumes after Redisson and the suspended writer have completed the DB update or insert.

![Suspended JDBC Redisson write-through sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-05.png)

### Write-Behind (Suspend Coroutines)

The suspend write-behind path resumes after Redis accepts the value; DB persistence is still handled by Redisson's
background writer.

![Suspended JDBC Redisson write-behind sequence diagram](../../docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-06.png)

## JdbcRedissonRepository / SuspendedJdbcRedissonRepository Key Methods

`JdbcRedissonRepository` uses synchronous calls; `SuspendedJdbcRedissonRepository` exposes the same API as
`suspend` functions.

| Method                                  | Description                                                                 |
|-----------------------------------------|-----------------------------------------------------------------------------|
| `containsKey(id)`                            | Check whether the ID exists in cache (DB Read-Through on miss)              |
| `get(id)` / `cache[id]`                 | Retrieve entity from cache (Read-Through)                                   |
| `getAll(ids, batchSize)`                | Batch retrieve multiple entities from cache                                 |
| `findByIdFromDb(id)`                    | Bypass cache and query DB directly                                          |
| `findAllFromDb(ids)`                    | Bypass cache and batch query DB directly                                    |
| `findAll(limit, offset, sortBy, where)` | Load from DB and store results in cache                                     |
| `put(entity)`                           | Store in cache (also persists to DB in Write-Through/Behind mode)           |
| `putAll(entities, batchSize)`           | Batch store in cache                                                        |
| `upsertAll(entities, batchSize)`        | Explicit bulk cache upsert using Redisson batched map writes                |
| `invalidate(ids)`                       | Remove from cache (also deletes from DB if `deleteFromDBOnInvalidate=true`) |
| `invalidateAll()`                       | Clear all cache entries                                                     |
| `invalidateByPattern(pattern, count)`   | Remove cache entries matching a pattern                                     |

> **Note**: `SuspendedJdbcRedissonRepository.invalidateAll()` returns `Boolean`.

## Key Files and Classes

### Repository (repository/)

| File                                         | Description                                    |
|----------------------------------------------|------------------------------------------------|
| `JdbcRedissonRepository.kt`                  | Synchronous cache Repository interface         |
| `AbstractJdbcRedissonRepository.kt`          | Synchronous cache Repository abstract class    |
| `SuspendedJdbcRedissonRepository.kt`         | Coroutines cache Repository interface          |
| `AbstractSuspendedJdbcRedissonRepository.kt` | Coroutines cache Repository abstract class     |

### Map (map/)

| File                                 | Description                         |
|--------------------------------------|-------------------------------------|
| `EntityMapLoader.kt`                 | Synchronous MapLoader interface     |
| `EntityMapWriter.kt`                 | Synchronous MapWriter interface     |
| `ExposedEntityMapLoader.kt`          | Exposed JDBC-based MapLoader        |
| `ExposedEntityMapWriter.kt`          | Exposed JDBC-based MapWriter        |
| `SuspendedEntityMapLoader.kt`        | Coroutines MapLoader interface      |
| `SuspendedEntityMapWriter.kt`        | Coroutines MapWriter interface      |
| `SuspendedExposedEntityMapLoader.kt` | Coroutines MapLoader implementation |
| `SuspendedExposedEntityMapWriter.kt` | Coroutines MapWriter implementation |

## Testing

```bash
./gradlew :bluetape4k-exposed-jdbc-redisson:test
```

## References

- [JetBrains Exposed](https://github.com/JetBrains/Exposed)
- [Redisson](https://github.com/redisson/redisson)
- [Redisson RMap](https://www.javadoc.io/doc/org.redisson/redisson/latest/org/redisson/api/RMap.html)
- [exposed-jdbc](../exposed-jdbc)
