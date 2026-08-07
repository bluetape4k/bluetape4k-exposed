---
manualId: "bluetape4k-exposed-cache"
id: "bluetape4k-exposed-cache"
title: "Exposed Cache Foundation"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-cache"
sourceDir: "exposed/cache"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-cache
---

# Exposed Cache Foundation

`bluetape4k-exposed-cache` defines the repository contracts and configuration shared by the Caffeine, Lettuce, and Redisson adapters. It does not create a cache client or choose a backend for the application.

## Problem {#problem}

A cache-backed repository has two state owners: the cache and the database. The application must decide how misses are loaded, when writes become durable, what invalidation removes, and who closes backend resources. This module gives every adapter the same vocabulary for those decisions.

## When to use it {#when-to-use}

Use it when implementing a cache adapter or writing code against the common `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`, or `R2dbcCacheRepository` contract. Most applications should depend on one concrete backend module instead; it already brings this foundation transitively.

## Coordinates {#coordinates}

Import the ecosystem BOM once, then omit the library version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cache")
}
```

## Core concepts {#concepts}

| Decision | Values | Operational meaning |
| --- | --- | --- |
| Location | `LOCAL`, `REMOTE`, `NEAR_CACHE` | One JVM, Redis, or local front cache plus Redis |
| Write policy | `READ_ONLY`, `WRITE_THROUGH`, `WRITE_BEHIND` | Cache-only put, synchronous database write, or deferred database write |
| Read-through | `get`/`getAll` | A miss loads from the database and populates the cache |
| Invalidation | `invalidate`, `invalidateAll`, `clear` | Removes cache state; the common contract does not delete database rows |

An ordinary `put` is not automatically write-through. Its durability depends on the repository's `cacheWriteMode` and concrete writer configuration.

## Quick start {#quick-start}

```kotlin
val entity = repository.get(id)       // cache miss -> DB load -> cache fill
repository.invalidate(id)             // cache only; DB row remains
repository.close()                    // release queues/connections owned by the adapter
```

Start by choosing the concrete backend in the [cache selection guide](../guides/cache-selection.md), then implement the table mapping in that backend's abstract repository.

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Bypass cache | `findByIdFromDb`, `findAllFromDb`, `countFromDb` |
| Read through cache | `containsKey`, `get`, `getAll` |
| Write according to policy | `put`, `putAll` |
| Evict cached state | `invalidate`, `invalidateAll`, `clear` |
| Release resources | `close` |

JDBC has blocking and suspend contracts. R2DBC exposes suspend operations throughout, including row-to-entity conversion.

## Recommended patterns {#patterns}

Keep transaction-owned database updates explicit when cache and database cannot commit atomically. For cache-aside, commit the database change first and invalidate the affected key. Use true write-through only when the adapter owns a configured map writer. Use write-behind only for data that tolerates delayed persistence and a bounded failure window.

Namespace keys with a stable `keyPrefix` or cache name. Treat the serialized key and value format as stored-data contracts: changing them without a migration leaves unreadable or duplicate entries behind.

## Integrations {#integrations}

- Caffeine: in-process `LOCAL` cache for JDBC or R2DBC.
- Lettuce: Redis-backed repository with an explicit value codec; selected suspend paths can add a Caffeine near cache.
- Redisson: Redis `RMap`/`RLocalCachedMap` with loader and writer integration.
- Test fixtures: reusable read-through, write-through, write-behind, and invalidation scenarios.

## Configuration {#configuration}

`LocalCacheConfig` defaults to 10,000 entries, ten-minute expiry after write, and `READ_ONLY`. Its key prefix must be nonblank; sizes and durations must be positive; the write-behind queue must hold at least one batch. Redis adapters add backend configuration and may enable retry, timeout, and circuit-breaking through `RedisRepositoryResilienceConfig`.

## Failure modes {#failures}

- A local cache is not shared across application instances, so invalidation can diverge.
- Redis failure can turn a cache into an availability dependency unless fallback and timeout policy are explicit.
- Write-behind can lose accepted-but-unflushed data during process failure.
- A codec or key-format change can strand existing Redis entries.
- Calling `close` too late or not at all can leave a queue, connection, or coroutine scope alive.

## Operations {#operations}

Observe cache hit/miss behavior, backend latency, retry/circuit state, write-behind queue depth, rejected writes, and drain results. Set TTL from the data's staleness budget, not from a generic cache default. Document whether a Redis outage fails the request, bypasses cache, or serves stale local data.

## Testing {#testing}

Use the module's test fixtures to lock down miss loading, partial `getAll` misses, synchronous durability, deferred flush, and cache-only invalidation. Give each test a unique key prefix/cache name, clear it in teardown, and close repositories and application-owned clients. Run distributed adapters against an isolated Redis instance rather than sharing developer data.

## Workshops and learning path {#workshops}

1. Read the [cache selection guide](../guides/cache-selection.md) to choose local, remote, or near cache.
2. Implement the smallest repository with the matching Caffeine, Lettuce, or Redisson module.
3. Add failure tests before enabling write-behind or near cache.
4. Continue with [exposed-workshop](https://github.com/bluetape4k/exposed-workshop) for end-to-end cache repository examples.

## Limitations {#limitations}

The contracts do not provide a distributed transaction between cache and database, choose a serialization format, provision Redis, or make a local cache coherent across JVMs. Backend-specific semantics take precedence where their writer or invalidation configuration is more precise.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Repository Interface Class Diagram

[![Repository Interface Class Diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-01.svg)

_Release README: [`exposed/cache/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.md)_

### Cache Configuration Decision Map

[![Cache Configuration Decision Map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-diagram-02.svg)

_Release README: [`exposed/cache/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.md)_

### Write Strategy Patterns diagram

[![Write Strategy Patterns diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cache-sequence-01.svg)

_Release README: [`exposed/cache/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cache/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Module overview](../../../../exposed/cache/README.md)
- [`JdbcCacheRepository`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/JdbcCacheRepository.kt)
- [`R2dbcCacheRepository`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/R2dbcCacheRepository.kt)
- [`LocalCacheConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/LocalCacheConfig.kt)
- [`RedisRepositoryResilienceConfig`](../../../../exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/redis/RedisRepositoryResilienceConfig.kt)
