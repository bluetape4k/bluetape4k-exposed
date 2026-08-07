---
manualId: "bluetape4k-exposed-jdbc-redisson"
id: "bluetape4k-exposed-jdbc-redisson"
title: "Exposed JDBC Redisson Cache"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-jdbc-redisson"
sourceDir: "exposed/jdbc-redisson"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson
---

# Exposed JDBC Redisson Cache

This adapter combines blocking JDBC or suspended JDBC with Redis through Redisson. It implements the common cache repository contract without hiding lifecycle or durability choices.

## Problem {#problem}

Cache and database state can diverge when a miss, write, invalidation, timeout, cancellation, or shutdown completes only partly. The adapter supplies the integration; the application still defines acceptable staleness and failure behavior.

## When to use it {#when-to-use}

Use this module when Redis through Redisson is the chosen backend and the persistence path is blocking JDBC or suspended JDBC. Compare all six adapters in the [cache selection guide](../guides/cache-selection.md) before adding infrastructure.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-redisson")
}
```

Users select the central BOM version; this page records the stable `1.11` source line.

## Core concepts {#concepts}

`get` and `getAll` are read-through operations. Cache-aside is an application-owned DB update followed by invalidation. True write-through requires the configured writer to finish DB persistence before returning. Write-behind accepts a delayed durability window. Ordinary `put` must not be called write-through without that writer policy. This adapter is the config can choose an `RMapCache` or `RLocalCachedMap`; near-cache synchronization is part of correctness.

## Quick start {#quick-start}

Extend `AbstractJdbcRedissonRepository` and provide `table`, `extractId`, `ResultRow.toEntity`, and the update/insert DSL hooks. Construct it with a reviewed `RedissonCacheConfig`.

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

The module joins Exposed JDBC, the shared cache foundation, and Redis through Redisson. Loader/writer adapters own the cache-to-table bridge; the application owns the surrounding service transaction and client lifecycle.

## Configuration {#configuration}

`RedissonCacheConfig` controls namespace, TTL/expiry, cache/write mode, and backend limits. Fory/Kryo/JDK-family codecs require `trustedBinaryCache=true`; otherwise provide a reviewed codec. Validate positive durations and bounded batch/queue sizes at startup.

## Failure modes {#failures}

Invalidation is cache-only by default. `deleteFromDBOnInvalidate=true` changes eviction into database deletion and needs a separate destructive-policy review. A cache hit can be stale, a backend success can be followed by DB failure, and DB commit can be followed by failed invalidation. Record these as distinct states.

## Operations {#operations}

The application owns `RedissonClient`; do not shut down a client shared by other components from a repository. Monitor hit/miss, backend latency, retries/timeouts, queue depth, rejected or dead-letter writes, invalidation lag, and shutdown drain. Set separate SLOs for cache and database paths.

## Testing {#testing}

Use isolated cache and database fixtures with unique namespaces. Prove miss loading, partial `getAll` misses, each enabled write mode, cache-only invalidation, TTL, partial failure, and lifecycle cleanup.

```bash
./gradlew :bluetape4k-exposed-jdbc-redisson:test
```

## Workshops and learning path {#workshops}

1. Read [Exposed cache foundation](bluetape4k-exposed-cache.md).
2. Choose a backend in [cache selection](../guides/cache-selection.md).
3. Implement read-through plus cache-only invalidation first.
4. Add partial-failure and shutdown tests before near cache or write-behind.
5. Continue with [exposed-workshop](https://github.com/bluetape4k/exposed-workshop).

## Limitations {#limitations}

The adapter does not create a distributed transaction, provision the backend, migrate stored cache values, or decide whether stale data is safe. It is the config can choose an `RMapCache` or `RLocalCachedMap`; near-cache synchronization is part of correctness.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### JDBC Redisson Redis cache architecture diagram

[![JDBC Redisson Redis cache architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-01.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### JDBC Redisson synchronous repository hierarchy diagram

[![JDBC Redisson synchronous repository hierarchy diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-diagram-02.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### JDBC Redisson read-through sequence diagram

[![JDBC Redisson read-through sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-01.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### JDBC Redisson write-through sequence diagram

[![JDBC Redisson write-through sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-02.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### JDBC Redisson write-behind sequence diagram

[![JDBC Redisson write-behind sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-03.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### Suspended JDBC Redisson read-through sequence diagram

[![Suspended JDBC Redisson read-through sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-04.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-04.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### Suspended JDBC Redisson write-through sequence diagram

[![Suspended JDBC Redisson write-through sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-05.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-05.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

### Suspended JDBC Redisson write-behind sequence diagram

[![Suspended JDBC Redisson write-behind sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-06.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-jdbc-redisson-sequence-06.svg)

_Release README: [`exposed/jdbc-redisson/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/jdbc-redisson/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Module README](../../../../exposed/jdbc-redisson/README.md)
- [Abstract repository](../../../../exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/repository/AbstractJdbcRedissonRepository.kt)
- [Module build](../../../../exposed/jdbc-redisson/build.gradle.kts)
