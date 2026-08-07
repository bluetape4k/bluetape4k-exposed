---
manualId: "bluetape4k-exposed-cockroachdb"
id: "bluetape4k-exposed-cockroachdb"
title: "Exposed CockroachDB Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-cockroachdb"
sourceDir: "exposed/cockroachdb"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb
---

# Exposed CockroachDB Adapter

`bluetape4k-exposed-cockroachdb` connects Exposed through PostgreSQL JDBC and adds a CockroachDB-specific top-level transaction retry boundary for serialization failures.

## Problem {#problem}

CockroachDB is PostgreSQL-wire compatible, but distributed serializable transactions can return SQLSTATE `40001` and must restart the entire unit of work. PostgreSQL feature parity is also incomplete.

## When to use it {#when-to-use}

Use it for transactional workloads that need CockroachDB distribution and can make a whole transaction retry-safe. Do not choose it merely because an application already supports PostgreSQL.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb")
}
```

## Core concepts {#concepts}

`CockroachDatabase` accepts host/port/database, JDBC URL, or `DataSource`. `withCockroachTransaction` runs a top-level serializable Exposed transaction and retries only CockroachDB restart errors with bounded backoff. The compatibility ledger records verified, partial, and unsupported PostgreSQL features.

## Quick start {#quick-start}

```kotlin
val db = CockroachDatabase.connect(host = "localhost", database = "app")
val result = withCockroachTransaction(db) {
    Accounts.update({ Accounts.id eq id }) { it[balance] = newBalance }
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Connect | `CockroachDatabase.connect(...)` |
| Retry-safe unit | `withCockroachTransaction` |
| Retry tuning | `CockroachTransactionRetryOptions` |
| Classify SQLSTATE 40001 | `isCockroachRetryableTransactionError` |

## Recommended patterns {#patterns}

Call the helper outside any existing Exposed transaction and keep every side effect inside the retried block idempotent or deferred until commit. Use bounded keyset paging and batch sizes; a larger transaction increases contention and retry cost.

## Integrations {#integrations}

PostgreSQL JDBC is an API dependency. Release tests use Testcontainers CockroachDB and cover connection forms, schema create/drop, a compatibility matrix, retry classification, backoff, and whole-transaction restart.

## Configuration {#configuration}

Default port is `26257`, database `defaultdb`, and retry isolation is `SERIALIZABLE`. Configure TLS, credentials, pool lifecycle, maximum attempts, and backoff for the deployment environment.

## Failure modes {#failures}

Non-retryable SQL exceptions fail immediately; exhausted retry attempts propagate. External calls made inside the transaction may repeat. PostgreSQL advisory locks and range types are unsupported, and other features may be partial.

## Operations {#operations}

Measure retry count, SQLSTATE, contention, transaction duration, backoff, pool wait, and statement batch size. Alert on retry storms rather than hiding them behind a high attempt limit.

## Testing {#testing}

Use a CockroachDB container and force a retryable conflict. Assert the full block is repeated, non-retryable failures are not repeated, side effects remain safe, DDL matches the compatibility ledger, and paging order is stable.

## Workshops and learning path {#workshops}

Compare CockroachDB with PostgreSQL in the [adapter matrix](../guides/database-adapter-matrix.md), then read [OLTP vs OLAP](../guides/oltp-vs-olap.md). Add the retry helper only after the ordinary JDBC transaction boundary is understood.

## Limitations {#limitations}

PostgreSQL wire compatibility is not feature parity. The helper handles documented restart errors only; it cannot make external side effects transactional or remove contention.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### CockroachDB helper boundary

[![CockroachDB helper boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-diagram-01.svg)

_Release README: [`exposed/cockroachdb/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cockroachdb/README.md)_

### CockroachDB transaction retry flow

[![CockroachDB transaction retry flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-flow-02.svg)

_Release README: [`exposed/cockroachdb/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cockroachdb/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`CockroachDatabase`](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDatabase.kt)
- [`CockroachTransaction`](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachTransaction.kt)
- [Compatibility ledger](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDbCompatibility.kt)
- [Database release test](../../../../exposed/cockroachdb/src/test/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDatabaseTest.kt)
