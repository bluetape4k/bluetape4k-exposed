# exposed-cache

English | [한국어](./README.ko.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bluetape4k.exposed/bluetape4k-exposed-cache)](https://central.sonatype.com/artifact/io.github.bluetape4k.exposed/bluetape4k-exposed-cache)

## Overview

`exposed-cache` defines the **core interfaces and shared configuration** for cache-backed Exposed repositories.

It is **cache-backend agnostic** — the same interfaces are implemented by both local cache (Caffeine) and distributed cache (Redis via Lettuce/Redisson) modules. All cache-specific modules depend on this hub module and add only their backend-specific implementation.

## Module Ecosystem

| Module | Cache Backend | Cache Mode | DB Access | Suspend Support |
|--------|--------------|------------|-----------|-----------------|
| `exposed-jdbc-caffeine` | Caffeine (local) | `LOCAL` | JDBC | sync + suspend |
| `exposed-r2dbc-caffeine` | Caffeine (local) | `LOCAL` | R2DBC | suspend only |
| `exposed-jdbc-lettuce` | Redis (Lettuce) | `REMOTE` / `NEAR_CACHE` | JDBC | sync + suspend |
| `exposed-r2dbc-lettuce` | Redis (Lettuce) | `REMOTE` | R2DBC | suspend only |
| `exposed-jdbc-redisson` | Redis (Redisson) | `REMOTE` / `NEAR_CACHE` | JDBC | sync + suspend |
| `exposed-r2dbc-redisson` | Redis (Redisson) | `REMOTE` | R2DBC | suspend only |

## Diagrams

### Repository Interface Class Diagram

The shared cache repository contracts and Redis-only extension interfaces are shown as a UML-style class diagram.

![Repository Interface Class Diagram](../../docs/images/readme-diagrams/exposed-cache-diagram-01.png)

### Cache Configuration Decision Map

`CacheMode`, `CacheWriteMode`, local cache limits, and optional Redis resilience settings are configuration decisions, not class inheritance.

![Cache Configuration Decision Map](../../docs/images/readme-diagrams/exposed-cache-diagram-02.png)

## CacheMode

| Value | Description |
|-------|-------------|
| `LOCAL` | In-process cache only (Caffeine). Fastest, but not shared across JVM processes. |
| `REMOTE` | Remote cache only (Redis). Shared across all instances. |
| `NEAR_CACHE` | L1 local cache + L2 Redis. Minimizes network round-trips; supported by Lettuce/Redisson modules. |

## CacheWriteMode

| Value | Read | Write |
|-------|------|-------|
| `READ_ONLY` | Read-through: cache miss loads from DB and caches | Cache only — no DB writes |
| `WRITE_THROUGH` | Read-through | Cache + DB written synchronously |
| `WRITE_BEHIND` | Read-through | Cache written immediately; DB written asynchronously in batches |

## LocalCacheConfig

Configuration for local (in-process) cache implementations. Caffeine modules use this directly; Redis modules use it as the L1 near-cache configuration.

| Property | Default | Description |
|----------|---------|-------------|
| `keyPrefix` | `"local"` | Cache key prefix |
| `maximumSize` | `10_000` | Maximum number of cache entries |
| `expireAfterWrite` | `10 minutes` | TTL from last write |
| `expireAfterAccess` | `null` (disabled) | TTL from last access |
| `writeMode` | `READ_ONLY` | Write strategy (`READ_ONLY` / `WRITE_THROUGH` / `WRITE_BEHIND`) |
| `writeBehindBatchSize` | `100` | Write-Behind flush batch size |
| `writeBehindQueueCapacity` | `10_000` | Write-Behind queue capacity (must not be unlimited) |

**Predefined constants**:

```kotlin
LocalCacheConfig.READ_ONLY      // writeMode = READ_ONLY
LocalCacheConfig.WRITE_THROUGH  // writeMode = WRITE_THROUGH
LocalCacheConfig.WRITE_BEHIND   // writeMode = WRITE_BEHIND
```

## RedisRepositoryResilienceConfig

Optional resilience configuration for Redis-backed repositories. Pass `null` (the default) to disable resilience wrapping.

| Property | Default | Description |
|----------|---------|-------------|
| `retryMaxAttempts` | `3` | Maximum retry attempts on Redis failure |
| `retryWaitDuration` | `500ms` | Wait time between retries |
| `retryExponentialBackoff` | `true` | Use exponential backoff for retries |
| `circuitBreakerEnabled` | `false` | Enable Circuit Breaker |
| `timeoutDuration` | `2s` | Redis operation timeout |

`retryMaxAttempts` must be at least 1. `retryWaitDuration` and `timeoutDuration` must be positive.

## Write Strategy Flow

![Write Strategy Patterns diagram](../../docs/images/readme-diagrams/exposed-cache-sequence-01.png)

<!-- SNAPSHOT-CACHE-CONTRACT -->
## Transaction-aware snapshot cache (opt-in)

The snapshot-cache API is an opt-in cache-only path. It does not change or migrate existing `JdbcCacheRepository`,
`R2dbcCacheRepository`, Caffeine repository, or Redis repository behavior. Use it when a read result can be copied to a
detached immutable DTO and published only after the surrounding Exposed transaction commits.

| Boundary | Exposed transaction-local `EntityCache` | Application snapshot near-cache |
|---|---|---|
| Lifetime | One Exposed transaction | Across transactions in one process, or across nodes when paired with invalidation |
| Value | Managed DAO `Entity` state | `CacheSnapshot` containing a detached serializable DTO |
| Visibility | Transaction-scoped | Commit publishes; rollback discards |
| Consistency role | Identity/change tracking inside Exposed | Application-owned read optimization with explicit repair obligations |

`CacheSnapshotMapper` runs inside the current root transaction. Copy every required field there; never retain a DAO
`Entity`, transaction, request object, lazy relation, or mutable persistence object. The default
`rejectDirectEntitySnapshotValues()` validator rejects a direct top-level `Entity`, and applications remain responsible
for deep immutability. Cache callbacks perform no database writes.

`SnapshotCacheConfig` defines the namespace, schema version (included in the Redisson compatibility fingerprint), and
transaction limits shared by snapshot stores.
Local Caffeine stores add capacity and expiry through `CaffeineSnapshotCacheConfig`. Create the caller-owned failure
queue with `snapshotCacheFailureBuffer(capacity)` and expose it as a `SnapshotCacheFailureBuffer`.

The safe miss path is deliberately capability-based:

1. Call `lookup(id)` before database work. If `maxOutstandingMissTokens` is exhausted, lookup fails before the database
   read starts.
2. On a miss, read from the database in the current root transaction and call `stageSnapshot`. The opaque
   `SnapshotCacheMiss` is one-shot; mapper, validation, or staging failure still consumes it.
3. Snapshot fill requires `maxAttempts = 1`. Put transient-database retry outside the transaction and perform a fresh
   `lookup` for every outer attempt. Nested/savepoint transactions are rejected.
4. Commit applies staged work; rollback and a failed Exposed attempt discard it. Staged invalidation is attempt-local, so
   a successful Exposed retry publishes it once. For repeated mutations of one key, the last staged mutation wins.
5. A process-local fence rejects a late fill after a newer local mutation. It is not a distributed lock and is never
   serialized.

An earlier failing transaction callback can prevent this cache callback from running, leaving an older cache value in
place. `maxStagedMutations`, `maxParticipatingStores`, optional staged weight, `localDrainBudget`, and outstanding miss
capacity bound retained work. Post-commit cache failures go to the caller-owned bounded `SnapshotCacheFailureBuffer` and
can be drained with `drainTo`; they do not change the already committed database result.

Public failure and health surfaces retain bounded structural fields and, when safe, the exception type. They never retain
exception text, stack traces, payloads, identifiers, SQL, URLs, endpoints, or credentials. Do not use identifiers or
payload-derived values as metric tags.

Commit-safe means only that rollback does not publish and commit is the publication boundary. It is not database/cache
atomicity, does not provide crash durability, and does not replace an application-owned outbox or repair path for a
post-commit cache failure.

## testFixtures Scenarios

`exposed-cache` ships testFixtures with reusable scenario classes that all implementing modules inherit for consistency.

| Scenario class | Interface tested | Covered scenarios |
|----------------|-----------------|-------------------|
| `JdbcCacheTestScenario` | `JdbcCacheRepository` | Read-through, Write-through, Write-behind, invalidate |
| `JdbcReadThroughScenario` | `JdbcCacheRepository` | Cache miss → DB load, cache hit, getAll partial miss |
| `JdbcWriteThroughScenario` | `JdbcCacheRepository` | put / putAll → DB updated immediately |
| `JdbcWriteBehindScenario` | `JdbcCacheRepository` | put → cache immediate, DB flushed asynchronously |
| `SuspendedJdbcCacheTestScenario` | `SuspendedJdbcCacheRepository` | Same as above, suspend variants |
| `SuspendedJdbcReadThroughScenario` | `SuspendedJdbcCacheRepository` | suspend read-through scenarios |
| `SuspendedJdbcWriteThroughScenario` | `SuspendedJdbcCacheRepository` | suspend write-through scenarios |
| `SuspendedJdbcWriteBehindScenario` | `SuspendedJdbcCacheRepository` | suspend write-behind scenarios |
| `R2dbcCacheTestScenario` | `R2dbcCacheRepository` | R2DBC full scenario suite |
| `R2dbcReadThroughScenario` | `R2dbcCacheRepository` | R2DBC read-through |
| `R2dbcWriteThroughScenario` | `R2dbcCacheRepository` | R2DBC write-through |
| `R2dbcWriteBehindScenario` | `R2dbcCacheRepository` | R2DBC write-behind |

**Reuse in tests**:

```kotlin
// testFixtures dependency in build.gradle.kts
testImplementation(testFixtures("io.github.bluetape4k.exposed:bluetape4k-exposed-cache"))

// Extend the scenario in your module test
class MyCaffeineReadThroughTest : JdbcReadThroughScenario() {
    override val repo = ActorCaffeineRepository(LocalCacheConfig.WRITE_THROUGH)
}
```

## When to Use Which Module

| Scenario | Recommended Module |
|----------|--------------------|
| Single instance, no Redis | `exposed-jdbc-caffeine` / `exposed-r2dbc-caffeine` |
| Distributed cache, Redis available | `exposed-jdbc-lettuce` / `exposed-r2dbc-lettuce` |
| L1 local + L2 Redis NearCache | `exposed-jdbc-lettuce` (nearCacheEnabled=true) |
| R2DBC + Redis | `exposed-r2dbc-lettuce` / `exposed-r2dbc-redisson` |
| Pattern-based cache invalidation needed | Any Redis module (`invalidateByPattern`) |
| Redisson features (distributed locks, etc.) | `exposed-jdbc-redisson` / `exposed-r2dbc-redisson` |

## Cache Worker State Migration

`CacheHealthReport.isFlushJobRunning` was removed before the stable release.
Read `workerState` directly; there is no ambiguous compatibility alias.

```kotlin
val healthy = report.lastFlushError == null && report.workerState in setOf(
    CacheWorkerState.NOT_APPLICABLE,
    CacheWorkerState.IDLE,
    CacheWorkerState.RUNNING,
)
```

`NOT_APPLICABLE` means the configured mode has no background worker. A fresh
`IDLE` worker is healthy even though no flush is running; `RUNNING` means it is
available after accepting work. `DRAINING` is finishing accepted writes,
`FAILED` is a terminal worker failure, and `STOPPED` will accept no more work.
The old Boolean could not distinguish those states, so retaining an alias would
recreate the shutdown and failure ambiguity this lifecycle model removes.

## Module Links

- [exposed-jdbc-caffeine](../exposed-jdbc-caffeine/README.md) — JDBC + Caffeine local cache
- [exposed-r2dbc-caffeine](../exposed-r2dbc-caffeine/README.md) — R2DBC + Caffeine local cache
- [exposed-jdbc-lettuce](../exposed-jdbc-lettuce/README.md) — JDBC + Lettuce Redis cache
- [exposed-r2dbc-lettuce](../exposed-r2dbc-lettuce/README.md) — R2DBC + Lettuce Redis cache

## Dependency

```kotlin
dependencies {
    api("io.github.bluetape4k.exposed:bluetape4k-exposed-cache")
}
```

The application owns the `bluetape4k-dependencies` BOM version, so the module coordinate is intentionally versionless.
