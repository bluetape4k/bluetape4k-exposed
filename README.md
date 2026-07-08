# bluetape4k-exposed

[![CI](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

English | [한국어](README.ko.md)

![Bluetape4k Exposed workbench](./docs/assets/exposed-workbench.png)

Kotlin extensions for [JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM — repository runtimes, cache decorators, JSON/encrypted columns, database dialect helpers, and Spring Boot auto-configuration.

---

## Project Purpose

`bluetape4k-exposed` turns JetBrains Exposed into a production-oriented Kotlin
data toolkit. Start with the JDBC or R2DBC repository runtime, then add cache
decorators, column codecs, database-specific helpers, and Spring Boot 4
auto-configuration only where the application data path needs them.

## Features

- **Repository Pattern** — Type-safe JDBC and R2DBC (coroutine) repository abstractions built on Exposed DSL
- **CTE Query DSL** — PostgreSQL/MySQL `WITH` and `WITH RECURSIVE` SELECT helpers for JDBC and R2DBC
- **Cache Integrations** — Caffeine (local), Lettuce and Redisson (distributed Redis) cache backends
- **JSON Columns** — Jackson 2.x, Jackson 3.x, and Fastjson2 column serializers
- **Encryption** — Google Tink-based encrypted columns
- **Database-specific Extensions** — PostgreSQL, MySQL 8, BigQuery, ClickHouse, Trino, StarRocks, CockroachDB, DuckDB, and Timefold persistence helpers
- **Ktor** — Explicit Ktor helpers for caller-owned Exposed JDBC/R2DBC resources, readiness routes, and safe status pages
- **Spring Boot** — Spring Boot 4.x auto-configuration (JDBC, R2DBC, Batch, and Spring Modulith JDBC event publication integration)
- **Measured Columns** — Exposed custom column types for `bluetape4k-measured` units

<!-- README_VISUAL_OVERVIEW:START -->
## Overview Diagram

![Bluetape4k Exposed overview diagram](docs/images/readme-diagrams/root-readme-overview-01.png)

## Module Composition Diagram

![Bluetape4k Exposed module composition diagram](docs/images/readme-diagrams/root-readme-module-relationships-01.png)
<!-- README_VISUAL_OVERVIEW:END -->

## Modules

| Module | Description |
|--------|-------------|
| `exposed-core` | Core Column types, DSL helpers, extension functions |
| `exposed-dao` | DAO Entity extensions, lifecycle hooks |
| `exposed-jdbc` | JDBC-based Repository pattern, transaction DSL |
| `exposed-r2dbc` | R2DBC coroutine-native Repository, suspend transactions |
| `exposed-jdbc-tests` | JDBC integration test fixtures |
| `exposed-r2dbc-tests` | R2DBC integration test fixtures |
| `exposed-cache` | Cache abstraction interfaces |
| `exposed-jdbc-caffeine` | JDBC + Caffeine local cache |
| `exposed-jdbc-lettuce` | JDBC + Lettuce Redis distributed cache |
| `exposed-jdbc-redisson` | JDBC + Redisson Redis distributed cache |
| `exposed-r2dbc-caffeine` | R2DBC + Caffeine local cache |
| `exposed-r2dbc-lettuce` | R2DBC + Lettuce Redis distributed cache |
| `exposed-r2dbc-redisson` | R2DBC + Redisson Redis distributed cache |
| `exposed-jackson2` | Jackson 2.x JSON column serialization |
| `exposed-jackson3` | Jackson 3.x JSON column serialization |
| `exposed-fastjson2` | Fastjson2 JSON column serialization |
| `exposed-tink` | Google Tink encrypted columns |
| `exposed-measured` | Custom ColumnType mappings for measured units |
| `exposed-postgresql` | PostgreSQL dialect extensions |
| `exposed-mysql8` | MySQL 8 dialect extensions |
| `exposed-bigquery` | BigQuery connector support |
| `exposed-clickhouse` | ClickHouse connector support |
| `exposed-trino` | Trino connector support |
| `exposed-starrocks` | StarRocks local-first OLAP connector support |
| `exposed-cockroachdb` | CockroachDB PostgreSQL-wire smoke support |
| `exposed-duckdb` | DuckDB embedded analytics support |
| `exposed-druid` | Apache Druid query-only Avatica JDBC experiment |
| `exposed-timefold-solver-persistence` | Timefold Solver persistence integration |
| `exposed-ktor` | Ktor integration for explicit Exposed JDBC/R2DBC transactions, readiness routes, and status pages |
| `exposed-spring-boot-jdbc` | Spring Boot 4.x JDBC auto-configuration |
| `exposed-spring-boot-r2dbc` | Spring Boot 4.x R2DBC auto-configuration |
| `exposed-spring-boot-batch` | Spring Boot 4.x batch integration |
| `exposed-spring-modulith` | Spring Modulith JDBC event publication repository backed by Exposed |

## Boundary with JaVers

`bluetape4k-exposed` owns the application data path around JetBrains Exposed:
repository execution, transaction boundaries, cache read/write behavior, and
Spring Boot or Ktor integration. DDD-facing contracts in this repository should
therefore stay Spring-neutral and JaVers-neutral. They may describe aggregate
roots, pending domain events, and after-commit publication hooks, but they
should not encode JaVers audit concepts.

Use `bluetape4k-javers` when the requirement is object history, diffing, or
JaVers commit metadata. In that repository, `javers-exposed` stores JaVers CDO
snapshots through Exposed JDBC, and `javers-ddd` adapts aggregate/domain-event
workflows into JaVers commits. Those modules complement this repository; they
do not replace the source-of-truth Exposed repositories or cache decorators.

## Spring-Neutral DDD Contracts

`bluetape4k-exposed-core` provides Spring-neutral `AggregateRoot`,
`DomainEvent`, and `AbstractAggregateRoot` contracts for aggregates that record
domain events before repository adapters publish or persist them.

These contracts are opt-in helpers. Existing repositories, cache decorators,
Spring Modulith integration, and JaVers integration are unaffected until an
application explicitly adopts the new aggregate base class or interfaces. They
do not trigger automatic publication or persistence.

```kotlin
import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import java.io.Serializable
import java.time.Instant

@JvmInline
value class OrderId(val value: Long) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

class Order(
    override val id: OrderId,
) : AbstractAggregateRoot<OrderId>() {

    fun place() {
        recordDomainEvent(OrderPlaced(id))
    }
}

data class OrderPlaced(
    override val aggregateId: OrderId,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent<OrderId>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

The contracts keep only an in-memory event buffer. They do not provide a
durable outbox, publisher adapter, Exposed DAO lifecycle hook, Exposed DAO
`EntityCache` event registry, in-memory event queue, or Spring Modulith
publication store. A database flush that can still roll back is not a durable
event boundary.

Repository integrations should:

1. Snapshot events with `domainEvents()`.
2. Persist aggregate state and wait for after-transaction-commit or an
   equivalent durability boundary.
3. Hand the snapshot to a durable owner such as an outbox, persisted retry
   queue, or transactionally recorded handoff.
4. Clear or drain the aggregate buffer only after that durable owner accepts
   responsibility for the events.

The Spring Modulith and JaVers modules remain separate adapters. These core
contracts do not encode Spring Modulith publication semantics or JaVers audit
commit semantics.

Event payloads should prefer opaque, non-sensitive identifiers and minimal
business facts. Do not put secrets, credentials, tokens, natural keys, or
unnecessary PII in domain events.

## Quick Start

### Gradle

```kotlin
dependencies {
    // Core
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc:1.11.0")
    // R2DBC (coroutines)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc:1.11.0")
    // Redis cache
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce:1.11.0")
    // Jackson JSON columns
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2:1.11.0")
    // Ktor integration
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor:1.11.0")
    // Spring Boot auto-configuration
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.11.0")
    // Spring Modulith JDBC event publication through Exposed
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith:1.11.0")
}
```

Snapshots are published to Maven Central Snapshots. Add the repository:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    mavenCentral()
}
```

### Exposed Gradle Plugin

Use JetBrains' official Exposed Gradle plugin in application or example modules
that generate migration scripts from Exposed table definitions. Keep its version
aligned with the JetBrains Exposed version used by the project. In bluetape4k
repositories, consume the plugin from the central `bluetape4k-dependencies`
catalog so the plugin version follows the shared `exposed` compatibility line.

```kotlin
plugins {
    alias(bt4k.plugins.exposed.plugin)
}
```

The plugin adds the `generateMigrations` workflow and uses the
`exposed.migrations` block to configure the table package and the target
database or Testcontainers image. See the
[Exposed Gradle plugin documentation](https://www.jetbrains.com/help/exposed/exposed-gradle-plugin.html)
and the
[Gradle Plugin Portal entry](https://plugins.gradle.org/plugin/org.jetbrains.exposed.plugin).

Demo migration generation is covered by a weekly and pull-request smoke workflow:

```bash
./gradlew :exposed-spring-boot-jdbc-demo:generateMigrations --filename=V1__create_products.sql
./gradlew :exposed-spring-boot-r2dbc-demo:generateMigrations --filename=V1__create_webflux_products.sql
```

### Database Examples

| Example | Purpose | Verification |
|---------|---------|--------------|
| `examples-exposed-clickhouse-oltp-olap` | PostgreSQL OLTP to ClickHouse OLAP forwarding and aggregate analytics | `./gradlew :examples-exposed-clickhouse-oltp-olap:test` |
| `examples-exposed-bigquery-dry-run` | Credential-free BigQuery REST dry-run validation with query-job options | `./gradlew :examples-exposed-bigquery-dry-run:test` |

### JDBC Repository (H2 / PostgreSQL / MySQL)

```kotlin
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object UserTable : LongIdTable("users") {
    val name = varchar("name", 255)
    val email = varchar("email", 255)
    val createdAt = datetime("created_at")
}

class UserRepository(private val database: Database) {

    fun findById(id: Long): ResultRow? = transaction(database) {
        UserTable.selectAll()
            .where { UserTable.id eq id }
            .singleOrNull()
    }

    fun findAll(): List<ResultRow> = transaction(database) {
        UserTable.selectAll().toList()
    }

    fun create(name: String, email: String): Long = transaction(database) {
        UserTable.insertAndGetId {
            it[UserTable.name] = name
            it[UserTable.email] = email
            it[UserTable.createdAt] = org.joda.time.DateTime.now()
        }.value
    }

    fun deleteById(id: Long): Boolean = transaction(database) {
        UserTable.deleteWhere { UserTable.id eq id } > 0
    }
}

// Usage
val db = Database.connect(dataSource)
SchemaUtils.create(UserTable)

val repo = UserRepository(db)
val id = repo.create("Alice", "alice@example.com")
val user = repo.findById(id)
```

### R2DBC Coroutine Repository

```kotlin
import io.bluetape4k.exposed.r2dbc.transactions.suspendTransaction

class UserR2dbcRepository(private val database: R2dbcDatabase) {

    suspend fun findById(id: Long): ResultRow? = suspendTransaction(database) {
        UserTable.selectAll()
            .where { UserTable.id eq id }
            .singleOrNull()
    }

    suspend fun create(name: String, email: String): Long = suspendTransaction(database) {
        UserTable.insertAndGetId {
            it[UserTable.name] = name
            it[UserTable.email] = email
        }.value
    }
}

// Usage in a coroutine scope
val repo = UserR2dbcRepository(r2dbcDatabase)
val id = repo.create("Bob", "bob@example.com")
```

### Common Table Expressions (PostgreSQL / MySQL)

```kotlin
import io.bluetape4k.exposed.core.CteTable
import io.bluetape4k.exposed.jdbc.withCte
import org.jetbrains.exposed.v1.jdbc.select

val activeUsers = CteTable(
    name = "active_users",
    query = Users.select(Users.id, Users.name).where { Users.active eq true }
)

val rows = activeUsers
    .select(activeUsers[Users.id], activeUsers[Users.name])
    .withCte(activeUsers)
    .orderBy(activeUsers[Users.id])
    .toList()
```

### JSON Columns (Jackson)

```kotlin
import io.bluetape4k.exposed.jackson2.json

data class Address(val street: String, val city: String)

object ContactTable : LongIdTable("contacts") {
    val name = varchar("name", 255)
    val address = json<Address>("address")  // stored as JSON text
}
```

### Encrypted Columns (Tink)

```kotlin
import io.bluetape4k.exposed.tink.encrypted

object SecretTable : LongIdTable("secrets") {
    val sensitiveData = encrypted("data")  // AES-GCM encrypted at rest
}
```

### Spring Boot Auto-configuration

```kotlin
@SpringBootApplication
@EnableExposedJdbc
class MyApplication

// application.yml
// spring:
//   datasource:
//     url: ${APP_JDBC_URL}
```

### Spring Modulith Event Publication

`exposed-spring-modulith` provides a JDBC-only
Spring Modulith `EventPublicationRepository` backed by Exposed DSL and the
same Exposed `DataSource`/`springTransactionManager`. The artifact is named
`exposed-spring-modulith` to avoid looking like an official Spring Modulith
store module.

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        completion-mode: update
        initialize-schema: false
```

Use Flyway or Liquibase for production schema creation. `initialize-schema`
is intended for tests and small local applications.

### Ktor Integration

`exposed-ktor` adds explicit Ktor helpers around caller-owned Exposed resources.
The default `installBluetape4kExposedKtor()` call is a no-op: status pages and
health/readiness routes are installed only when the application opts in.

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor:1.11.0")
}
```

```kotlin
import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.bluetape4kExposedErrors
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.bluetape4kErrorResponses
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

fun Application.module(
    jdbcDatabase: Database,
    r2dbcDatabase: R2dbcDatabase,
) {
    val jdbcDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installStatusPages = false,
            installHealthRoutes = false,
        )
    )
    install(StatusPages) {
        bluetape4kErrorResponses()
        bluetape4kExposedErrors()
    }
    installBluetape4kExposedKtor(
        Bluetape4kExposedKtorConfig(
            jdbcDatabase = jdbcDatabase,
            jdbcBlockingDispatcher = jdbcDispatcher,
            r2dbcDatabase = r2dbcDatabase,
            installHealthRoutes = true,
            readinessProbeTimeout = 2.seconds,
            installStatusPages = false,
        )
    )
}
```

JDBC work is blocking; pass a dedicated dispatcher and close it with the
application-owned lifecycle. R2DBC work stays coroutine-native through
`exposedR2dbcTransaction()` / `suspendTransaction`. See
[ktor/exposed/README.md](ktor/exposed/README.md) for StatusPages composition,
readiness triage, rollback, and non-goals.

## Requirements

- JVM 21+
- Kotlin 2.3+
- JetBrains Exposed 1.3+

## License

MIT — see [LICENSE](LICENSE).
