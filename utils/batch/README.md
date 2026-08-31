# exposed-batch

[한국어](./README.ko.md) | English

A coroutine-native batch processing framework for Kotlin. Implements a lightweight, checkpointable chunk-oriented pipeline — no Spring Batch required.

## Runtime Role Map

![Batch runtime role map](../../docs/images/readme-diagrams/utils-batch-diagram-01.png)

## Chunk Checkpoint Flow

![Batch chunk checkpoint flow](../../docs/images/readme-diagrams/utils-batch-sequence-01.png)

## Features

- **Coroutine-first**: all interfaces are `suspend`; no `runBlocking` or thread blocking
- **Checkpointable restart**: keyset-based checkpoint survives JVM crash; already-completed steps are skipped on restart
- **Chunk-oriented pipeline**: `BatchReader → BatchProcessor → BatchWriter` with configurable chunk size
- **Skip policy**: per-item skip on processor/writer failure (`NONE` / `ALL` / `maxSkips(n)` / custom lambda)
- **Retry with backoff**: chunk-level retry with configurable delay and exponential backoff
- **Commit timeout**: `WriteTimeoutException` wrapper prevents indefinite hangs; retried/skipped like any other error
- **Cancellation safe**: `CancellationException` is never swallowed; `STOPPED` status is persisted before re-throwing
- **Workflow integration**: `BatchJob` implements `SuspendWork` for embedding in `bluetape4k-workflow` pipelines
- **JDBC + R2DBC readers/writers**: Exposed-based implementations for both blocking and reactive databases

## Quick Start

### DSL

```kotlin
val job = batchJob("importUsers") {
    repository(myJdbcRepository)
    params("date" to "2026-04-10")
    step<UserCsv, UserEntity>("loadStep") {
        reader(csvReader)
        processor { csv -> UserEntity(csv.name, csv.email) }
        writer(jdbcWriter)
        chunkSize(500)
        skipPolicy(SkipPolicy.maxSkips(100))
        retryPolicy(RetryPolicy(maxAttempts = 3, delay = 1.seconds))
        commitTimeout(30.seconds)
    }
}

private object ExampleLog : io.bluetape4k.logging.KLogging()
val report = job.run()
when (report) {
    is BatchReport.Success           -> ExampleLog.log.info { "완료: ${report.stepReports[0].writeCount} rows" }
    is BatchReport.PartiallyCompleted -> ExampleLog.log.info { "부분완료: skip=${report.stepReports.sumOf { it.skipCount }}" }
    is BatchReport.Failure           -> ExampleLog.log.error(report.error) { "실패" }
}
```

### Restart

```kotlin
// First run — fails at step 2
val report1 = job.run()  // BatchReport.Failure

// Second run — step 1 is COMPLETED, so it's skipped automatically
val report2 = job.run()  // only step 2 runs again
```

### Job parameter identity

Persistent JDBC and R2DBC repositories identify a restartable job by
`jobName + BatchParameterHash`. The shared `v2` encoding sorts keys and records
the UTF-8 byte length and runtime type of every key/value before calculating a
lowercase SHA-256 digest. Delimiters inside a value and values such as `1`
(`Int`) and `"1"` (`String`) therefore remain distinct. JDBC and R2DBC use the
same core implementation.

Supported values are deterministic scalars (`String`, numbers, `Boolean`,
`Char`, `Enum`, `UUID`, `Instant`, `LocalDate`, `LocalDateTime`, `LocalTime`,
`OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Year`, `YearMonth`, `ZoneId`,
and `ZoneOffset`), `Map`, `List`, `Set`, and arrays. Arbitrary objects and generic
`Iterable` values are rejected because
their `toString()` output or iteration order can vary between processes.
Normalize custom values to the supported scalars or collections first.

Strings must contain well-formed UTF-16; unpaired surrogates are rejected
instead of being replaced during UTF-8 encoding. Canonical input is limited to
1 MiB of UTF-8, each scalar to 256 KiB, each container to 1,000 items, the full
tree to 10,000 values, and nesting to 32 levels. Exceeding a limit throws
`IllegalArgumentException`.

Treat the parameter map and every nested collection or array as immutable for
the entire repository call. Concurrent mutation is unsupported because the
identity hash and persisted parameter payload must describe the same input.

An empty parameter map keeps the existing `""` hash rule. The `v2` algorithm
changes non-empty hashes from the legacy `key=value&...` format; existing rows
with legacy hashes are not silently matched. Before a rollout, re-key or
retire active legacy rows from their persisted parameters in a controlled
migration, then use only `v2` for new executions.

### Workflow Embedding

```kotlin
val pipeline = sequentialWorkflow {
    work(validationJob)  // BatchJob implements SuspendWork
    work(importJob)
    work(reportJob)
}
val workReport = pipeline.run(WorkContext())
```

## Components

### Core

| Class | Description |
|-------|-------------|
| `BatchJob` | Orchestrates steps sequentially; supports restart; implements `SuspendWork` |
| `BatchStep` | Defines reader → processor → writer pipeline configuration |
| `BatchStepRunner` | Executes a single step's chunk loop with skip/retry/checkpoint |

### API Interfaces

| Interface | Description |
|-----------|-------------|
| `BatchReader<T>` | Reads items one at a time; provides checkpoint |
| `BatchProcessor<I, O>` | Transforms items (null return = filter) |
| `BatchWriter<T>` | Writes a chunk of items |
| `BatchJobRepository` | Persists job/step execution state |
| `SkipPolicy` | Decides whether to skip on exception |

### Implementations

| Class | Description |
|-------|-------------|
| `InMemoryBatchJobRepository` | In-memory repository for testing and simple use cases |
| `ExposedJdbcBatchJobRepository` | JDBC-based repository using Exposed + Virtual Threads |
| `ExposedR2dbcBatchJobRepository` | R2DBC-based repository using Exposed suspend transactions |
| `ExposedJdbcBatchReader<K, E>` | Keyset-paginated JDBC reader |
| `ExposedR2dbcBatchReader<K, E>` | Keyset-paginated R2DBC reader |
| `ExposedJdbcBatchWriter` | Bulk JDBC insert/update writer |
| `ExposedR2dbcBatchWriter` | Bulk R2DBC insert writer |

### Skip Policies

```kotlin
SkipPolicy.NONE                      // never skip (default)
SkipPolicy.ALL                       // always skip
SkipPolicy.maxSkips(100L)            // skip up to 100 items
SkipPolicy { e, count -> e is DataException && count < 50 }  // custom
```

## Checkpoint Protocol

1. Reader returns a checkpoint value via `checkpoint()` after each `onChunkCommitted()` call
2. `BatchStepRunner` persists the checkpoint to the repository after each successful write
3. On restart, the checkpoint is restored via `reader.restoreFrom(checkpoint)` before the chunk loop begins
4. `TypedCheckpoint` envelope (Jackson 3) ensures type-safe round-trip for all serializable types

## Repository concurrency and schema prerequisites

`batch_job_execution` uses a unique index on `(job_name, params_hash, active_key)` to allow at most one reusable execution. `active_key` is `ACTIVE` for `STARTING`, `RUNNING`, `FAILED`, and `STOPPED` rows, and `NULL` for `COMPLETED` and `COMPLETED_WITH_SKIPS` history. This contract relies on `NULLS DISTINCT` behavior in PostgreSQL, MySQL 8.0.16+ InnoDB, and H2.

Apply the existing-schema artifacts before starting the new runtime. The library does not execute production DDL during application startup.

1. Quiesce writers and run `schema/<backend>/V001__active_job_execution_key_preflight.sql`; unknown statuses, null keys, and active duplicates must be absent.
2. Run `schema/<backend>/V001__active_job_execution_key_migrate.sql` with the migration role.
3. Run `schema/<backend>/V001__active_job_execution_key_postflight.sql`; open traffic only when every diagnostic count is zero and `batch_job_execution_active_uidx` exists.

After a unique conflict, the repository re-queries the active winner. If that winner becomes terminal first, the repository makes one bounded attempt to create a new active execution and finishes with one final winner query after another conflict. JDBC/R2DBC regression tests exercise the post-conflict re-query barrier on H2, PostgreSQL, and MySQL.

`completeJobExecution` and `completeStepExecution` accept only terminal statuses: `COMPLETED`, `COMPLETED_WITH_SKIPS`, `FAILED`, and `STOPPED`. `STARTING` and `RUNNING` are rejected with `IllegalArgumentException` before persistence. Exhausted bounded recovery raises `BatchRepositoryRecoveryExhaustedException` with a 16-character Base58 correlation ID.

## BatchStatus Transitions

```
STARTING → RUNNING → COMPLETED
                   → COMPLETED_WITH_SKIPS
                   → FAILED
                   → STOPPED (cancellation)
```

**Important**: `StepExecution` rows with `COMPLETED` or `COMPLETED_WITH_SKIPS` are skipped automatically on restart.

## Benchmarks

The benchmark setup has been migrated to `kotlinx-benchmark` with DB-specific profiles for JDBC + Virtual Threads and R2DBC.

| DB | Summary | Details |
|----|---------|---------|
| H2 | Compare JDBC vs R2DBC for `seedBenchmark` and `endToEndBatchJobBenchmark` | [H2 benchmark details](benchmark/h2.md) |
| PostgreSQL | Compare JDBC vs R2DBC for the same scenarios with Testcontainers-backed execution | [PostgreSQL benchmark details](benchmark/postgresql.md) |
| MySQL | Compare JDBC vs R2DBC across seed and end-to-end batch job runs | [MySQL benchmark details](benchmark/mysql.md) |

- [Benchmark hub](benchmark/README.md)
- Example tasks: `./gradlew :bluetape4k-exposed-batch:h2JdbcBenchmark`, `./gradlew :bluetape4k-exposed-batch:postgresR2dbcBenchmark`, `./gradlew :bluetape4k-exposed-batch:generateBenchmarkDocs` (each benchmark task writes and validates its `metadata.json` sidecar)

### Comparison Focus

- Primary axis: **JDBC vs R2DBC**
- Scenarios: `seedBenchmark`, `endToEndBatchJobBenchmark`
- Parameters: `dataSize = 1000/10000/100000`, `poolSize = 10/30/60`, `parallelism = 1/4/8`
- Detailed tables and charts live under `benchmark/*.md`

![Batch seed throughput by database chart](../../docs/images/readme-charts/utils-batch-db-summary-chart-01.png)

## Module Dependencies

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))

    // Existing users can keep the compatibility aggregator:
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch")

    // Select only the runtime boundary you need:
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-core")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc")
    // or: implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc")

    // Workflow embedding is version-aligned by the same BOM:
    implementation("io.github.bluetape4k:bluetape4k-workflow")
}
```

`bluetape4k-exposed-batch-core` does not publish Exposed, JDBC, R2DBC, or
Jackson as runtime dependencies. Use the JDBC or R2DBC child only when that
adapter is part of the application boundary. `CheckpointJson` is an explicit
strategy: the core artifact supports a custom implementation without Jackson;
`CheckpointJson.jackson3()` requires `bluetape4k-jackson3` on the runtime
classpath.

## Lease Safety and Operations

Each job and step execution is fenced by an `ownerId` and a monotonically
increasing `version`. The repository renews the job and step lease atomically;
the runner performs a final lease check immediately before each writer call and
checkpoint mutation. A lost lease returns a sanitized lease-loss failure and
does not start another writer call. Custom `BatchJobRepository` implementations
must advertise authoritative claim and atomic renewal support before a job
starts; an adapter that does not implement those capabilities is rejected
fail-closed.

Configure the same lease on the DSL job and its steps. The supported lease
range is 30 seconds through 24 hours, and the default is 15 minutes:

JDBC and R2DBC lease database timeouts are currently implemented for
PostgreSQL, H2, and MySQL dialects. Calling a lease operation with another
Exposed dialect fails fast with an `Unsupported database dialect for batch lease
timeout` error; configure a supported dialect before enabling lease renewal.

```kotlin
val job = batchJob("importUsers") {
    executionLease(15.minutes)
    step<UserCsv, UserEntity>("loadStep") {
        executionLease(15.minutes)
    }
}
```

Do not automatically retry a lease-loss runner with the same execution object.
Read the database owner/version/lease state, reconcile whether the external
writer used an idempotency key, outbox, or equivalent fencing, and only then
start a new execution. This library does not provide exactly-once semantics for
external systems.
