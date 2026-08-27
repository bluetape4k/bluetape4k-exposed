---
manualId: "bluetape4k-exposed-batch-r2dbc"
id: "bluetape4k-exposed-batch-r2dbc"
title: "Exposed Batch R2DBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch-r2dbc"
sourceDir: "utils/batch/r2dbc"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc
---

# Exposed Batch R2DBC Adapter

> A suspendable R2DBC repository, keyset reader, and batch writer for `batch-core`.

## Problem {#problem}

Reactive applications need durable batch state without importing JDBC classes.
This artifact owns its R2DBC table and mapping definitions.

## When to use it {#when-to-use}

Choose it when the application owns an `R2dbcDatabase`, pool, and suspend
transaction lifecycle. Use the JDBC artifact for blocking Exposed transactions.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc")
    runtimeOnly("io.github.bluetape4k:bluetape4k-jackson3")
}
```

## Core concepts {#concepts}

`ExposedR2dbcBatchJobRepository` applies owner/version CAS inside one
`suspendTransaction`. The R2DBC tables and mappers intentionally do not reuse a
JDBC package.

## Quick start {#quick-start}

Create the application-owned `R2dbcDatabase`, create the batch tables, choose
`CheckpointJson.jackson3()` or a custom serializer, and run the core runner in a
suspend scope.

## API by task {#api-by-task}

- Use `ExposedR2dbcBatchJobRepository` for durable suspendable state.
- Use `ExposedR2dbcBatchReader` for keyset pages and checkpoint restore.
- Use `ExposedR2dbcBatchWriter` for suspendable batch writes.

## Recommended patterns {#patterns}

Keep suspend database work outside non-suspending memory locks, preserve stable
reader ordering, and make writer effects idempotent.

## Integrations {#integrations}

The adapter depends on `batch-core`, Exposed R2DBC, and Bluetape coroutine/R2DBC
utilities. It has no production dependency on the JDBC adapter.

## Configuration {#configuration}

Configure the R2DBC pool, dialect, page size, and dispatcher. The application
owns database creation, transaction composition, metrics, and shutdown.

## Migration and compatibility {#migration}

Replace the former aggregator dependency with `batch-core` plus this adapter and
keep versions supplied by the ecosystem BOM. New code imports the public
`io.bluetape4k.batch.CheckpointJson`; the deprecated
`io.bluetape4k.batch.internal.CheckpointJson` constructor and mapper overloads
remain only as JVM compatibility bridges. Custom repositories must implement
`saveCheckpointAndReturn` so owner/version CAS is retained. Add the optional
`bluetape4k-jackson3` runtime for `CheckpointJson.jackson3()`, or inject a custom
serializer with an explicit allowlist.

Run the compatibility fixtures from the repository root:

```bash
bash scripts/batch/validate_consumer_fixtures.sh
```

The gate covers `aggregator-runtime`, `core-custom-json`, `jdbc-runtime`,
`r2dbc-jackson3-runtime`, `legacy-binary-runtime`, and the Maven JDBC consumer.

## Failure modes {#failures}

Wrong owner, stale version, unclaimed execution, and zero-row CAS updates fail
closed. Cancellation is not converted into a normal failure.
After a committed chunk, a later FAILED step keeps the last checkpoint. A null
failure report checkpoint is treated as no replacement, so the R2DBC completion
update does not erase the stored value and restart can resume after that key.

## Operations {#operations}

Observe suspend transaction latency, lease expiry, CAS conflicts, checkpoint
size, and driver errors. Keep schema creation and migration outside the adapter.

## Testing {#testing}

Run `./gradlew :bluetape4k-exposed-batch-r2dbc:test` sequentially for H2,
PostgreSQL, and MySQL_V8. The suite covers repository CAS, reader/writer
round-trips, restart, and R2DBC mapping.

## Workshops and learning path {#workshops}

Read the [core manual](bluetape4k-exposed-batch-core.md), then compare the
[JDBC adapter](bluetape4k-exposed-batch-jdbc.md) before selecting a transaction model.

## Limitations {#limitations}

The adapter does not own pools, schema migration, or exactly-once external
effects. Checkpoint persistence remains at-least-once.

## Sources {#sources}

- [`ExposedR2dbcBatchJobRepository.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchJobRepository.kt)
- [`BatchJobExecutionTable.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/tables/BatchJobExecutionTable.kt)
- [`ExposedR2dbcBatchReader.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchReader.kt)
