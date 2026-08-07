---
manualId: "bluetape4k-exposed-bigquery"
id: "bluetape4k-exposed-bigquery"
title: "Exposed BigQuery Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-bigquery"
sourceDir: "exposed/bigquery"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-bigquery
---

# Exposed BigQuery Adapter

`bluetape4k-exposed-bigquery` converts Exposed DSL statements to GoogleSQL and executes them through BigQuery REST query jobs. It is an SQL-generation and remote-execution bridge, not a JDBC transaction adapter.

## Problem {#problem}

Applications may want Exposed table and query definitions without installing a BigQuery JDBC driver. `BigQueryContext` uses an internal H2 PostgreSQL-mode database only to render SQL, then sends the translated statement to the BigQuery API.

## When to use it {#when-to-use}

Use it for BigQuery SELECT, basic INSERT/UPDATE/DELETE, dry runs, and controlled DDL where query-job semantics are acceptable. Use another persistence boundary when a unit of work requires JDBC atomicity or DAO behavior.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-bigquery")
}
```

## Core concepts {#concepts}

- `BigQueryContext` owns the REST client, project/dataset, SQL generator DB, and dispatcher.
- `Query.withBigQuery()` creates an executor for list, Flow, single-row, and dry-run tasks.
- `BigQueryQueryOptions` controls billed-byte limits, labels, priority, location, destination table, timeout, and cache use.
- REST calls are independent query jobs; there is no client-side rollback across them.

## Quick start {#quick-start}

```kotlin
val context = BigQueryContext.create(bigquery, "project", "analytics")
with(context) {
    val estimate = Events.selectAll().withBigQuery(
        BigQueryQueryOptions(maximumBytesBilled = 10_000_000)
    ).dryRun()
    val rows = Events.selectAll().limit(100).withBigQuery().toList()
}
```

## API by task {#api-by-task}

| Task | API |
| --- | --- |
| Exposed query | `query.withBigQuery().toList()` / `toFlow()` |
| Cost and syntax validation | `dryRun()` / `validateRawQuery()` |
| DML | `execInsert`, `execUpdate`, `execDelete` |
| DDL | `execCreateTable`, `execDropTable` |
| Raw GoogleSQL | `runRawQuery` |

## Recommended patterns {#patterns}

Dry-run every user-shaped or high-cost query and set `maximumBytesBilled`. Prefer `toFlow` for large results because it follows BigQuery page tokens page by page; `toList` collects all pages. Make each DML call independently retryable and do not model several calls as one transaction.

## Integrations {#integrations}

The public integration is `google-api-services-bigquery-v2`; H2 is an internal runtime SQL generator. Release tests can use a locally installed emulator or a Testcontainers BigQuery emulator. The runnable `examples-exposed-bigquery-dry-run` path demonstrates validation without paid execution.

## Configuration {#configuration}

Provide an authenticated `Bigquery` client and align project, dataset, location, and credentials. Set labels for cost attribution, a timeout, and a billed-byte cap. The default uses standard SQL and a 30-second request timeout when no override is supplied.

## Failure modes {#failures}

- A successful earlier query job is not rolled back when a later job fails.
- SQL accepted by H2 generation can still be rejected by BigQuery; dry run is the server-side check.
- `toList` may consume substantial heap across many result pages.
- SchemaUtils automation, sequences, generated keys, and `ALTER COLUMN TYPE` are limited.

## Operations {#operations}

Record job ID, labels, bytes processed/billed, slot time, cache use, location, and error details. Separate dry-run authorization failures from execution failures and preserve the generated SQL in diagnostics without logging sensitive literals.

## Testing {#testing}

Unit-test SQL generation and query options, then run API behavior against the emulator. Keep a small production-project smoke test for permissions and location differences. Test page tokens, cancellation between pages, dry-run limits, and conversion of decimal/timestamp/null values.

## Workshops and learning path {#workshops}

Begin with [the BigQuery dry-run example](../../../../examples/exposed-bigquery-dry-run/README.md), then use the [adapter matrix](../guides/database-adapter-matrix.md) and [OLTP vs OLAP guide](../guides/oltp-vs-olap.md) to decide whether BigQuery owns execution or is reached through Trino.

## Limitations {#limitations}

This module does not provide JDBC transaction semantics, full DAO compatibility, universal SchemaUtils DDL, or rollback across REST jobs. Join/alias result access and all schema translations should be verified against the actual query.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### BigQuery REST execution boundary diagram

[![BigQuery REST execution boundary diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-diagram-01.svg)

_Release README: [`exposed/bigquery/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bigquery/README.md)_

### BigQuery query job lifecycle flow diagram

[![BigQuery query job lifecycle flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-flow-02.svg)

_Release README: [`exposed/bigquery/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bigquery/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`BigQueryContext`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryContext.kt)
- [`BigQueryQueryExecutor`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryExecutor.kt)
- [`BigQueryQueryOptions`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryOptions.kt)
- [Context release test](../../../../exposed/bigquery/src/test/kotlin/io/bluetape4k/exposed/bigquery/BigQueryContextUnitTest.kt)
