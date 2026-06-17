# exposed-r2dbc-caffeine

English | [한국어](./README.ko.md)

Exposed R2DBC repository with Caffeine local (in-process) cache. No JDBC dependency -- only `exposed-cache` is referenced.

> **See also**: [exposed-cache — Full Module Ecosystem & Interface Hierarchy](../exposed-cache/README.md)

## Architecture

The architecture view separates the coroutine-facing repository contract, the local `AsyncCache`, the Exposed R2DBC transaction path, and the write-behind worker. Use it to decide where cache configuration, table mapping, and database writes are owned.

![R2DBC Caffeine local cache architecture diagram](../../docs/images/readme-diagrams/exposed-exposed-r2dbc-caffeine-diagram-01.png)

The sequence view focuses on behavior: read-through misses load through `suspendTransaction`, write-through waits for the database write, and write-behind returns after queuing while `close()` waits for the final flush.

![R2DBC Caffeine cache sequence diagram](../../docs/images/readme-diagrams/exposed-exposed-r2dbc-caffeine-sequence-01.png)

## Features

- **Read-Through**: Cache miss triggers DB load via R2DBC `suspendTransaction`, result cached in Caffeine
- **Write-Through**: `put()` updates both Caffeine and DB synchronously
- **Write-Behind**: `put()` updates Caffeine immediately, DB write is batched asynchronously via `Channel`
- **No JDBC dependency**: Pure R2DBC with `exposed-cache` interfaces only
- **Caffeine AsyncCache**: Non-blocking cache backed by `CompletableFuture`
- **Coroutine-native**: All DB operations use `suspendTransaction`

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
