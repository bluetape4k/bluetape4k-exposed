---
manualId: "bluetape4k-exposed-r2dbc-caffeine"
id: "bluetape4k-exposed-r2dbc-caffeine"
title: "Exposed R2DBC Caffeine Cache"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-r2dbc-caffeine"
sourceDir: "exposed/r2dbc-caffeine"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-caffeine
---

# Exposed R2DBC Caffeine Cache

This adapter combines coroutine-first R2DBC with in-process Caffeine AsyncCache. It implements the common cache repository contract without hiding lifecycle or durability choices.

## Problem {#problem}

Cache and database state can diverge when a miss, write, invalidation, timeout, cancellation, or shutdown completes only partly. The adapter supplies the integration; the application still defines acceptable staleness and failure behavior.

## When to use it {#when-to-use}

Use this module when in-process Caffeine AsyncCache is the chosen backend and the persistence path is coroutine-first R2DBC. Compare all six adapters in the [cache selection guide](../guides/cache-selection.md) before adding infrastructure.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-caffeine")
}
```

Users select the central BOM version; this page records the stable `1.12.1` source line.

## Core concepts {#concepts}

`get` and `getAll` are read-through operations. Cache-aside is an application-owned DB update followed by invalidation. True write-through requires the configured writer to finish DB persistence before returning. Write-behind accepts a delayed durability window. Ordinary `put` must not be called write-through without that writer policy. This adapter is local-only; it has no distributed invalidation.

The stable `1.12.1` adapter owns its R2DBC transactions, coroutine scope, and `AsyncCache`; write-behind remains an explicitly delayed durability mode. The lifecycle coordinator, bounded admission accounting, publication lease, and terminal retry behavior described below are current develop changes and are not part of the stable `1.12.1` contract.

**Develop-only write-behind contract:** `writeBehindBatchSize` and `writeBehindQueueCapacity` must each be in `1..100_000`, and queue capacity must be greater than or equal to batch size. Zero, negative, values above `100_000`, or a queue smaller than the batch fail fast with `IllegalArgumentException`. A failed flush is retried at most 8 times with capped exponential backoff from 10 ms to 1 s; the retained batch is not dropped before the retry limit. A terminal failure leaves the worker in `FAILED`; the library does not silently move the retained batch to a durable outbox or dead-letter store.

## Quick start {#quick-start}

Extend `AbstractR2dbcCaffeineRepository` and provide `table`, `extractId`, `ResultRow.toEntity`, and the update/insert DSL hooks. Construct it with a reviewed `LocalCacheConfig`.

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

Keep one repository per cache namespace and close it with the application. For cache-aside, commit the DB transaction before invalidating. Treat write-through failure as an incomplete write. Before write-behind, expose queue state and define the shutdown drain budget. `putAll` processes entries in input order; if an entry fails, earlier effects remain visible and the exception identifies the failing operation—there is no implicit rollback. After an accepted queue handoff, a cache publication failure invalidates that key. Keep key prefixes and serialized value formats stable.

## Integrations {#integrations}

The module joins Exposed R2DBC, the shared cache foundation, and in-process Caffeine AsyncCache. Loader/writer adapters own the cache-to-table bridge; the application owns the surrounding service transaction and client lifecycle.

## Configuration {#configuration}

`LocalCacheConfig` controls namespace, TTL/expiry, cache/write mode, and backend limits. Values remain local; entity mapping and repository operations are suspend functions. Validate positive durations and bounded batch/queue sizes at startup.

## Failure modes {#failures}

Caller cancellation can happen while cache or R2DBC work is in flight. Write-behind acknowledgement does not mean the DB has committed. A cache hit can be stale, a backend success can be followed by DB failure, and DB commit can be followed by failed invalidation. Record these as distinct states.

## Operations {#operations}

The repository owns the async cache and bounded write-behind scope; close it during application shutdown. In the develop implementation, `close()` first stops new admissions, attempts publication and worker drain within a finite shutdown boundary, and then invalidates the cache; timeout or interruption can leave a residual batch or failure for operators to observe. Monitor hit/miss, backend latency, retries/timeouts, queue depth, rejected writes, invalidation lag, and shutdown drain. For durable retry or dead-letter recovery, the application must own the outbox schema, replay/idempotency policy, alerting, and operator runbook; coordinator `FAILED` is an observation boundary, not a durable recovery mechanism. Set separate SLOs for cache and database paths.

## Testing {#testing}

Use isolated cache and database fixtures with unique namespaces. Prove miss loading, partial `getAll` misses, each enabled write mode, cache-only invalidation, TTL, partial failure, and lifecycle cleanup.

```bash
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test
```

## Workshops and learning path {#workshops}

1. Read [Exposed cache foundation](bluetape4k-exposed-cache.md).
2. Choose a backend in [cache selection](../guides/cache-selection.md).
3. Implement read-through plus cache-only invalidation first.
4. Add partial-failure and shutdown tests before near cache or write-behind.
5. Continue with [exposed-workshop](https://github.com/bluetape4k/exposed-workshop).

## Limitations {#limitations}

The adapter does not create a distributed transaction, provision the backend, migrate stored cache values, or decide whether stale data is safe. It is local-only; it has no distributed invalidation.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### R2DBC Caffeine local cache architecture diagram

[![R2DBC Caffeine local cache architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.svg)

_Release README: [`exposed/r2dbc-caffeine/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-caffeine/README.md)_

### R2DBC Caffeine cache sequence diagram

[![R2DBC Caffeine cache sequence diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.svg)

_Release README: [`exposed/r2dbc-caffeine/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/r2dbc-caffeine/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Module README](../../../../exposed/r2dbc-caffeine/README.md)
- [Abstract repository](../../../../exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/repository/AbstractR2dbcCaffeineRepository.kt)
- [Module build](../../../../exposed/r2dbc-caffeine/build.gradle.kts)
