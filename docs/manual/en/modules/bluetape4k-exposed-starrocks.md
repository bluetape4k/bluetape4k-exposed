---
manualId: "bluetape4k-exposed-starrocks"
id: "bluetape4k-exposed-starrocks"
title: "Exposed StarRocks Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-starrocks"
sourceDir: "exposed/starrocks"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks
---

# Exposed StarRocks Adapter

`bluetape4k-exposed-starrocks` is a deliberately narrow Exposed JDBC adapter for StarRocks Connector/J. Release 1.11 proves local all-in-one connection, simple OLAP table DDL, insert, and select paths; it does not claim broad MySQL parity.

## Problem {#problem}

StarRocks speaks a MySQL-compatible wire protocol but has its own OLAP DDL and durability model. The adapter registers the driver/dialect, keeps the connection in autocommit, and provides a conservative table base for smoke-tested schemas.

## When to use it {#when-to-use}

Use it when the proven 1.11 surface matches a small StarRocks integration and you are prepared to validate each additional feature. Prefer native SQL or a dedicated ingestion path beyond that boundary.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks")
}
```

## Core concepts {#concepts}

- `StarRocksDatabase` accepts host/FE query port/catalog/database, a JDBC URL, or `DataSource`.
- `StarRocksConnectionWrapper` forces autocommit; commit/rollback are no-ops.
- `StarRocksTable` removes relational primary-key syntax and appends `ENGINE=OLAP` with single-replica fixture properties.
- `StarRocksDialect` disables unproven schema mutation and generated-key features.

## Quick start {#quick-start}

```kotlin
val db = StarRocksDatabase.connect(
    host = "localhost", port = 9030,
    catalog = "default_catalog", database = "analytics", user = "root",
)
transaction(db) { Events.selectAll().limit(100).toList() }
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Direct connection | `StarRocksDatabase.connect(...)` |
| Application pool | `StarRocksDatabase.connect(dataSource)` |
| Extra driver properties | `StarRocksConnectionOptions` |
| Narrow fixture DDL | extend `StarRocksTable` |
| Query/write | Exposed JDBC DSL within independent statements |

## Recommended patterns {#patterns}

Treat the module as an opt-in compatibility layer. Review generated DDL, replace fixture replication settings for production, and make writes idempotent. Use database-side paging and aggregation; do not infer generic batch semantics from the single-row smoke path.

## Integrations {#integrations}

The module exposes StarRocks Connector/J. Release tests start `starrocks/allin1-ubuntu` with Testcontainers and prove metadata, connection validation, table creation, insert, and select. That local image is evidence for the smoke path, not every production topology.

## Configuration {#configuration}

The URL is `jdbc:starrocks://host:port/catalog.database`, normally using FE query port `9030`. The caller owns pool lifecycle. `extraProperties` is an escape hatch and every key/value must be nonblank.

## Failure modes {#failures}

- Rollback is not a durability guarantee; statements execute independently.
- `ALTER COLUMN TYPE`, sequences, multiple generated keys, and several reference actions are disabled.
- The default `replication_num=1` emitted by `StarRocksTable` is for local fixtures.
- MySQL wire compatibility does not prove MySQL DDL or transaction equivalence.

## Operations {#operations}

Observe load errors, query latency, tablet/replica health, FE availability, and rejected rows. Keep ingestion retry and deduplication policy outside the JDBC wrapper and validate production DDL through the StarRocks schema tooling you operate.

## Testing {#testing}

Keep the all-in-one container test for adapter regression and add environment-gated tests against the production-like topology. Assert the final DDL string, not only that `SchemaUtils.create` returns.

## Workshops and learning path {#workshops}

Read the [database adapter matrix](../guides/database-adapter-matrix.md) and [OLTP vs OLAP guide](../guides/oltp-vs-olap.md). Expand from connection → DDL → one insert → one read, adding evidence before relying on any new feature.

## Limitations {#limitations}

Release 1.11 proves a narrow local AIO smoke scope. It does not promise full Connector/J, MySQL dialect, distributed transaction, batch ingestion, paging, or production DDL parity.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### StarRocks local OLAP integration boundary diagram

[![StarRocks local OLAP integration boundary diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-diagram-01.svg)

_Release README: [`exposed/starrocks/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/starrocks/README.md)_

### StarRocks local smoke lifecycle diagram

[![StarRocks local smoke lifecycle diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-starrocks-flow-02.svg)

_Release README: [`exposed/starrocks/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/starrocks/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`StarRocksDatabase`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/StarRocksDatabase.kt)
- [`StarRocksTable`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/StarRocksTable.kt)
- [`StarRocksDialect`](../../../../exposed/starrocks/src/main/kotlin/io/bluetape4k/exposed/starrocks/dialect/StarRocksDialect.kt)
- [Database release test](../../../../exposed/starrocks/src/test/kotlin/io/bluetape4k/exposed/starrocks/StarRocksDatabaseTest.kt)
