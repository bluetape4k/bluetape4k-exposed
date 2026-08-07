---
manualId: "bluetape4k-exposed-clickhouse"
id: "bluetape4k-exposed-clickhouse"
title: "Exposed ClickHouse Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-clickhouse"
sourceDir: "exposed/clickhouse"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-clickhouse
---

# Exposed ClickHouse Adapter

`bluetape4k-exposed-clickhouse` adapts Exposed JDBC queries and ClickHouse-specific table engines, types, aggregates, and date functions to ClickHouse's autocommit OLAP model.

## Problem {#problem}

Exposed expects JDBC transaction and relational DDL conventions that ClickHouse does not share. The adapter registers a dialect, forces autocommit, sanitizes table DDL, and exposes ClickHouse-native engine and column DSLs.

## When to use it {#when-to-use}

Use it for analytical reads and append-oriented writes where ClickHouse is already the execution engine. Do not use an Exposed `transaction {}` block as an atomic multi-statement unit.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-clickhouse")
}
```

## Core concepts {#concepts}

- `ClickHouseDatabase` registers the JDBC driver and dialect.
- `ClickHouseConnectionWrapper` enforces autocommit; `commit` and `rollback` are no-ops.
- `ClickHouseTable` keeps only `CREATE TABLE`, removes relational constraints/null tokens, and appends an engine clause.
- The module supplies MergeTree DSL, unsigned/array/low-cardinality/time types, and analytical functions.

## Quick start {#quick-start}

```kotlin
val db = ClickHouseDatabase.connect(
    host = "localhost", port = 8123, database = "default",
    user = "default", password = "",
)
transaction(db) {
    Events.selectAll().limit(100).toList()
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Connect | `ClickHouseDatabase.connect(...)` |
| Engine-aware DDL | extend `ClickHouseTable`; configure `mergeTree { ... }` |
| Coroutine bridge | `suspendTransaction` |
| Materialized Flow | `queryFlow` |
| Explicitly reject invalid SQL patterns | `ClickHouseUnsupported` |

## Recommended patterns {#patterns}

Make each write independently retryable and idempotent where possible. Use batch inserts for ingestion and a MergeTree engine suited to the deduplication/order requirement. Apply paging or aggregation at the database; `queryFlow` materializes the result before emission.

## Integrations {#integrations}

The ClickHouse JDBC driver is bundled as an API dependency. Release tests use Testcontainers ClickHouse and cover connection validation, DDL, inserts, batch inserts, engine clauses, functions, and custom types. The runnable OLTP/OLAP comparison lives in `examples-exposed-clickhouse-oltp-olap`.

## Configuration {#configuration}

The convenience connection uses HTTP port `8123`; a full `jdbc:clickhouse://...` URL is also accepted. Validate the chosen engine, `ORDER BY`, partition key, and server-side retention settings as part of schema review.

## Failure modes {#failures}

- A later statement failure does not roll back earlier writes.
- `INSERT IGNORE`, `ON CONFLICT`/upsert, and `RETURNING` are unsupported.
- Primary keys, foreign keys, comments, sequences, and `ALTER COLUMN TYPE` are not emitted as ordinary relational DDL.
- `queryFlow` can hold the full result in memory.

## Operations {#operations}

Observe insert batch size, rejected rows, query duration, parts/merges, and memory. Keep retry boundaries smaller than a logical batch and record a deduplication key when a producer may repeat writes.

## Testing {#testing}

Run integration tests against a disposable ClickHouse container. Assert both generated engine DDL and query results. Add a partial-write test for every workflow that contains more than one DML statement.

## Workshops and learning path {#workshops}

Use the [adapter matrix](../guides/database-adapter-matrix.md), then run [the ClickHouse OLTP/OLAP example](../../../../examples/exposed-clickhouse-oltp-olap/README.md). The [OLTP vs OLAP guide](../guides/oltp-vs-olap.md) explains when to keep transactional writes elsewhere.

## Limitations {#limitations}

The wrapper makes Exposed callable; it cannot add transactions, savepoints, relational constraints, or rollback to ClickHouse. Only the engine/type/function surface covered in release 1.11 should be treated as supported.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### ClickHouse Exposed integration architecture

[![ClickHouse Exposed integration architecture](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-diagram-01.svg)

_Release README: [`exposed/clickhouse/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/clickhouse/README.md)_

### ClickHouse DDL lifecycle

[![ClickHouse DDL lifecycle](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-flow-02.svg)

_Release README: [`exposed/clickhouse/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/clickhouse/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`ClickHouseDatabase`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabase.kt)
- [`ClickHouseTable`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseTable.kt)
- [`ClickHouseUnsupported`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseUnsupported.kt)
- [Database release test](../../../../exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseTest.kt)
