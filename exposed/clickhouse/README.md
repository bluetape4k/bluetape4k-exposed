English | [한국어](./README.ko.md)

# exposed-clickhouse

Kotlin/Exposed dialect for ClickHouse JDBC. It keeps Exposed table/query syntax while adding ClickHouse engine clauses, column types, aggregate/date functions, and coroutine-friendly wrappers around blocking JDBC work.

## Architecture

![ClickHouse Exposed integration architecture](../../docs/images/readme-diagrams/exposed-clickhouse-diagram-01.png)

## Features

- **ClickHouseDatabase** — factory functions `connect(host, port, database)` and `connect(jdbcUrl)` for JDBC connection setup
- **ClickHouseTable** — abstract base class with `engine: ClickHouseEngine` parameter; handles DDL sanitization and ENGINE clause injection
- **MergeTree Engine DSL** — type-safe DSL for `mergeTree {}`, `replacingMergeTree {}`, `summingMergeTree {}`, `aggregatingMergeTree {}`, `Log`, `TinyLog`, `Memory`
- **Rich Column Types** — `String`, `FixedString(N)`, `Int8`–`Int64`, `UInt8`–`UInt64`, `Float32/64`, `DateTime64`, `Date32`, `LowCardinality(T)`, `Array(T)`, `Nullable(T)`
- **Date Functions** — `toYYYYMM()`, `dateDiff(unit, start, end)`, `toStartOfInterval()`
- **Aggregate Functions** — `argMax()`, `argMin()`, `quantile(level)()`, `uniq()`, `uniqExact()`
- **Coroutine Helpers** — `suspendTransaction {}` runs blocking JDBC work on a caller-selected dispatcher; `queryList {}` collects all results; `queryFlow(query = ..., mapper = ...)` streams mapped rows. The original `queryFlow {}` keeps its materializing behavior.

## Table option policy

Exposed `1.5.0` does not validate dialect compatibility of generic
`Table.options` or `storageParameters`. This policy applies to CREATE TABLE generation.

| Table / DB | options | storageParameters |
| --- | --- | --- |
| Native Table / H2 | Upstream behavior unchanged; empty-option DDL verified | Empty list verified |
| Native Table / PostgreSQL | `USING heap` rendering and execution verified | `FillFactorParameter(70)` and `AutovacuumEnabledParameter(false)` preservation and execution verified |
| StarRocksTable | Nonempty lists throw `IllegalArgumentException` | Nonempty lists throw `IllegalArgumentException` |
| ClickHouseTable | Nonempty lists throw `IllegalArgumentException` | Nonempty lists throw `IllegalArgumentException` |

Custom tables reject typed, raw, and user-defined options before generating SQL.
They do not invoke option `toSQL()` or infer safety by sanitizing its output.
Even an empty-string option is rejected. This is a behavior change for callers
that previously supplied raw options.

StarRocks retains its fixed `ENGINE=OLAP PROPERTIES ("replication_num" = "1")`.
ClickHouse retains `orderBy`, `partitionBy`, and `setting` through its existing
`engine` DSL. MySQL engine/charset and PostgreSQL WITH parameters are not
implicitly translated into these settings. There is currently no validated
generic-option allowlist, and no new raw SQL escape hatch is provided.

### Manual migration

The [Exposed Table option contract](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Table.kt)
states that option changes are not tracked by migration diffs. Compare the current
schema with the desired settings, write a separate DB-specific ALTER/recreation
migration, and verify data preservation and recovery on a test database before
applying it. Do not expect another `SchemaUtils.create` call or an automatic diff
to update existing table options.

## Quick Start

```kotlin
// 1. Connect to ClickHouse
val database = ClickHouseDatabase.connect(
    host = "localhost",
    port = 8123,
    database = "analytics"
)

// 2. Define a table
object EventsTable : ClickHouseTable("events") {
    val eventDate = date32("event_date")
    val userId    = chInt64("user_id")
    val eventType = lowCardinalityString("event_type")
    val value     = chFloat64("value")

    override val engine = mergeTree {
        orderBy(eventDate, userId)
        partitionBy(eventDate.toYYYYMM())
        setting("index_granularity", 8192)
    }
}

// 3. Create schema
transaction(database) {
    SchemaUtils.create(EventsTable)
}

// 4. Batch insert
transaction(database) {
    EventsTable.batchInsert(events) { e ->
        this[EventsTable.eventDate]  = e.date
        this[EventsTable.userId]     = e.userId
        this[EventsTable.eventType]  = e.type
        this[EventsTable.value]      = e.value
    }
}

// 5. Coroutine query (blocking JDBC on an IO dispatcher)
val results = suspendTransaction(database) {
    EventsTable
        .select(EventsTable.userId, EventsTable.value.sum())
        .groupBy(EventsTable.userId)
        .toList()
}
```

## Choosing List or Flow

```kotlin
val values = queryList(database) {
    EventsTable.selectAll().limit(100).map { it[EventsTable.value] }
}
queryFlow(database,
    query = { EventsTable.selectAll().limit(100_000) },
    mapper = { it[EventsTable.value] },
).take(10).collect { value -> process(value) }
```

`queryList` collects inside the transaction and uses memory proportional to the result. It follows the existing `suspendTransaction` participation/retry policy. The original `queryFlow(database) { iterable }` still collects everything before emitting and is not deprecated.

The new overload is cold: each collection creates an independent transaction, connection and Query. It does not inherit an outer transaction or connection-local tenant/security state. Put authorization predicates in the query and use the caller's appropriately secured Database. The mapper must be short, must not execute extra SQL, and must return detached values rather than lazy DAO/resource-backed values. Never share a mutable Query between collections.

The producer keeps at most one pending mapped item, in addition to the item being consumed. Driver buffers and downstream `buffer()` are outside this bound. No application-level query retry or row replay occurs; driver request retries are a separate configuration. Completion, failure and cancellation wait for internal resource cleanup. Cancellation does not interrupt a blocking JDBC call immediately: callers must configure finite connection-acquisition, socket and query timeouts. The caller owns the Database, pool and dispatcher; the helper never closes them. ClickHouse DML atomicity is not provided.

This helper logs lifecycle-only events, not SQL, bindings, rows or exception payloads. Exposed and driver logs have their own policies. Paging or `queryList` may still be more appropriate when the caller needs detached bulk results or short-lived connections.

### Driver timeout and row-limit behavior

The integration tests exercise `clickhouse-jdbc` `0.9.9` against ClickHouse Server
`26.7.3.19`. JDBC URL server settings use the `clickhouse_setting_` prefix (see
the [ClickHouse JDBC URL documentation](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/clickhouse-jdbc/README.md#jdbc-url)).
The catalog `ClickHouseDriver` (`com.clickhouse.jdbc.ClickHouseDriver`) defaults
to the V2 path; the test records the `ClickHouse` metadata and `0.9.9` driver
version, and unwraps the actual `com.clickhouse.jdbc.ConnectionImpl`. The V2
probe matrix separates connection attempts, server execution timeout, transport
socket timeout, and local/downstream cancellation. It does not turn an
observed request or cleanup into a remote query-cancellation guarantee.

- `clickhouse_setting_max_result_rows=2` with
  `clickhouse_setting_result_overflow_mode=throw` raises a JDBC/Exposed SQL
  exception (`TOO_MANY_ROWS_OR_BYTES`). `queryFlow` does not replay the query;
  `ResultSet`, `Statement`, and `Connection` are released before the next
  collection succeeds.
- `clickhouse_setting_result_overflow_mode=break` returns a partial result.
  The server may round the result up to a block boundary, so
  `clickhouse_setting_max_result_rows` is not an exact client-side truncation.
  The test fixes `clickhouse_setting_max_block_size=2` and observes the two-row
  prefix on every cold collection.
- A direct V2 `Statement#setQueryTimeout(1)` probe runs
  `SELECT sleepEachRow(1), number FROM system.numbers LIMIT 3` three times. Each
  attempt raises `SQLTimeoutException("Query execution time exceeded limit")`
  before a row is emitted; the `ResultSet`, `Statement`, and `Connection` are
  released and a follow-up `queryFlow` collection succeeds. This is the V2
  server-side `max_execution_time` contract for a directly configured JDBC
  statement. Hikari `connectionTimeout` only bounds pool acquisition; it is not
  a query deadline. `queryFlow` does not expose that statement handle, so callers
  must configure `Statement#setQueryTimeout` or
  `clickhouse_setting_max_execution_time` at their JDBC/DataSource boundary.
- A V2 `socket_timeout=200` probe with the same delayed-row shape completes all
  two rows on three attempts (about 2.0 seconds each) without a SQL exception.
  The transport read-timeout contract is therefore `N/A` for this probe
  condition/server/driver combination; do not infer a socket timeout from the
  URL alone.
- A V2 connection probe repeats a `DriverManager` attempt against a closed
  ephemeral loopback port with `connect_timeout=200&connection_timeout=200`.
  All three attempts fail with `SQLException` within five seconds. This is a
  bounded connection-refusal result, not proof that a timeout expired or that a
  ClickHouse handshake was interrupted. V2 exposes `connection_timeout` in its
  client property list; `connect_timeout` is retained in the probe because it is
  the issue's requested spelling and must not be documented as a V2 guarantee.
- Three `queryFlow(...).take(1)` collections release the local cursor and pool
  connection and each follow-up collection succeeds within the bounded test
  window. This proves local producer/ResultSet cleanup, not interruption of a
  blocking V2 JDBC read or termination of the remote query.
- Three direct V2 `Statement#cancel()` calls are accepted after a row and release
  local resources. In `clickhouse-jdbc` `0.9.9`, the V2 implementation issues
  `KILL QUERY` asynchronously (see the [driver source](https://github.com/ClickHouse/clickhouse-java/blob/v0.9.9/jdbc-v2/src/main/java/com/clickhouse/jdbc/StatementImpl.java)); a successful
  `cancel()` return is not proof that ClickHouse has finished terminating the
  remote query (see [KILL QUERY](https://clickhouse.com/docs/reference/statements/kill)).
- The V1 read-timeout test selects `com.clickhouse.jdbc.DriverV1` explicitly and
  sets `socket_timeout=200`. A one-second-per-row query raises the driver's
  `BatchUpdateException("Read timed out")`, wrapped by Exposed, before the
  mapper emits a row. The pool resources are returned and an immediate follow-up
  collection succeeds. This is an actual JDBC socket-read timeout, distinct from
  the server-side `clickhouse_setting_max_execution_time` query timeout; callers
  must configure finite connection, socket, and query timeouts for blocking JDBC
  cancellation on the selected driver and treat timeout/limit failures as
  terminal for that collection. The V1 result is not a V2 guarantee.

## Column Types

| ClickHouse Type | Kotlin Type | Builder |
|----------------|-------------|---------|
| String | String | `chString(name)` |
| FixedString(N) | String | `fixedString(name, n)` |
| Int8 | Byte | `chInt8(name)` |
| Int16 | Short | `chInt16(name)` |
| Int32 | Int | `chInt32(name)` |
| Int64 | Long | `chInt64(name)` |
| UInt8 | UByte | `chUByte(name)` |
| UInt16 | UShort | `chUShort(name)` |
| UInt32 | UInt | `chUInt(name)` |
| UInt64 | ULong | `chULong(name)` |
| UInt64 | BigInteger | `chUInt64BigInt(name)` |
| Float32 | Float | `chFloat32(name)` |
| Float64 | Double | `chFloat64(name)` |
| DateTime64(n) | Instant | `dateTime64(name, precision)` |
| Date32 | LocalDate | `date32(name)` |
| LowCardinality(T) | T | `lowCardinality(name, innerType)` / `lowCardinalityString(name)` |
| Array(T) | List\<T\> | `chArray(name, innerType)` |
| Nullable(T) | T? | `chNullable(name, innerType)` |

## Engine DSL

```kotlin
// MergeTree — typed expressions from a ClickHouseTable override
val engine1 = mergeTree {
    orderBy(EventsTable.eventDate, EventsTable.userId)
    partitionBy(EventsTable.eventDate.toYYYYMM())
    primaryKey(EventsTable.eventDate)
    setting("index_granularity", 8192)
    setting("storage_policy", "hot")
}

// ReplacingMergeTree — deduplication with version column
val engine2 = replacingMergeTree {
    orderBy(EventsTable.userId)
    versionColumn(EventsTable.eventDate)
}

// SummingMergeTree — pre-aggregation
val engine3 = summingMergeTree {
    orderBy(EventsTable.eventType, EventsTable.eventDate)
    sumColumns(EventsTable.value)
}

// AggregatingMergeTree — for materialized views
val engine4 = aggregatingMergeTree {
    orderBy(EventsTable.userId)
    partitionBy(EventsTable.eventDate.toYYYYMM())
}

// Use raw fragments only when ClickHouse grammar is not modeled by Exposed.
// These APIs reject statement delimiters, comments, quotes, and clause-boundary tokens.
val rawEngine = mergeTree {
    unsafeRawOrderBy("event_date", "user_id")
    unsafeRawPartitionBy("toYYYYMM(event_date)")
}

// Lightweight engines
val logEngine    = Log
val tinyLog      = TinyLog
val memoryEngine = Memory
```

## Date & Aggregate Functions

```kotlin
transaction(database) {
    // toYYYYMM — extract year-month integer
    EventsTable
        .select(toYYYYMM(EventsTable.eventDate).alias("month"), EventsTable.value.sum())
        .groupBy(toYYYYMM(EventsTable.eventDate))
        .toList()

    // dateDiff — difference between two dates
    val diff = dateDiff("day", EventsTable.eventDate, EventsTable.eventDate)

    // toStartOfInterval — floor to interval boundary
    val monthly = toStartOfInterval(EventsTable.eventDate, "1 MONTH")

    // argMax — value at maximum of another column
    val latestValue = argMax(EventsTable.value, EventsTable.eventDate)

    // quantile — approximate quantile
    val p95 = quantile(0.95)(EventsTable.value)

    // uniq — HyperLogLog cardinality estimate
    val approxUniq = uniq(EventsTable.userId)

    // uniqExact — exact cardinality
    val exactUniq = uniqExact(EventsTable.userId)
}
```

## DDL Lifecycle

![ClickHouse DDL lifecycle](../../docs/images/readme-diagrams/exposed-clickhouse-flow-02.png)

## Caveats

1. **No transaction atomicity** — `commit()` and `rollback()` are no-ops in `ClickHouseConnectionWrapper`. DML statements that execute before a failure are **not** rolled back. Design accordingly (idempotent inserts, deduplication via ReplacingMergeTree).

2. **`modifyColumn` not supported** — `alterTable { modifyColumn(...) }` returns an empty list; column type changes must be handled manually via native ClickHouse DDL.

3. **JDBC-only, no R2DBC** — this module is JDBC-based. R2DBC/reactive integration is not available.

4. **`LowCardinality` wrapping order** — ClickHouse does not support `Nullable(LowCardinality(T))`. Always use `LowCardinality(Nullable(T))` order.

5. **HikariCP configuration** — `autoCommit=true` is enforced. Set `minimumIdle=1` to avoid unnecessary connection churn.

6. **DDL constraint conversion** — Exposed-generated `CREATE TABLE` removes primary-key constraints, inline `REFERENCES`, and column nullability constraints. Quoted literals/identifiers, comments, `Nullable(T)`, and `DEFAULT` expressions are preserved. Use `ORDER BY` in the engine DSL for the physical sort key. Table-level foreign-key constraints are not supported; do not declare foreign keys on ClickHouse tables. This is a bounded Exposed DDL adapter, not a general parser for handwritten SQL or other dialects.

7. **Column comments stripped** — `COMMENT ON COLUMN` statements are removed by the DDL filter and have no effect.

## License

MIT License — see [LICENSE](../../LICENSE) for details.
