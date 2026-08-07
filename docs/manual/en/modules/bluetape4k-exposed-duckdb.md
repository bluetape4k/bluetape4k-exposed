---
manualId: "bluetape4k-exposed-duckdb"
id: "bluetape4k-exposed-duckdb"
title: "Exposed DuckDB Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-duckdb"
sourceDir: "exposed/duckdb"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-duckdb
---

# Exposed DuckDB Adapter

`bluetape4k-exposed-duckdb` connects the Exposed JDBC DSL to embedded DuckDB. It registers a PostgreSQL-derived dialect and compensates for generated-key `prepareStatement` overloads that DuckDB JDBC does not implement.

## Problem {#problem}

DuckDB is useful for local analytical SQL, but its embedded connection lifecycle and JDBC metadata surface differ from a server RDBMS. This adapter keeps those differences at the module boundary instead of scattering driver workarounds through repositories.

## When to use it {#when-to-use}

Use it for in-process analytics, local files, repeatable data preparation, or tests that benefit from DuckDB SQL. Do not treat it as a drop-in replacement for a shared transactional service.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-duckdb")
}
```

## Core concepts {#concepts}

- `DuckDBDatabase.inMemory()` creates a database private to each new connection.
- `DuckDBDatabase.file(path)` and `readOnly(path)` open persistent or read-only files.
- `DuckDBDialect` reuses PostgreSQL SQL generation; `DuckDBDialectMetadata` skips unsupported imported-key metadata.
- `queryFlow` materializes rows inside the transaction before emitting them; it is not row-by-row streaming.

## Quick start {#quick-start}

```kotlin
val db = DuckDBDatabase.file("/tmp/events.duckdb")
transaction(db) {
    SchemaUtils.create(Events)
    Events.insert { it[id] = 1L; it[region] = "kr" }
    val rows = Events.selectAll().limit(100).toList()
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Ephemeral database | `DuckDBDatabase.inMemory()` |
| Shared state across transactions | `DuckDBDatabase.file(path)` |
| Read-only analysis | `DuckDBDatabase.readOnly(path)` |
| Blocking JDBC on coroutines | `suspendTransaction(db, dispatcher)` |
| Flow consumption | `queryFlow(db) { ... }` |

## Recommended patterns {#patterns}

Use a file database or a single-connection pool when multiple transactions must observe the same state. Page or aggregate large datasets in SQL; `queryFlow` first loads the complete `Iterable` into memory. Supply `Dispatchers.IO` or a virtual-thread dispatcher for blocking JDBC.

## Integrations {#integrations}

The DuckDB JDBC driver is an API dependency and no external service is required. Tests run directly against embedded DuckDB rather than Testcontainers. Exposed `SchemaUtils`, inserts, filters, grouping, ordering, and `limit` are covered by the release tests.

## Configuration {#configuration}

Java 25+ test execution needs `--enable-native-access=ALL-UNNAMED` because the driver loads native code. Treat the database file as application data: choose its location, permissions, backup, and cleanup policy explicitly.

## Failure modes {#failures}

- Separate in-memory connections see separate databases.
- Generated-key overload behavior is adapted, not made equivalent to every server driver.
- Imported-key discovery is unavailable, so do not use metadata inspection as proof that foreign keys exist.
- A large `queryFlow` can exhaust memory before its first item is emitted.

## Operations {#operations}

Observe file size, query duration, memory pressure, and concurrent access. Keep read-only consumers on `readOnly(path)` and close application-owned pools or connections during shutdown.

## Testing {#testing}

Prefer an isolated temporary file when a test spans connections; use `inMemory()` for a single connection scope. Test the generated SQL and the real DuckDB result, especially for PostgreSQL-derived syntax and nullable/time columns.

## Workshops and learning path {#workshops}

Start with the [database adapter matrix](../guides/database-adapter-matrix.md), then compare the workload in [OLTP vs OLAP](../guides/oltp-vs-olap.md). Move to a server adapter only when shared availability or database-managed concurrency becomes part of the requirement.

## Limitations {#limitations}

Release 1.11 does not provide row-streaming Flow, imported-key metadata, or a distributed service boundary. PostgreSQL dialect inheritance covers proven tests, not every PostgreSQL extension or DuckDB feature.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### DuckDB Exposed integration boundary

[![DuckDB Exposed integration boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-diagram-01.svg)

_Release README: [`exposed/duckdb/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/duckdb/README.md)_

### DuckDB query flow materialization

[![DuckDB query flow materialization](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-flow-02.svg)

_Release README: [`exposed/duckdb/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/duckdb/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`DuckDBDatabase`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/DuckDBDatabase.kt)
- [`DuckDBExtensions`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/DuckDBExtensions.kt)
- [`DuckDBDialectMetadata`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/dialect/DuckDBDialectMetadata.kt)
- [Database release test](../../../../exposed/duckdb/src/test/kotlin/io/bluetape4k/exposed/duckdb/DuckDBDatabaseTest.kt)
