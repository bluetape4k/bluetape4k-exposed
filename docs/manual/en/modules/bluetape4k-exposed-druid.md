---
manualId: "bluetape4k-exposed-druid"
id: "bluetape4k-exposed-druid"
title: "Exposed Druid JDBC Utilities"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-druid"
sourceDir: "exposed/druid"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-druid
---

# Exposed Druid JDBC Utilities

`bluetape4k-exposed-druid` supplies a deliberately query-only JDBC boundary for Apache Druid through the Calcite Avatica remote driver. It does not make Druid an Exposed transaction or DAO database.

## When to use it {#when-to-use}

Use this module when an application needs parameterized SQL queries and metadata reads from a Druid Avatica endpoint. Keep mutations, multi-step consistency, and schema ownership outside this adapter: the helper rejects non-query statements before it opens a connection.

## Coordinates {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-druid")
}
```

## Connection and query boundary {#connection-and-query-boundary}

`DruidConnectionOptions` builds the official Avatica Router URL, including the JSON or Protobuf serialization choice, context values, and authentication properties. `DruidJdbc.connection()` creates a JDBC connection from those options; `DruidJdbc.query()` accepts a query statement and binds parameters through `PreparedStatement`.

```kotlin
val options = DruidConnectionOptions(
    endpoint = "https://druid.example.com",
    context = mapOf("sqlTimeZone" to "UTC")
)
val rows = DruidJdbc.query(options, "SELECT datasource FROM INFORMATION_SCHEMA.TABLES") { statement ->
    statement.executeQuery().use { resultSet ->
        generateSequence { if (resultSet.next()) resultSet.getString(1) else null }.toList()
    }
}
```

## Recommended patterns {#patterns}

- Validate endpoint, query-only values, context, and authentication before opening a JDBC connection.
- Bind user-shaped values as parameters; never assemble literals into SQL text.
- Keep Druid reads at an application-owned boundary and use an OLTP database for transactional writes.
- Treat Avatica timeouts, transport failures, and result decoding as query failures, not as a transaction that can be rolled back.

## Testing {#testing}

The unit suite verifies official Avatica URL construction, Protobuf configuration, property propagation, early validation, parameterized metadata SQL, and rejection of non-query statements.

```bash
./gradlew :bluetape4k-exposed-druid:test --no-daemon
```

## Problem {#problem}

Druid is a remote, read-oriented SQL boundary. Treating its Avatica connection like an Exposed transaction would create misleading rollback and consistency expectations.

## Core concepts {#concepts}

The adapter separates endpoint construction, query validation, parameter binding, and result mapping. It never owns a Druid schema or multi-statement transaction.

## Quick start {#quick-start}

Create `DruidConnectionOptions`, call `DruidJdbc.query`, and map the `ResultSet` inside the callback. Keep the endpoint and context values application configuration.

## API by task {#api-by-task}

- Build a Router URL with `DruidConnectionOptions`.
- Open a connection with `DruidJdbc.connection` when a lower-level JDBC operation is required.
- Execute a parameterized read with `DruidJdbc.query`.

## Integrations {#integrations}

Use the helper from a service or repository boundary. Spring and Ktor applications should inject endpoint settings and expose query results through their own request and timeout policies.

## Configuration {#configuration}

Configure the Avatica endpoint, serialization mode, SQL context, authentication properties, and connection timeout. Do not put credentials in source-controlled URLs.

## Failure modes {#failures}

Reject non-query statements before connecting. Handle Avatica transport errors, timeout errors, malformed responses, and mapping failures as read failures with an application-level retry policy.

## Operations {#operations}

Log the logical query name, endpoint identity, duration, row count, and failure category without logging credentials or sensitive query parameters. Bound result size and request time.

## Workshops and learning path {#workshops}

Start with the query-only example, then inspect URL construction and parameter binding tests before integrating the adapter into a service boundary.

## Limitations {#limitations}

This module does not provide Exposed DAO support, distributed transactions, schema migrations, write statements, or a guarantee that a remote Druid query is idempotent.

## Sources {#sources}

- [`DruidConnectionOptions`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidConnectionOptions.kt)
- [`DruidJdbc`](../../../../exposed/druid/src/main/kotlin/io/bluetape4k/exposed/druid/DruidJdbc.kt)
- [`DruidJdbcTest`](../../../../exposed/druid/src/test/kotlin/io/bluetape4k/exposed/druid/DruidJdbcTest.kt)
