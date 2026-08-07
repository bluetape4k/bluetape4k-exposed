---
manualId: "bluetape4k-exposed-trino"
id: "bluetape4k-exposed-trino"
title: "Exposed Trino Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-trino"
sourceDir: "exposed/trino"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-trino
---

# Exposed Trino Adapter

`bluetape4k-exposed-trino` connects the Exposed JDBC DSL to a Trino coordinator and preserves Trino's catalog/schema and autocommit boundaries.

## Problem {#problem}

Trino federates connectors rather than behaving like one transactional database. This adapter registers its JDBC dialect, validates connection options, removes unsupported primary-key DDL, and exposes coroutine bridges without pretending that a client block is atomic.

## When to use it {#when-to-use}

Use it to issue Exposed-built queries through Trino across catalogs. Treat supported DML and DDL as connector-specific capabilities, not guarantees of the coordinator.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-trino")
}
```

## Core concepts {#concepts}

- `TrinoDatabase.connect` accepts host/port/catalog/schema, JDBC URL, or `DataSource`.
- `TrinoConnectionOptions` owns typed JDBC properties.
- `TrinoConnectionWrapper` keeps autocommit on; commit/rollback are no-ops.
- `TrinoTable` removes primary-key and explicit nullable DDL while preserving `NOT NULL`.

## Quick start {#quick-start}

```kotlin
val db = TrinoDatabase.connect(
    host = "localhost", port = 8080,
    catalog = "memory", schema = "default", user = "analyst",
)
transaction(db) { Events.selectAll().limit(100).toList() }
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Coordinator connection | `TrinoDatabase.connect(...)` |
| Pooled connection | `TrinoDatabase.connect(dataSource)` |
| Compatible simple DDL | extend `TrinoTable` |
| Blocking JDBC on coroutine dispatcher | `suspendTransaction` |
| Flow consumer API | `queryFlow` |

## Recommended patterns {#patterns}

Design queries around a catalog's pushdown behavior and inspect Trino query plans. Keep DML statements independent because a failure cannot roll back earlier work. Page in SQL; the adapter's Flow bridge materializes rows before emitting them.

## Integrations {#integrations}

The Trino JDBC driver is an API dependency. Release tests use Testcontainers Trino with the memory connector and cover sanitized DDL, selects, inserts, validation, `DataSource`, and the lack of transaction atomicity.

## Configuration {#configuration}

The URL shape is `jdbc:trino://host:port/catalog/schema`. Configure credentials, SSL, roles, and session properties through `TrinoConnectionOptions` only after confirming coordinator policy. The production data source and its lifecycle remain application-owned.

## Failure modes {#failures}

- `transaction {}` is a compatibility boundary, not an atomic unit.
- Primary/unique/foreign-key behavior and DDL support vary by connector.
- A query may scan more remote data than expected when predicate pushdown is unavailable.
- Flow materialization can consume substantial heap for large results.

## Operations {#operations}

Track queued/running time, scanned bytes, remote read volume, retries, and coordinator errors. Set query limits and resource groups outside this adapter; cancel requests at both coroutine and Trino query levels where the application requires prompt termination.

## Testing {#testing}

Use a Trino container to test adapter mechanics, then add connector-specific integration tests for the production catalog. Do not infer BigQuery, Hive, or Iceberg behavior solely from the memory connector.

## Workshops and learning path {#workshops}

Compare the adapter in the [database matrix](../guides/database-adapter-matrix.md) and read [OLTP vs OLAP](../guides/oltp-vs-olap.md). Validate one representative query and one unsupported write against the actual production connector before broad adoption.

## Limitations {#limitations}

Release 1.11 does not supply transactional atomicity, universal connector DDL, or true row-streaming Flow. The `TrinoUnsupported` annotation records known gaps but cannot discover connector capabilities at compile time.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Trino JDBC compatibility boundary diagram

[![Trino JDBC compatibility boundary diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-diagram-01.svg)

_Release README: [`exposed/trino/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/trino/README.md)_

### Trino Flow materialization contract diagram

[![Trino Flow materialization contract diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-flow-02.svg)

_Release README: [`exposed/trino/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/trino/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`TrinoDatabase`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoDatabase.kt)
- [`TrinoTable`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoTable.kt)
- [`TrinoConnectionWrapper`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoConnectionWrapper.kt)
- [Database release test](../../../../exposed/trino/src/test/kotlin/io/bluetape4k/exposed/trino/TrinoDatabaseTest.kt)
