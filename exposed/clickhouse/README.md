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

### ClickHouse JDBC V2 connection options

`ClickHouseV2Options` is an immutable `AbstractValueObject`. It validates the
ClickHouse JDBC V2 property boundary before a connection is opened and keeps
collection inputs defensively copied. The options overload is separate from the
existing `connect` overloads, so existing source and JVM descriptors remain
available.

```kotlin
val options = ClickHouseV2Options(
    connectionTimeoutMillis = 1_500,
    socketOperationTimeoutMillis = 2_000,
    connectionPoolEnabled = true,
    maxOpenConnections = 8,
    clientName = "analytics-api",
    customHeaders = mapOf("X-ClickHouse-User-Agent" to "bluetape/analytics"),
)

val database = ClickHouseDatabase.connect(
    host = "localhost",
    port = 8123,
    database = "analytics",
    user = "default",
    password = "",
    options = options,
)
```

The typed fields map to V2 properties such as `connection_timeout`,
`socket_timeout`, `connection_request_timeout`, `connection_ttl`,
`http_keep_alive_timeout`, compression/retry settings, `query_id`, and
`clickhouse_setting_<name>`. Timeout values use milliseconds; `0` is accepted
only where the driver defines it as its default, while pool limits and buffer
sizes must be positive.

`authentication` is a one-of value: `Basic`, `AccessToken`, or `BearerToken`.
Basic authentication uses the `user`/`password` arguments. Token modes require
the placeholder `user = "default"` and `password = ""`; they emit only the
token property and `http_use_basic_auth=false`. Do not put credentials or token
values in `rawProperties` or the JDBC URL query. Unknown raw keys, the
RowBinary beta key, and duplicate typed/raw keys fail fast. Raw server settings
are limited to `clickhouse_setting_<name>`, and custom headers are limited to
`X-ClickHouse-User-Agent`; header values are never logged.

TLS accepts file or secret-store references, not certificate/key material.
`ClickHouseV2SecretProvider` supplies a password just for property conversion;
the returned `CharArray` is cleared immediately afterwards. `toString()` and
connection failures redact passwords, tokens, secret values, and JDBC URL
query values. A JDBC URL query has the driver's highest precedence for
non-authentication properties; authentication keys in an options URL are
rejected so they cannot bypass the selected authentication mode.

### ClickHouse JDBC V2 RowBinary batch writer

`ClickHouseRowBinaryExecutor` is an explicit opt-in batch writer for the
ClickHouse JDBC V2 `RowBinaryWithDefaults` path. The caller owns a
`ClickHouseConnectionProvider`; it opens a connection with
`beta.row_binary_for_simple_insert=true` for the RowBinary profile and
`false` for the JDBC fallback profile. The property is connection-scoped and
is deliberately not part of `ClickHouseV2Options`.

```kotlin
val writer = ClickHouseRowBinaryExecutor(
    provider = ClickHouseConnectionProvider { rowBinaryEnabled ->
        openConnection(rowBinaryEnabled) // caller applies the V2 profile property
    },
    options = ClickHouseRowBinaryOptions(
        enabled = true,
        maxRowsPerFlush = 1_024,
    ),
)

val result = writer.executeBatch(
    sql = "INSERT INTO events (id, label) VALUES (?, ?)",
    rows = events.asSequence().map { event -> listOf(event.id, event.label) },
)
```

Only one simple `INSERT ... VALUES (...)` group is eligible. Placeholders and
`DEFAULT` are delegated to the driver; `INSERT ... SELECT`, multiple value
groups, nested value expressions/functions, unsupported setters, a missing
provider, or unknown driver capability select the disabled JDBC profile before
the first byte. The executor never retries or resends a chunk after a setter
has run or the first byte has been written. A setter/first-byte failure is
terminal for that executor instance and the original exception is preserved.

`ClickHouseRowBinaryResult.updateCounts` keeps driver sentinel values such as
`SUCCESS_NO_INFO` and `EXECUTE_FAILED`. `acceptedCount` sums only
non-negative counts; `acceptedCountMayBeIncomplete=true` records that the driver returned a
sentinel. The writer chunks input at `maxRowsPerFlush`, does not commit or
rollback a caller-owned connection, and does not close the caller's pool or
dispatcher. A writer instance is not reusable after a post-byte failure.

The real profile test uses `clickhouse-jdbc` `0.9.9` and ClickHouse Server
`26.7.3.19`, verifies the driver `WriterStatementImpl` for the opted-in path,
`PreparedStatementImpl` for unsupported SQL, and checks `DEFAULT` handling:

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

The bounded fixture benchmark covers 3 logical row counts × 3 flush sizes ×
2 row shapes × 2 paths across three fresh test processes (36 records per run
set). The fixture measures at most 2,048 in-memory rows, so the logical
10,000/100,000/1,000,000 row labels are not production wire throughput and do
not establish a private driver buffer or heap bound. The numeric source is
[clickhouse-v2-rowbinary](../../docs/benchmarks/clickhouse-v2-rowbinary);
the chart compares the three-process medians:

| Row shape | Logical rows | Flush | RowBinary rows/s | JDBC fallback rows/s | RowBinary / fallback |
| --- | ---: | ---: | ---: | ---: | ---: |
| narrow | 10,000 | 256 | 4.28M | 14.30M | 0.30x |
| narrow | 10,000 | 1,024 | 9.91M | 15.40M | 0.64x |
| narrow | 10,000 | 4,096 | 14.60M | 21.74M | 0.67x |
| narrow | 100,000 | 256 | 18.12M | 21.34M | 0.85x |
| narrow | 100,000 | 1,024 | 17.91M | 22.12M | 0.81x |
| narrow | 100,000 | 4,096 | 20.51M | 21.14M | 0.97x |
| narrow | 1,000,000 | 256 | 18.94M | 19.99M | 0.95x |
| narrow | 1,000,000 | 1,024 | 19.83M | 22.71M | 0.87x |
| narrow | 1,000,000 | 4,096 | 20.39M | 22.94M | 0.89x |
| wide | 10,000 | 256 | 1.85M | 3.46M | 0.54x |
| wide | 10,000 | 1,024 | 4.53M | 3.80M | 1.19x |
| wide | 10,000 | 4,096 | 7.42M | 10.18M | 0.73x |
| wide | 100,000 | 256 | 11.00M | 10.66M | 1.03x |
| wide | 100,000 | 1,024 | 12.04M | 11.63M | 1.04x |
| wide | 100,000 | 4,096 | 12.34M | 10.93M | 1.13x |
| wide | 1,000,000 | 256 | 12.24M | 16.91M | 0.72x |
| wide | 1,000,000 | 1,024 | 12.42M | 14.90M | 0.83x |
| wide | 1,000,000 | 4,096 | 13.46M | 14.43M | 0.93x |

![ClickHouse JDBC V2 RowBinary benchmark](../../docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.png)

The fixture shows a path/shape/flush interaction rather than a universal
optimization: JDBC fallback is faster in every narrow cell, while RowBinary
leads in the wide 10,000/1,024 cell (1.19x) and all three 100,000-row cells
(1.03x–1.13x). Treat the ratios as directional local evidence only. Recreate
the raw runs and chart with:

```bash
for run in 1 2 3; do
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseRowBinaryBenchmarkTest' \
    -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" \
    --no-parallel --max-workers=1 --no-daemon --console=plain
done
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py \
  --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale en \
  --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.svg \
  --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
```

General stream-writer support, `async_insert`, and remote cancellation or
`KILL QUERY` completion remain outside this issue; the latter is tracked by
#863.

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
| DateTime64(n[, timezone]) | Instant | `dateTime64(name, precision, zone)` |
| Date32 | LocalDate | `date32(name)` |
| LowCardinality(T) | T | `lowCardinality(name, innerType)` / `lowCardinalityString(name)` |
| Array(T) | List\<T\> | `chArray(name, innerType)` |
| Array(Nullable(T)) | List\<T?\> | `chArrayNullableElements(name, innerType)` |
| Array(Array(...)) | List\<List\<...\>\> | `chArray(name, ClickHouseArrayNullableElementsColumnType(...))` |
| Nullable(Array(...)) | List\<T?\>? | `chNullableArray(name, innerType)` |
| Map(K, V) | Map\<K, V\> | `chMap(name, keyType, valueType)` |
| Tuple(...) | List\<Any?\> | `chTuple(name, elements)` |
| Nested(...) | List\<List\<Any?\>\> | `chNested(name, elements)` (semantic adapter) |
| JSON | String / caller type | `chJson(name)` / `chJson(name, codec)` |
| UUID | UUID | `chUuid(name)` |
| IPv4 | Inet4Address | `chIpv4(name)` |
| IPv6 | Inet6Address | `chIpv6(name)` |
| Decimal(P, S) | BigDecimal | `chDecimal(name, precision, scale)` |
| Enum8/Enum16 | Enum\<E\> | `chEnum(name, values)` |
| Nullable(T) | T? | `chNullable(name, innerType)` |

Composite adapters copy JDBC `Array`/`Struct`/`ResultSet` values before returning
immutable collections and release the driver-owned resource in the same
conversion boundary. JSON raw mode validates JSON text; the codec overload
keeps serialization and deserialization caller-owned. Enum values use explicit
wire names and a typed `CAST(? AS Enum...)` marker, never ordinal values.
DateTime64 fractional input is truncated to the declared precision.

ClickHouse 26.7.3.19 rejects `Nullable(Array(...))` (Code 43) and a single
Exposed column cannot represent server-expanded `Nested(...)` subcolumns (Code
16). Those shapes are covered by H2/semantic adapters and are intentionally
excluded from the wire fixture; define physical Nested subcolumns when using
them on a server. A JDBC V2 JSON object may be returned as a map with the
driver's canonical JSON spacing.

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
