# Module exposed-trino

English | [한국어](./README.ko.md)

A module that integrates JetBrains Exposed ORM with Trino JDBC. Built on PostgreSQL Dialect, it enables using the Exposed DSL with Trino and provides coroutine-based suspend transactions and Flow queries.

## Overview

`exposed-trino` provides:

- **TrinoDialect**: Extends
  `PostgreSQLDialect` for Exposed ORM compatibility with Trino (disables ALTER COLUMN TYPE / multiple generated keys)
- **TrinoDialectMetadata**: Bypasses unsupported `getImportedKeys` (FK constraint caching no-op)
- **TrinoConnectionWrapper**: Compatibility wrapper for Trino JDBC
  `prepareStatement` overloads; forces the underlying JDBC connection to `autoCommit=true`
- **TrinoDatabase**: Connection factory based on JDBC URL or host/port/catalog/schema (`object`)
- **suspendTransaction**: Wraps blocking JDBC calls in a suspend function using `Dispatchers.IO`
- **queryFlow**: Materializes results inside a transaction and emits them as a `Flow<T>`
- **trinoBatchInsert**: Bounded chunk wrapper around Exposed `batchInsert` for connector-dependent Trino writes
- **TrinoTable**: Base table class that strips unsupported PRIMARY KEY / NULL syntax from Trino DDL
- **@TrinoUnsupported**: Marker annotation for Trino-unsupported features

## Dependency

```kotlin
dependencies {
    implementation(project(":exposed-trino"))
    // or Maven coordinates
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-trino:${version}")
}
```

## Basic Usage

### 1. Connecting to Trino

```kotlin
import io.bluetape4k.exposed.trino.TrinoDatabase

// Connect using host/port/catalog/schema
val db = TrinoDatabase.connect(
    host = "trino-coordinator",
    port = 8080,
    catalog = "hive",
    schema = "default",
    user = "analyst",
)

// Or specify a JDBC URL directly
val db = TrinoDatabase.connect(
    jdbcUrl = "jdbc:trino://localhost:8080/memory/default",
    user = "trino",
)

// Or connect via a DataSource (e.g., HikariCP connection pool)
val hikariConfig = HikariConfig().apply {
    jdbcUrl = "jdbc:trino://trino-coordinator:8080/hive/default"
    username = "analyst"
    driverClassName = "io.trino.jdbc.TrinoDriver"
    maximumPoolSize = 10
}
val db = TrinoDatabase.connect(HikariDataSource(hikariConfig))
```

> **Note:** `connect(dataSource)` wraps each connection obtained from the pool in
> `TrinoConnectionWrapper`, enforcing `autoCommit=true`. If wrapping fails the raw
> connection is closed automatically to prevent leaks.

### 2. Synchronous Transaction

```kotlin
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

transaction(db) {
    SchemaUtils.create(Events)
    Events.insert {
        it[eventId] = 1L
        it[region] = "kr"
    }
    val rows = Events.selectAll().toList()
}
```

> When generating DDL from Exposed, prefer extending `TrinoTable` over the standard `Table`.
> The Trino Memory connector does not support PRIMARY KEY / CONSTRAINT syntax, so using a plain `Table`'s DDL may fail.

### 3. Suspend Transaction

```kotlin
import io.bluetape4k.exposed.trino.suspendTransaction

val rows = suspendTransaction(db) {
    Events.selectAll().where { Events.region eq "kr" }.toList()
}
```

Using a Virtual Thread dispatcher:

```kotlin
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

val vtDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
val rows = suspendTransaction(db, vtDispatcher) {
    Events.selectAll().toList()
}
```

### 4. Flow Query

```kotlin
import io.bluetape4k.exposed.trino.queryFlow

queryFlow(db) {
    Events.selectAll().where { Events.region eq "kr" }
}.collect { row ->
    println(row[Events.eventId])
}
```

> To safely manage JDBC `ResultSet` lifetimes and Exposed transaction boundaries,
> `queryFlow` materializes results into a `List` inside the transaction before emitting.
> The API surface is `Flow`, but it is not a true row-by-row streaming cursor.
> For very large result sets, consider a separate pagination or dedicated batch strategy.

### 5. Paged Flow Query

Use `pagedQueryFlow` for large result sets that should not be materialized in a
single transaction. Each page is loaded inside its own Exposed transaction and
then emitted after the transaction is closed.

```kotlin
import io.bluetape4k.exposed.trino.TrinoPagedQueryOptions
import io.bluetape4k.exposed.trino.pagedQueryFlow
import org.jetbrains.exposed.v1.core.SortOrder

pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 500)) { limit, offset ->
    Events.selectAll()
        .where { Events.region eq "kr" }
        .orderBy(Events.eventId to SortOrder.ASC)
        .limit(limit)
        .offset(offset)
}.collect { row ->
    println(row[Events.eventId])
}
```

Large result set guidance:

- `queryFlow` keeps the existing safe materialize-then-emit behavior.
- `pagedQueryFlow` is the preferred API for large JDBC result sets.
- Always use a deterministic `orderBy` with `limit` and `offset`.
- The block must return at most the provided `limit` rows.
- `pageSize` bounds application-side materialization. Trino JDBC throughput
  tuning remains a driver/cluster protocol concern, including Trino's spooling
  protocol for high-volume result transfer.
- Cancellation stops before the next page request; the in-flight page
  transaction is closed before collection continues or fails.
- True row-by-row cursor streaming is intentionally not exposed because it
  would couple the `ResultSet` lifetime to Flow collection outside the
  transaction boundary.

### 6. Batch Write Helper

Use `trinoBatchInsert` when the target Trino catalog supports `INSERT` and you
want explicit client-side chunking around Exposed `batchInsert`.

```kotlin
import io.bluetape4k.exposed.trino.TrinoBatchInsertOptions
import io.bluetape4k.exposed.trino.trinoBatchInsert

transaction(db) {
    Events.trinoBatchInsert(events, TrinoBatchInsertOptions(chunkSize = 500)) { event ->
        this[Events.eventId] = event.id
        this[Events.eventName] = event.name
        this[Events.region] = event.region
    }
}
```

Batch write guidance:

- Trino supports `INSERT INTO ... query` and multi-row `VALUES` syntax, but
  actual write support is connector-specific.
- `trinoBatchInsert` is a bounded wrapper over Exposed JDBC `batchInsert`; it is
  not a Trino connector bulk-loader protocol.
- `shouldReturnGeneratedValues` defaults to `false` because generated keys are
  not a reliable Trino write contract.
- If a later chunk fails, earlier chunks may already be visible. This module
  does not claim rollback or all-or-nothing semantics for Trino writes.
- Connector-side write tuning, such as JDBC connector `write.batch-size`
  catalog properties, remains a Trino catalog configuration concern.

## ⚠️ Transaction Behavior Warning

Trino does not support ACID transactions. While
`transaction {}` blocks can be used, be sure to understand the behavioral differences in the table below.

| Behavior           | Trino                          | Standard RDBMS        |
|--------------------|--------------------------------|-----------------------|
| Atomicity          | ❌ Not guaranteed               | ✅ Guaranteed          |
| Rollback           | ❌ no-op                        | ✅ Works               |
| Nested transaction | ⚠️ Calls allowed, no atomicity | ✅ Supported           |
| Savepoint          | ❌ Not supported                | ✅ Supported           |
| Autocommit mode    | Always ON (cannot be changed)  | Can be toggled ON/OFF |

**Practical impact**:

- If a failure occurs mid-way through multiple DML operations in a
  `transaction {}` block, previously executed DML statements are **not rolled back**.
- Write blocks always carry the risk of partial writes.
- Read-only queries (`SELECT`) are generally safe to use.

## Supported / Unsupported Features

### General Trino Contract

| Feature                        | Supported              | Notes                                                                         |
|--------------------------------|------------------------|-------------------------------------------------------------------------------|
| SELECT / JOIN / Aggregation    | ✅                      | Standard SQL                                                                  |
| INSERT / batch INSERT          | ⚠️ Connector-dependent | `trinoBatchInsert` is verified against Memory; actual support depends on the connector |
| UPDATE / DELETE                | ⚠️ Connector-dependent | This module provides the Exposed DSL; actual support depends on the connector |
| CREATE TABLE / DROP TABLE      | ⚠️ Connector-dependent | Tests verified against the Memory connector                                   |
| DDL via SchemaUtils            | ⚠️ Connector-dependent | Prefer `TrinoTable`                                                           |
| Window functions (GROUPS mode) | ✅                      | `supportsWindowFrameGroupsMode = true`                                        |
| Transaction atomicity          | ❌                      | Autocommit only                                                               |
| Rollback                       | ❌                      | no-op                                                                         |
| Savepoint                      | ❌                      | Not supported                                                                 |
| ALTER COLUMN TYPE              | ❌                      | `supportsColumnTypeChange = false`                                            |
| Multiple generated keys        | ❌                      | `supportsMultipleGeneratedKeys = false`                                       |
| FK constraint metadata lookup  | ❌                      | `getImportedKeys` not supported → no-op                                       |

### Memory Connector Test Coverage (test environment only)

Features verified in a Trino Memory connector environment via Testcontainers.

| Feature                              | Verified | Notes                                |
|--------------------------------------|----------|--------------------------------------|
| CREATE/DROP TABLE                    | ✅        | Memory connector                     |
| Single/batch INSERT                  | ✅        | Memory connector                     |
| trinoBatchInsert                     | ✅        | Chunked Exposed batchInsert wrapper  |
| SELECT / WHERE / ORDER BY            | ✅        |                                      |
| COUNT / Aggregation functions        | ✅        |                                      |
| suspendTransaction                   | ✅        | Dispatchers.IO                       |
| queryFlow                            | ✅        | Materialized before emit             |
| pagedQueryFlow                       | ✅        | Page materialized before emit        |
| TrinoConnectionWrapper compatibility | ✅        | prepareStatement overloads           |
| Automatic JDBC driver registration   | ✅        | init{} block on TrinoDatabase access |

## Core API Diagram

```mermaid
classDiagram
    direction LR
    class TrinoDatabase {
        <<factory>>
        +DRIVER: String
        +connect(host, port, catalog, schema, user): Database
        +connect(jdbcUrl, user): Database
    }
    class TrinoExtensions {
        <<extensionFunctions>>
        +suspendTransaction~T~(db, dispatcher, block): T
        +queryFlow~T~(db, dispatcher, block): Flow~T~
        +trinoBatchInsert~E~(data, options, body): List~ResultRow~
    }
    class TrinoConnectionWrapper {
        -conn: Connection
        +getAutoCommit(): Boolean
        +setAutoCommit(autoCommit): Unit
        +commit(): Unit
        +rollback(): Unit
        +prepareStatement(sql, autoGeneratedKeys): PreparedStatement
        +prepareStatement(sql, columnIndexes): PreparedStatement
        +prepareStatement(sql, columnNames): PreparedStatement
    }
    class TrinoDialect {
        +dialectName: String
        +supportsColumnTypeChange: Boolean
        +supportsMultipleGeneratedKeys: Boolean
        +supportsWindowFrameGroupsMode: Boolean
    }
    class TrinoDialectMetadata {
        +fillConstraintCacheForTables(tables): Unit
    }

    TrinoDialect --|> PostgreSQLDialect
    TrinoDialectMetadata --|> PostgreSQLDialectMetadata
    TrinoDatabase ..> TrinoConnectionWrapper : creates
    TrinoConnectionWrapper ..|> Connection

    style TrinoDatabase fill:#FFF3E0,stroke:#FFCC80,color:#E65100
    style TrinoExtensions fill:#F3E5F5,stroke:#CE93D8,color:#6A1B9A
    style TrinoConnectionWrapper fill:#FCE4EC,stroke:#F48FB1,color:#AD1457
    style TrinoDialect fill:#E0F2F1,stroke:#80CBC4,color:#00695C
    style TrinoDialectMetadata fill:#E0F2F1,stroke:#80CBC4,color:#00695C
```

### Distributed Query Flow

```mermaid
sequenceDiagram
        participant App as Kotlin Code
        participant DSL as Exposed DSL
        participant TD as TrinoDialect
        participant TC as TrinoConnectionWrapper
        participant COORD as Trino Coordinator
        participant WORKER as Trino Workers

    App->>DSL: Table.selectAll().where { ... }
    DSL->>TD: Generate SQL
    TD-->>DSL: SQL string (autocommit)
    DSL->>TC: JDBC execute
    TC->>COORD: Submit query
    COORD->>WORKER: Distribute to workers
    WORKER-->>COORD: Partial results
    COORD-->>TC: ResultSet
    TC-->>App: List<ResultRow> / Flow<T>
```

## Key Files / Classes

| File                              | Description                                                          |
|-----------------------------------|----------------------------------------------------------------------|
| `TrinoDatabase.kt`                | Connection factory (host/port/catalog or JDBC URL)                   |
| `TrinoConnectionWrapper.kt`       | Trino JDBC-compatible Connection wrapper (forces autocommit=true)    |
| `TrinoExtensions.kt`              | `suspendTransaction` and `queryFlow` extension functions             |
| `TrinoTable.kt`                   | Strips unsupported DDL syntax (PRIMARY KEY, explicit NULL) for Trino |
| `TrinoUnsupported.kt`             | Marker annotation for Trino-unsupported features                     |
| `dialect/TrinoDialect.kt`         | Trino dialect extending PostgreSQLDialect                            |
| `dialect/TrinoDialectMetadata.kt` | FK constraint caching no-op implementation                           |

## Testing

```bash
./gradlew :exposed-trino:test
```

Core regression test examples:

```bash
./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoConnectionWrapperTest"
./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoDatabaseTest"
./gradlew :exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoTransactionAtomicityTest"
```

## Phase 2 Roadmap

The following features are planned for future releases.

| Feature                   | Description                                                                   |
|---------------------------|-------------------------------------------------------------------------------|
| `exposed-bigquery-trino`  | Integrated pipeline module: BigQuery → Trino → Exposed                        |
| Connector-specific bulk loaders | Dedicated non-Exposed bulk write protocols for connectors that expose them |
| Result set streaming      | True row-by-row cursor streaming is deferred until a safe cursor contract exists |

## References

- [Trino](https://trino.io/)
- [Trino JDBC Driver](https://trino.io/docs/current/client/jdbc.html)
- [Trino INSERT syntax](https://trino.io/docs/current/sql/insert.html)
- [Trino SQL statement support](https://trino.io/docs/current/language/sql-support.html)
- [JetBrains Exposed](https://github.com/JetBrains/Exposed)
- [exposed-duckdb](../exposed-duckdb/README.md) — Similar in-process analytics DB integration reference
