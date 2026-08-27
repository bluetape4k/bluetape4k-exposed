---
manualId: "bluetape4k-exposed-batch-jdbc"
id: "bluetape4k-exposed-batch-jdbc"
title: "Exposed Batch JDBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch-jdbc"
sourceDir: "utils/batch/jdbc"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc
---

# Exposed Batch JDBC Adapter

> A JDBC repository, keyset reader, and batch writer for the `batch-core` runtime.

## Problem {#problem}

Applications need durable batch execution without coupling the core artifact to
JDBC classes. This adapter owns the Exposed JDBC tables and transactions.

## When to use it {#when-to-use}

Use it when the application owns a JDBC `Database`, connection pool, and
transaction lifecycle. Use the R2DBC artifact for a fully suspendable path.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc")
}
```

## Core concepts {#concepts}

`ExposedJdbcBatchJobRepository` stores owner, lease, version, status, counters,
parameters, and checkpoints. Owner-aware updates require matching owner and
version and must affect exactly one row.

## Quick start {#quick-start}

Create the application-owned `Database`, create the two batch tables, and pass a
`CheckpointJson` implementation to the repository. The reader and writer use the
same JDBC transaction boundary supplied by the application.

## API by task {#api-by-task}

- Use `ExposedJdbcBatchJobRepository` for durable job and step state.
- Use `ExposedJdbcBatchReader` for keyset page reads and checkpoints.
- Use `ExposedJdbcBatchWriter` for batch inserts with explicit duplicate policy.

## Recommended patterns {#patterns}

Keep transactions short, use stable key ordering for readers, and treat the
ID-only checkpoint overload as a trusted administrative escape hatch.

## Integrations {#integrations}

The adapter depends on `batch-core` and Exposed JDBC only. It does not import the
R2DBC adapter or require Spring Boot.

## Configuration {#configuration}

Configure the JDBC pool, dialect, page size, and application transaction scope.
The adapter does not create or close the application's `Database`.

## Migration and compatibility {#migration}

Replace the former aggregator dependency with `batch-core` plus this adapter and
keep versions supplied by the ecosystem BOM. Use the public
`io.bluetape4k.batch.CheckpointJson`; the constructor accepting the deprecated
`io.bluetape4k.batch.internal.CheckpointJson` and the legacy mapper overloads are
JVM compatibility bridges only. Migrate custom repositories to
`saveCheckpointAndReturn` so owner/version CAS is preserved during checkpoint
writes. The Jackson 3 strategy is optional; add `bluetape4k-jackson3` or inject a
custom `CheckpointJson` with an allowlisted type registry.

Run the compatibility fixtures from the repository root:

```bash
bash scripts/batch/validate_consumer_fixtures.sh
```

That gate compiles and runs `aggregator-runtime`, `core-custom-json`,
`jdbc-runtime`, `r2dbc-jackson3-runtime`, `legacy-binary-runtime`, and the Maven
JDBC consumer against the published-style artifacts.

## Failure modes {#failures}

Wrong owner, stale version, unclaimed execution, and zero-row CAS updates fail
explicitly. A lost lease must be reconciled before retrying external effects.
After a committed chunk, a later FAILED step keeps the last checkpoint. A null
failure report checkpoint is treated as no replacement, so the JDBC completion
update does not erase the stored value and restart can resume after that key.

## Operations {#operations}

Monitor database lock latency, lease expiry, CAS conflicts, checkpoint size, and
writer duplicate errors. Keep schema ownership and migrations with the application.

## Testing {#testing}

Run `./gradlew :bluetape4k-exposed-batch-jdbc:test` with H2, PostgreSQL, and
MySQL_V8 in sequence. The suite covers repository CAS, restart, reader paging,
writer behavior, and Exposed mapper round trips.

## Workshops and learning path {#workshops}

Read the [core manual](bluetape4k-exposed-batch-core.md) first, then compare the
[R2DBC adapter](bluetape4k-exposed-batch-r2dbc.md) when choosing a transaction model.

## Limitations {#limitations}

The adapter does not provide schema migration, connection-pool ownership, or
exactly-once external effects. Checkpoint persistence remains at-least-once.

## Sources {#sources}

- [`ExposedJdbcBatchJobRepository.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchJobRepository.kt)
- [`BatchJobExecutionTable.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/tables/BatchJobExecutionTable.kt)
- [`ExposedJdbcBatchReader.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchReader.kt)
