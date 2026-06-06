# Module exposed-starrocks

English | [한국어](./README.ko.md)

StarRocks JDBC integration for JetBrains Exposed ORM. This module proves a
narrow local-first OLAP path: native StarRocks Connector/J connectivity, Exposed
dialect registration, metadata discovery, fixture table setup, and simple query
execution.

## Scope

`exposed-starrocks` provides:

- **StarRocksDatabase**: connection factory for
  `jdbc:starrocks://<fe_host>:<fe_query_port>/<catalog>.<database>`.
- **StarRocksDialect**: minimal Exposed dialect registered as `starrocks`.
- **StarRocksDialectMetadata**: metadata adapter that keeps standard JDBC
  `DatabaseMetaData` discovery enabled.
- **StarRocksConnectionWrapper**: autocommit-oriented JDBC wrapper for Exposed
  compatibility.
- **StarRocksConnectionOptions**: narrow extra JDBC property holder.
- **StarRocksTable**: simple fixture-oriented table base that removes generic
  primary-key syntax and appends conservative StarRocks OLAP table options.

This module does not claim MySQL, PostgreSQL, Trino, or ClickHouse parity. Broad
StarRocks DDL, partitioning, aggregate key variants, stream load, external
catalogs, and StarRocks Cloud verification are out of scope.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-starrocks:${version}")
}
```

The module uses StarRocks Connector/J:

```kotlin
implementation("com.starrocks:starrocks-connector-j:1.1.1")
```

## Local StarRocks

The tested local container path follows the official StarRocks all-in-one image:

```bash
docker run -p 9030:9030 -p 8030:8030 -p 8040:8040 -itd \
  --name quickstart starrocks/allin1-ubuntu
```

Docker should have at least 4 GB RAM and 10 GB free disk available. The FE query
port is `9030`.

## Basic Usage

```kotlin
import io.bluetape4k.exposed.starrocks.StarRocksDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

val db = StarRocksDatabase.connect(
    host = "localhost",
    port = 9030,
    catalog = "default_catalog",
    database = "analytics",
    user = "root",
)

transaction(db) {
    exec("SELECT 1") { rs ->
        rs.next()
        rs.getInt(1)
    }
}
```

Create the target database before connecting to
`default_catalog.<database>`. The local tests bootstrap a dedicated database and
then verify table metadata and `SELECT` queries through Exposed.

## Verification

```bash
./gradlew :bluetape4k-exposed-starrocks:test --no-configuration-cache --no-daemon
```
