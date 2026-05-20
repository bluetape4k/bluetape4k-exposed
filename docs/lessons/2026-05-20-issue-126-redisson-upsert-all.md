# Issue 126 Redisson `upsertAll`

## Context

Milestone 1.8.1 needed an explicit Redisson bulk upsert API for cache warming and
bulk write-through/write-behind paths.

## Decision

Use Redisson 4.4.0 `putAll(Map, batchSize)` / `putAllAsync(Map, batchSize)` as
the implementation. The issue mentioned `fastPutAllAsync`, but the local
Redisson API does not provide that method.

## Outcome

Added `upsertAll` to JDBC, Suspended JDBC, and R2DBC Redisson repository APIs,
centralized `putAll` through it, and documented the new API in module README
pairs.

## Verification

- `./gradlew :bluetape4k-exposed-jdbc-redisson:compileKotlin :bluetape4k-exposed-r2dbc-redisson:compileKotlin :bluetape4k-exposed-jdbc-redisson:compileTestKotlin :bluetape4k-exposed-r2dbc-redisson:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-r2dbc-redisson:test --tests "io.bluetape4k.exposed.redisson.repository.ReadWriteThroughCacheTest" --tests "io.bluetape4k.exposed.r2dbc.redisson.repository.R2dbcReadWriteThroughCacheTest"`: 421 passing
- `./gradlew :bluetape4k-exposed-jdbc-redisson:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-redisson:test --tests "io.bluetape4k.exposed.redisson.repository.ReadWriteThroughCacheTest" --tests "io.bluetape4k.exposed.redisson.repository.SuspendedReadWriteThroughCacheTest"`: 320 passing

## Future Guidance

Before selecting a Redisson bulk primitive, inspect the actual local Redisson jar
API. Do not assume `fastPutAll*` exists across versions.
