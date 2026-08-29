# bluetape4k-exposed

[![CI](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-25-ED8B00?logo=openjdk)](https://openjdk.org)
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

## Manual

The repository-owned manual is the source of truth for the stable 1.11 line:

- [Manual overview](docs/manual/en/index.md)
- [Getting started](docs/manual/en/getting-started.md)
- [Module inventory and learning path](docs/manual/en/guides/learning-path.md)

It covers all 40 released Gradle projects in English and Korean, including
ownership boundaries, runnable examples, failure diagnosis, operations, and
release-pinned source links. README files remain concise entry points; detailed
behavior belongs in `docs/manual/`.

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
| `exposed-timefold-solver-persistence` | Exposed column mappings for Timefold Score values |
| `exposed-ktor` | Ktor integration for explicit Exposed JDBC/R2DBC transactions, readiness routes, and status pages |
| `exposed-ktor-tenant-jdbc` | TenantContext-based JDBC transaction routing for Ktor |
| `exposed-ktor-tenant-r2dbc` | TenantContext-based coroutine-native R2DBC transaction routing for Ktor |
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

A transaction-aware publisher uses a different sequence when its durable
publication must participate in the command transaction: persist the aggregate
and hand off the read-only snapshot before commit, then clear the buffer only
after committed completion. See the [JDBC transaction-aware publisher](spring-boot/jdbc/README.md#transaction-aware-domain-events)
for its synchronous-listener and rollback boundaries.

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
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    // Core
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc")
    // R2DBC (coroutines)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc")
    // Redis cache
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce")
    // Jackson JSON columns
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2")
    // Ktor integration
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
    // Ktor tenant-aware JDBC/R2DBC transaction adapters (opt-in)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc")
    // Spring Boot auto-configuration
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
    // Spring Modulith JDBC event publication through Exposed
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith")
}
```

Snapshots are published to Maven Central Snapshots. Add the repository:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    mavenCentral()
}
```

<!-- migration-guide:start -->
<!-- migration-guide:heading:migration-generation -->
### Migration Generation and Schema Drift

<!-- migration-guide:heading:availability -->
#### Availability

JetBrains Exposed 1.4.0 provides the Gradle migration plugin and the JDBC and
R2DBC `MigrationUtils` APIs. The dedicated `migrationDriftTest` tasks and CI
checks described here are available on `develop` and first ship with
bluetape4k-exposed 1.12.0. See the
[Exposed Gradle plugin documentation](https://www.jetbrains.com/help/exposed/exposed-gradle-plugin.html),
[Exposed migration documentation](https://www.jetbrains.com/help/exposed/migrations.html),
and [Gradle Plugin Portal entry](https://plugins.gradle.org/plugin/org.jetbrains.exposed.plugin).

<!-- migration-guide:heading:application-users -->
#### Application Users

Use the Gradle plugin to compare Exposed table definitions with database
metadata and generate a reviewable SQL file. The application owns the output
directory, credentials, filename sequence, SQL review, and execution through
Flyway, Liquibase, or another migration runner.

This self-contained PostgreSQL example pins the upstream plugin directly,
keeps every connection value outside the build file, and includes the JDBC
driver that matches `MIGRATION_JDBC_URL`. Applications that already import the
central `bluetape4k-dependencies` catalog may replace the direct plugin line
with `alias(bt4k.plugins.exposed.plugin)`:

```kotlin
plugins {
    id("org.jetbrains.exposed.plugin") version "1.4.0"
}

val migrationJdbcUrl = providers.environmentVariable("MIGRATION_JDBC_URL")
val migrationDbUser = providers.environmentVariable("MIGRATION_DB_USER")
val migrationDbPassword = providers.environmentVariable("MIGRATION_DB_PASSWORD")

exposed {
    migrations {
        tablesPackage.set("com.example.app.persistence")
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
        databaseUrl.set(migrationJdbcUrl)
        databaseUser.set(migrationDbUser)
        databasePassword.set(migrationDbPassword)
    }
}

dependencies {
    runtimeOnly("org.postgresql:postgresql")
}
```

Create a new monotonically ordered filename for every change and fail before
generation if it already exists:

```bash
MIGRATION_FILE=V202607170001__add_description.sql
test ! -e "src/main/resources/db/migration/$MIGRATION_FILE" &&
  ./gradlew generateMigrations --filename="$MIGRATION_FILE"
```

<!-- migration-guide:warning:credentials -->
> Do not commit migration credentials or point generation at a shared or
> production database. Use a disposable or staging copy with production-shaped
> metadata and review the generated SQL before promotion.

<!-- migration-guide:warning:r2dbc-jdbc-boundary -->
> An R2DBC application still needs a build-time JDBC URL and matching JDBC
> driver for the Gradle plugin. An R2DBC URL or R2DBC runtime driver is not
> sufficient for plugin generation.

<!-- migration-guide:warning:no-runtime-management -->
> Do not run plugin generation or `MigrationUtils` comparison during application
> startup or on a request path. Neither API is a production migration runner.

<!-- migration-guide:warning:immutable-migrations -->
> Never overwrite a migration that may have been applied. The checked-in V1
> files under this repository's demos are replaceable test fixtures, not an
> application filename convention.

<!-- migration-guide:heading:surface-boundaries -->
#### Surface Boundaries

<!-- migration-guide:table:surface-boundaries -->
| Surface | Connection and purpose | Do not infer |
|---|---|---|
| `Gradle plugin` | Build-time JDBC metadata connection and script generation | It neither connects over R2DBC nor applies production migrations |
| `JDBC MigrationUtils` | Programmatic or test-time JDBC schema comparison | Do not use it for startup or request-path schema management |
| `R2DBC MigrationUtils` | Programmatic or test-time R2DBC schema comparison | Do not use it for startup or request-path schema management |

<!-- migration-guide:heading:repository-contributors -->
#### Repository Contributors

The demo V1 files are fixed-name repository fixtures. Contributors may
regenerate and replace them intentionally; applications must not copy that
naming policy. The demo proof requires only the Gradle wrapper and its H2 JDBC
driver. It writes into the two demo migration directories. A clean bounded
status proves that Exposed 1.4.0 regenerated those fixtures without repository
drift; it does not prove that arbitrary application migrations are safe.

```bash
./gradlew :exposed-spring-boot-jdbc-demo:generateMigrations --filename=V1__create_products.sql --rerun --no-build-cache --no-configuration-cache --no-daemon
./gradlew :exposed-spring-boot-r2dbc-demo:generateMigrations --filename=V1__create_webflux_products.sql --rerun --no-build-cache --no-configuration-cache --no-daemon
git status --short --untracked-files=all -- examples/jdbc-demo/src/main/resources/db/migration examples/r2dbc-demo/src/main/resources/db/migration
```

The focused H2 proof needs no external database. It writes JUnit XML under
`exposed/jdbc-tests/build/test-results/migrationDriftTest` and
`exposed/r2dbc-tests/build/test-results/migrationDriftTest`; CI stages sanitized
status and XML under `build/migration-drift-reports/h2/<api>`. A pass proves the
additive-column contract for both APIs on H2, not schema equivalence or rollout
safety.

```bash
EXPOSED_TEST_DB=H2 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon
```

Real-database proof requires a working Docker-compatible runtime for
Testcontainers. Run the commands sequentially. Each command writes its module's
`build/test-results/migrationDriftTest` XML; CI stages sanitized evidence under
`build/migration-drift-reports/<api>-<database>`. A pass proves the same focused
additive-column contract on the selected dialects. It does not approve type
changes, destructive DDL, production data handling, locks, or rollout order.

```bash
EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=POSTGRESQL ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-jdbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon

EXPOSED_TEST_DB=MYSQL_V8 ./gradlew \
  :bluetape4k-exposed-r2dbc-tests:migrationDriftTest \
  --no-configuration-cache \
  --no-parallel --max-workers=1 --no-daemon
```

<!-- migration-guide:heading:failure-diagnostics -->
#### Failure Diagnostics

<!-- migration-guide:table:failure-diagnostics -->
| Failure surface | First diagnostic and evidence order |
|---|---|
| `Gradle plugin` | Rerun the fixed-filename command with `--stacktrace --info`; inspect bounded migration-directory status |
| `H2 JDBC drift` | Run `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:migrationDriftTest --tests '*JdbcMigrationDriftTest*' --stacktrace --info`; locally inspect module `build/test-results/migrationDriftTest`, or in CI inspect staged status then sanitized XML |
| `H2 R2DBC drift` | Run `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-r2dbc-tests:migrationDriftTest --tests '*R2dbcMigrationDriftTest*' --stacktrace --info`; locally inspect module `build/test-results/migrationDriftTest`, or in CI inspect staged status then sanitized XML |
| `PostgreSQL/MySQL 8` | Verify Docker first; locally inspect the selected module's `build/test-results/migrationDriftTest`, or in CI inspect `command-summary.log`, `status.txt`, then sanitized JUnit XML |

<!-- migration-guide:heading:support-matrix -->
#### Support Matrix

<!-- migration-guide:table:support-matrix -->
| Change | Evidence level | Meaning |
|---|---|---|
| `Add nullable column` | H2 PR lane plus PostgreSQL/MySQL 8 full Nightly/manual lane | Focused JDBC and R2DBC regressions validate one exact additive statement and a clean second comparison |
| `Change column type on H2` | Characterized only | Regression records current output; it does not approve the change |
| `Change column type on PostgreSQL/MySQL 8` | Not guaranteed | Inspect and test generated SQL for the target dialect |
| `Rename or remove column` | Not guaranteed | Treat as potentially destructive and design an explicit data migration |
| `Defaults and indexes` | Not guaranteed | Review expression, ordering, locking, and existing-row effects |
| `Foreign/unique/check constraints` | Not guaranteed | Validate existing data and enforcement/locking behavior |
| `Vendor-specific DDL` | Not guaranteed | Test against the exact database version and operational policy |

An empty diff means only "no difference detected by this API and version." It
does not prove that two schemas are equal.

<!-- migration-guide:heading:promotion-review -->
#### Promotion Review

<!-- migration-guide:table:promotion-review -->
| Review area | Required checks |
|---|---|
| `Schema safety` | Review `DROP`/`TRUNCATE`, removal, rename, type changes, `NOT NULL`, defaults, indexes, unique/foreign/check constraints, and statement order |
| `Data safety` | Validate backfill correctness, production-shaped row volume, table rewrites, and data reinterpretation risk |
| `Rollout safety` | Check lock duration, phased nullable-add/backfill/constraint enforcement, database transaction support, backup, rollback, and migration-runner ownership |

Review raw SQL against a disposable or staging copy before handing it to the
application's migration runner.
<!-- migration-guide:end -->

### Database Examples

| Example | Purpose | Verification |
|---------|---------|--------------|
| `examples-ddd-spring-modulith-demo` | DDD aggregate events, Spring Modulith module boundaries, Exposed-backed publication rows, and idempotent listeners | `./gradlew :examples-ddd-spring-modulith-demo:test` |
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

### Testcontainers lifecycle

Module tests share each `XxxServer.Launcher` only within one test JVM and do
not reuse Docker containers across processes. The direct BigQuery and
StarRocks fixtures follow the same non-reusable default. For explicit local
development only, set `testcontainers.reuse.enable=true` in
`~/.testcontainers.properties` and opt in for a single command:

```bash
BLUETAPE4K_TESTCONTAINERS_REUSE=true ./gradlew :bluetape4k-exposed-bigquery:test
```

The opt-in is ignored whenever the `CI` or `GITHUB_ACTIONS` environment marker
is present, regardless of its value (including `CI=1`). Reusable containers are
not registered for JVM shutdown stop/removal; tests and examples never enable
reuse implicitly.

### Ktor Integration

`exposed-ktor` adds explicit Ktor helpers around caller-owned Exposed resources.
The default `installBluetape4kExposedKtor()` call is a no-op: status pages and
health/readiness routes are installed only when the application opts in.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
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

For one database per tenant, add the backend-specific opt-in adapter and bind
the validated `TenantId` before routing. Use an immutable exact-match resolver
such as `databases::getValue`; missing context fails before resolution and an
unknown tenant never falls back to a default database. The JDBC adapter requires
an application-owned blocking dispatcher, while the R2DBC adapter remains
coroutine-native. Map `MissingTenantContextException` to
`tenant_context_missing` and resolver failures to `tenant_resolution_failed` in
your existing `StatusPages` policy. The adapters do not log or tag raw tenant
identifiers, headers, URLs, SQL, or credentials and do not own resource
shutdown. See the [tenant JDBC manual](docs/manual/en/modules/bluetape4k-exposed-ktor-tenant-jdbc.md)
and [tenant R2DBC manual](docs/manual/en/modules/bluetape4k-exposed-ktor-tenant-r2dbc.md).

## Requirements

- JVM 25+
- Kotlin 2.4+
- JetBrains Exposed 1.3+

## License

MIT — see [LICENSE](LICENSE).
