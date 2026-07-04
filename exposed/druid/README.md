# Module exposed-druid

English | [한국어](./README.ko.md)

Query-only Apache Druid JDBC experiment for `bluetape4k-exposed`. The module
uses Apache Calcite Avatica JDBC to connect to a Druid Router or Broker and
provides small helpers for SQL query execution and datasource metadata discovery.

## Positioning

`exposed-druid` is intentionally **not** an Exposed dialect parity module.

Supported:

- Avatica JDBC URL/property construction for Druid Router/Broker endpoints
- `SELECT`, `WITH`, `EXPLAIN`, `DESCRIBE`, and `SHOW` style query execution
- datasource column metadata lookup through `INFORMATION_SCHEMA.COLUMNS`
- suspend query execution on `Dispatchers.IO`

Out of scope:

- DDL and DML helpers
- DAO/repository abstractions
- Exposed `Database`/Dialect registration
- migration or schema-generation support
- batch writes or ingestion APIs

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-druid:${version}")
}
```

## Connection

```kotlin
import io.bluetape4k.exposed.druid.DruidConnectionOptions
import io.bluetape4k.exposed.druid.DruidJdbc

val options = DruidConnectionOptions(
    avaticaEndpoint = "http://localhost:8888/druid/v2/sql/avatica/",
    contextProperties = mapOf("sqlTimeZone" to "Etc/UTC"),
)

val rows = DruidJdbc.query(
    sql = "SELECT COUNT(*) AS cnt FROM \"wikipedia\"",
    options = options,
) { rs -> rs.getLong("cnt") }
```

The default endpoint is the Router Avatica path:

```text
http://localhost:8888/druid/v2/sql/avatica/
```

`DruidConnectionOptions.jdbcUrl()` builds an Avatica URL like:

```text
jdbc:avatica:remote:url=http://localhost:8888/druid/v2/sql/avatica/;transparent_reconnection=true
```

Use the protobuf endpoint when the deployment supports it:

```kotlin
val options = DruidConnectionOptions(
    avaticaEndpoint = "http://localhost:8888/druid/v2/sql/avatica-protobuf/",
    serialization = DruidAvaticaSerialization.PROTOBUF,
)
```

## Metadata discovery

```kotlin
val columns = DruidJdbc.listColumns(
    datasource = "wikipedia",
    options = options,
)

columns.forEach { column ->
    println("${column.columnName}: ${column.dataType}")
}
```

The helper uses a parameterized `INFORMATION_SCHEMA.COLUMNS` query with
`TABLE_SCHEMA='druid'` by default.

## Suspend query

```kotlin
val comments = DruidJdbc.querySuspend(
    sql = "SELECT comment FROM \"wikipedia\" LIMIT 10",
    options = options,
) { rs -> rs.getString("comment") }
```

Blocking JDBC work is dispatched to `Dispatchers.IO` by default. Cancellation is
re-thrown rather than swallowed.

## Router/Broker stickiness

Druid JDBC connections are stateful at the Broker. Prefer the Router Avatica
endpoint, which provides connection stickiness, or use a Broker/load balancer
configuration that keeps JDBC requests sticky. Keep `transparent_reconnection`
enabled so Avatica can recover from Broker pool membership changes or restarts.

## Local/container smoke test

Normal CI runs the module unit tests only. To prove a prepared local/container
Druid instance with a loaded fixture datasource, start Druid first, load a
fixture such as `wikipedia`, then run:

```bash
EXPOSED_DRUID_SMOKE=true \
EXPOSED_DRUID_AVATICA_ENDPOINT='http://localhost:8888/druid/v2/sql/avatica/' \
EXPOSED_DRUID_DATASOURCE=wikipedia \
./gradlew --no-parallel :bluetape4k-exposed-druid:test --tests '*DruidJdbcSmokeTest'
```

The smoke test checks Avatica connection, metadata discovery, and one `SELECT`
against the fixture datasource. Keep Testcontainers or Docker-backed Druid
verification serial; the official Druid quickstart is multi-container and
memory-heavy.

## References

- Apache Druid SQL JDBC driver API: https://druid.apache.org/docs/latest/api-reference/sql-jdbc/
- Apache Druid SQL metadata tables: https://druid.apache.org/docs/latest/querying/sql-metadata-tables/
- Apache Druid Docker quickstart: https://druid.apache.org/docs/latest/tutorials/docker/
