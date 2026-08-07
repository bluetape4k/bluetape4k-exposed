---
manualId: "bluetape4k-exposed-r2dbc-lettuce"
id: "bluetape4k-exposed-r2dbc-lettuce"
title: "Exposed R2DBC Lettuce Cache"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-r2dbc-lettuce"
sourceDir: "exposed/r2dbc-lettuce"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-lettuce
---

# Exposed R2DBC Lettuce Cache

This adapter combines coroutine-first R2DBC with Redis through Lettuce. It implements the common cache repository contract without hiding lifecycle or durability choices.

## Problem {#problem}

Cache and database state can diverge when a miss, write, invalidation, timeout, cancellation, or shutdown completes only partly. The adapter supplies the integration; the application still defines acceptable staleness and failure behavior.

## When to use it {#when-to-use}

Use this module when Redis through Lettuce is the chosen backend and the persistence path is coroutine-first R2DBC. Compare all six adapters in the [cache selection guide](../guides/cache-selection.md) before adding infrastructure.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-lettuce")
}
```

Users select the central BOM version; this page records the stable `1.11` source line.

## Core concepts {#concepts}

`get` and `getAll` are read-through operations. Cache-aside is an application-owned DB update followed by invalidation. True write-through requires the configured writer to finish DB persistence before returning. Write-behind accepts a delayed durability window. Ordinary `put` must not be called write-through without that writer policy. This adapter is when enabled, lookup proceeds Caffeine near cache, Redis, then the R2DBC loader; invalidation must clear both tiers.

## Quick start {#quick-start}

Extend `AbstractR2dbcLettuceRepository` and provide `table`, `extractId`, `ResultRow.toEntity`, and the update/insert DSL hooks. Construct it with a reviewed `LettuceCacheConfig`.

```kotlin
repository.use { repo ->
    val current = repo.get(id)          // miss -> DB loader -> cache
    current?.let { repo.put(id, it) }   // durability follows write mode
    repo.invalidate(id)                 // cache-only by default
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Cache-backed read | `containsKey`, `get`, `getAll` |
| DB bypass | `findByIdFromDb`, `findAllFromDb`, `countFromDb` |
| Policy-controlled write | `put`, `putAll` |
| Eviction | `invalidate`, `invalidateAll`, `clear` |
| Entity mapping | `ResultRow.toEntity`, `extractId`, update/insert hooks |
| Lifecycle | `close` |

## Recommended patterns {#patterns}

Keep one repository per cache namespace and close it with the application. For cache-aside, commit the DB transaction before invalidating. Treat write-through failure as an incomplete write. Before write-behind, expose queue/dead-letter state and define the shutdown drain budget. Keep key prefixes and serialized value formats stable.

## Integrations {#integrations}

The module joins Exposed R2DBC, the shared cache foundation, and Redis through Lettuce. Loader/writer adapters own the cache-to-table bridge; the application owns the surrounding service transaction and client lifecycle.

## Configuration {#configuration}

`LettuceCacheConfig` controls namespace, TTL/expiry, cache/write mode, and backend limits. An explicit `RedisCodec<String, E>` is required; plan migration before changing it. Validate positive durations and bounded batch/queue sizes at startup.

## Failure modes {#failures}

Coroutine cancellation, Redis timeout, and DB retry are distinct failures. Write-behind returns before the DB transaction commits. A cache hit can be stale, a backend success can be followed by DB failure, and DB commit can be followed by failed invalidation. Record these as distinct states.

## Operations {#operations}

The application owns `RedisClient`; the repository closes its connection and optional near cache. Monitor hit/miss, backend latency, retries/timeouts, queue depth, rejected or dead-letter writes, invalidation lag, and shutdown drain. Set separate SLOs for cache and database paths.

## Testing {#testing}

Use isolated cache and database fixtures with unique namespaces. Prove miss loading, partial `getAll` misses, each enabled write mode, cache-only invalidation, TTL, partial failure, and lifecycle cleanup.

```bash
./gradlew :bluetape4k-exposed-r2dbc-lettuce:test
```

## Workshops and learning path {#workshops}

1. Read [Exposed cache foundation](bluetape4k-exposed-cache.md).
2. Choose a backend in [cache selection](../guides/cache-selection.md).
3. Implement read-through plus cache-only invalidation first.
4. Add partial-failure and shutdown tests before near cache or write-behind.
5. Continue with [exposed-workshop](https://github.com/bluetape4k/exposed-workshop).

## Limitations {#limitations}

The adapter does not create a distributed transaction, provision the backend, migrate stored cache values, or decide whether stale data is safe. It is when enabled, lookup proceeds Caffeine near cache, Redis, then the R2DBC loader; invalidation must clear both tiers.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### R2DBC Lettuce Redis cache architecture diagram

[![R2DBC Lettuce Redis cache architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-lettuce-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-lettuce-diagram-01.svg)

_Release README: [`exposed/r2dbc-lettuce/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-lettuce/README.md)_

### R2DBC Lettuce cache sequence diagram

[![R2DBC Lettuce cache sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-lettuce-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-lettuce-sequence-01.svg)

_Release README: [`exposed/r2dbc-lettuce/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-lettuce/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Module README](../../../../exposed/r2dbc-lettuce/README.md)
- [Abstract repository](../../../../exposed/r2dbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/r2dbc/lettuce/repository/AbstractR2dbcLettuceRepository.kt)
- [Module build](../../../../exposed/r2dbc-lettuce/build.gradle.kts)
