# Module exposed-cockroachdb

English | [한국어](./README.ko.md)

Minimal CockroachDB JDBC integration for JetBrains Exposed ORM. This module
proves the first supported path for CockroachDB in `bluetape4k-exposed`:
PostgreSQL-wire JDBC connectivity plus a real Testcontainers-backed smoke test.

## Scope

`exposed-cockroachdb` provides:

- **CockroachDatabase**: a small connection factory for
  `jdbc:postgresql://<host>:<sql_port>/<database>` CockroachDB URLs.
- **Testcontainers smoke coverage**: a single-node CockroachDB test using
  `CockroachServer` from `bluetape4k-testcontainers`.

CockroachDB is PostgreSQL-wire-compatible but not PostgreSQL-equivalent. This
module intentionally does not register a custom Exposed dialect and does not
claim broad PostgreSQL DDL parity.

## Out Of Scope

- Custom CockroachDB dialect registration.
- PostgreSQL compatibility and DDL boundary matrix.
- Serializable transaction retry helper APIs.
- R2DBC support.

Those items are tracked under the CockroachDB follow-up issues in the parent
epic.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb:${version}")
}
```

The module uses the PostgreSQL JDBC driver because CockroachDB exposes the
PostgreSQL wire protocol:

```kotlin
implementation("org.postgresql:postgresql")
```

## Basic Usage

```kotlin
import io.bluetape4k.exposed.cockroachdb.CockroachDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

val db = CockroachDatabase.connect(
    host = "localhost",
    port = 26257,
    database = "defaultdb",
    user = "root",
)

transaction(db) {
    exec("SELECT 1") { rs ->
        rs.next()
        rs.getInt(1)
    }
}
```

## Testcontainers Usage

```kotlin
import io.bluetape4k.testcontainers.database.CockroachServer

val cockroach = CockroachServer.Launcher.cockroach
val db = CockroachDatabase.connect(
    jdbcUrl = cockroach.url,
    user = cockroach.username ?: CockroachServer.USERNAME,
    password = cockroach.password ?: CockroachServer.PASSWORD,
)
```

## Verification

```bash
./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon
```
