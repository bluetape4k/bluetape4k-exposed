# Module exposed-jdbc-tests

English | [한국어](./README.ko.md)

## Exposed 1.5.0 one-way hashing

Use upstream `exposed-crypt` directly, without a new Bluetape adapter.
[`JdbcHashedColumnContractTest`](src/test/kotlin/io/bluetape4k/exposed/tests/crypt/JdbcHashedColumnContractTest.kt)
covers H2 storage, reads, nullable values, resaving, custom hashers, and rehashing. R2DBC coverage is for the DSL, not DAO support.

Applications explicitly opt in using their existing Exposed BOM/catalog.
`exposed-crypt` 1.5.0 transitively requires `spring-security-crypto`, but no Spring Boot starter. Argon2/SCrypt additionally require BouncyCastle at runtime. With the Spring Security 7.1.1 resolved by this repository, validation also needs
`spring-core`; its absence produced `NoClassDefFoundError: org/springframework/util/StringUtils`. The example assumes Spring versions are managed by the application's BOM. For test-only usage, replace implementation/runtimeOnly below with testImplementation/testRuntimeOnly.

```kotlin
dependencies {
    implementation(libs.exposed.crypt)
    runtimeOnly(bt4k.bouncycastle.bcprov) // Argon2 / SCrypt
    runtimeOnly("org.springframework:spring-core") // Spring Security 7.1.x
}
```

```kotlin
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.crypt.hash
import org.jetbrains.exposed.v1.crypt.hashed

object Accounts: Table("accounts") {
    val password = varchar("password_hash", 512).hashed()
}
val encoded = Accounts.password.hash(submittedPassword)
val accepted = encoded.matches(submittedPassword)
```

Use these values with the JDBC insert/update DSL inside its transaction. Declare nullable columns with `varchar("optional", 512).nullable().hashed()`. Assign a loaded `Hashed` directly when resaving. `Hashed(hasher, encodedValue)` only wraps an existing encoded value; it does not hash, so never pass plaintext to that constructor.

- BCrypt produces 60 characters. The example's 512-character column leaves room for algorithm identifiers and parameter changes, but cannot guarantee space for every custom hasher.
- Argon2/SCrypt output lengths depend on parameters. A PBKDF2 pepper is a separate secret and must remain available for verification.
- `Hasher` has no `upgradeEncoding` API. Use `PasswordEncoderHasher(DelegatingPasswordEncoder(...))` to verify an existing `{id}hash`; only after successful authentication and a true `upgradeEncoding(encodedValue)` result, hash the submitted plaintext with the new settings and update it. Wrong input must not change stored values.
- Changing configuration alone does not migrate hashes. Never rehash an old hash without plaintext. The application owns concurrent-update policy.
- BCrypt strengths 4/5 in tests reduce test duration; they are not production recommendations. Measure production cost separately, define input-size/rate limits, and do not hash on an event loop.
- `Hashed.toString()` returns `Hashed(***)`, but direct `encodedValue` output and custom encoder exceptions are not automatically redacted. Do not log plaintext, hashes, or request bodies, or publish them in events. Use generic authentication failure messages and separately restrict SQL/driver debug logging.
- Plaintext storage/logger/database-error negative tests prove the configured upstream path, not arbitrary custom hashers or application-wide logging safety.
- Reversible and searchable encryption remain separate [Tink module](../tink/README.md) features.

Official baseline: [Exposed 1.5.0 crypt sources](https://github.com/JetBrains/Exposed/tree/84361204b6639cad5696506a26595c97afac3531/exposed-crypt).

## Overview

Shared JDBC test infrastructure for Exposed-based modules. It gives test authors a stable `TestDB` selector, transaction-scoped helpers, schema/table fixture utilities, and reusable sample schemas so one test can run against fast H2 feedback or real MySQL/PostgreSQL coverage.

## Dialect Coverage

![JDBC test dialect coverage](../../docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.png)

### Test Lifecycle

![JDBC test lifecycle](../../docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.png)

## Adding Dependencies

```kotlin
dependencies {
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests")
}
```

## Key Features

- **Common test
  base**: `AbstractExposedTest` fixes the default timezone to UTC and exposes `ENABLE_DIALECTS_METHOD` for parameterized tests.
- **Dialect
  selection**: `TestDB.enabledDialects()` combines `useFastDB`, `EXPOSED_TEST_DB`, and the default H2/PostgreSQL/MySQL 8 set.
- **Scoped JDBC
  helpers**: `withDb`, `withTables`, `withSchemas`, and auto-commit variants run inside one Exposed transaction and clean up fixtures.
- **Coroutine variants**: suspending helpers mirror the blocking JDBC helpers while using `suspendTransaction`.
- **Shared schemas and
  assertions**: reusable movie, board, blog, person, order, and composite-id fixtures keep module tests concise.

## Supported Databases

| Database             | TestDB         | Testcontainers |
|----------------------|----------------|----------------|
| H2 v1                | `H2_V1`        | No             |
| H2 v2                | `H2`           | No             |
| H2 MySQL mode        | `H2_MYSQL`     | No             |
| H2 MariaDB mode      | `H2_MARIADB`   | No             |
| H2 PostgreSQL mode   | `H2_PSQL`      | No             |
| MariaDB              | `MARIADB`      | Yes            |
| MySQL 5.7            | `MYSQL_V5`     | Yes            |
| MySQL 8.0            | `MYSQL_V8`     | Yes            |
| PostgreSQL           | `POSTGRESQL`   | Yes            |
| PostgreSQL pgjdbc-ng | `POSTGRESQLNG` | Yes            |

## Usage Examples

### Writing a basic test

```kotlin
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

object Users: LongIdTable("users") {
    val name = varchar("name", 50)
    val email = varchar("email", 100)
}

class UserRepositoryTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert and find user`(testDB: TestDB) {
        withTables(testDB, Users) {
            // Insert
            Users.insert {
                it[name] = "John"
                it[email] = "john@example.com"
            }

            // Query
            val user = Users.selectAll().single()

            assertEquals("John", user[Users.name])
            assertEquals("john@example.com", user[Users.email])
        }
    }
}
```

### withDb — DB connection only (no tables)

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should connect to database`(testDB: TestDB) {
    withDb(testDB) {
        // Runs inside a transaction
        val isConnected = connection.isValid(5)
        assertTrue(isConnected)
    }
}
```

### withTables — Auto create and drop tables

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should create and drop tables`(testDB: TestDB) {
    withTables(testDB, Users, Orders) {
        // Tables are created before the test
        // Tables are dropped after the test

        Users.insert { /* ... */ }
        Orders.insert { /* ... */ }

        // Test logic
    }
}
```

### Coroutines environment (async tests)

```kotlin
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTablesSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class AsyncRepositoryTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert user in coroutine`(testDB: TestDB) = runBlocking {
        withTablesSuspending(testDB, Users) {
            // Runs inside a suspend function
            Users.insert {
                it[name] = "John"
                it[email] = "john@example.com"
            }

            val user = Users.selectAll().single()
            assertEquals("John", user[Users.name])
        }
    }
}
```

### Testing against a specific database only

```kotlin
import io.bluetape4k.exposed.tests.TestDB

class PostgresOnlyTest: AbstractExposedTest() {

    // Test only against PostgreSQL
    companion object {
        @JvmStatic
        fun databases() = TestDB.ALL_POSTGRES
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `postgres specific test`(testDB: TestDB) {
        withTables(testDB, Users) {
            // PostgreSQL-specific test
        }
    }
}
```

### Testing against a group of databases

```kotlin
import io.bluetape4k.exposed.tests.TestDB

class MySQLLikeTest: AbstractExposedTest() {

    companion object {
        // MySQL + MariaDB + H2 MySQL mode
        @JvmStatic
        fun databases() = TestDB.ALL_MYSQL_LIKE

        // PostgreSQL + H2 PostgreSQL mode
        @JvmStatic
        fun postgresDatabases() = TestDB.ALL_POSTGRES_LIKE
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `mysql compatible test`(testDB: TestDB) {
        withTables(testDB, Users) {
            // MySQL-compatible DB test
        }
    }
}
```

## TestDB Configuration

```kotlin
import io.bluetape4k.exposed.tests.TestDBConfig

// Whether to use Testcontainers
TestDBConfig.useTestcontainers = true  // default

// Use only H2 for fast tests (default: false)
TestDBConfig.useFastDB = true
```

## Test Schemas and Data

### MovieSchema (DAO example)

```kotlin
import io.bluetape4k.exposed.shared.entities.MovieSchema

class MovieTest: AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should query actors by movie`(testDB: TestDB) {
        withMovieAndActors(testDB) {
            // Sample data is pre-loaded
            val actors = ActorEntity.all()
            assertTrue(actors.isNotEmpty())
        }
    }
}
```

### Shared table schemas

| File                             | Description                       |
|----------------------------------|-----------------------------------|
| `shared/entities/MovieSchema.kt` | Movie, Actor, ActorInMovie tables |
| `shared/entities/BoardSchema.kt` | Board table                       |
| `shared/entities/BlogSchema.kt`  | Blog table                        |
| `shared/mapping/PersonSchema.kt` | Person mapping table              |
| `shared/mapping/OrderSchema.kt`  | Order mapping table               |

## Testcontainers Configuration

```kotlin
import io.bluetape4k.exposed.tests.Containers

// MariaDB container
Containers.MariaDB

// MySQL 5.7 container
Containers.MySQL5

// MySQL 8.0 container
Containers.MySQL8

// PostgreSQL container
Containers.Postgres
```

## Key Files

| File                          | Description                                                                                                                           |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `AbstractExposedTest.kt`      | Base test class                                                                                                                       |
| `TestDB.kt`                   | Supported database definitions and connection info                                                                                    |
| `TestDBConfig.kt`             | Test environment settings (`useTestcontainers`, `useFastDB`)                                                                          |
| `Containers.kt`               | Testcontainers container management                                                                                                   |
| `WithDB.kt`                   | DB connection utilities                                                                                                               |
| `WithTables.kt`               | Table create/drop utilities                                                                                                           |
| `WithSchemas.kt`              | Schema utilities                                                                                                                      |
| `WithAutoCommit.kt`           | AutoCommit mode utilities                                                                                                             |
| `WithDBSuspending.kt`         | Coroutines DB connection utilities                                                                                                    |
| `WithTablesSuspending.kt`     | Coroutines table utilities                                                                                                            |
| `WithSchemasSuspending.kt`    | Coroutines schema utilities                                                                                                           |
| `WithAutoCommitSuspending.kt` | Coroutines AutoCommit utilities                                                                                                       |
| `Assertions.kt`               | Test assertion utilities (`assertTrue`, `assertFalse`, `assertEquals`, `assertNotEquals`, `assertFailAndRollback`, `expectException`) |
| `TestSupports.kt`             | Test support utilities (`inProperCase`, `currentDialectTest`, etc.)                                                                   |

## Test Run Options

```bash
# H2 only
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc-tests:test

# Default enabled dialects: H2 + PostgreSQL + MySQL 8
./gradlew :bluetape4k-exposed-jdbc-tests:test

# CI-style matrix lanes add one real DB next to H2
EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-jdbc-tests:test
EXPOSED_TEST_DB=MYSQL_V8 ./gradlew :bluetape4k-exposed-jdbc-tests:test
```

## Notes

- Run module-scoped `detekt` and `checkKotlinAbi` alongside tests.
- Docker is required when `TestDBConfig.useTestcontainers` is `true`.
- Set `TestDBConfig.useTestcontainers = false` when a local PostgreSQL/MySQL/MariaDB server should be used instead of Testcontainers.
- `EXPOSED_TEST_DB=POSTGRESQL` or `EXPOSED_TEST_DB=MYSQL_V8` adds that real DB next to H2 for CI-style matrix runs.

## Caller-owned fixture contracts

Use `JdbcTestDbFixture<K>` through `jdbcTestDbFixture` when an application owns its DB selector and connection supply. Create one fixture per physical test DB and share it for the test-suite/JVM lifetime. Keys are not stored in a global registry. A factory callback returns an Exposed wrapper around an existing caller-owned pool or connection supply; it must not allocate a new long-lived pool on each call.

The following fragment assumes the application's `ApplicationDb`, `Orders`, and connection supply already exist:

```kotlin
val fixture = jdbcTestDbFixture(
    key = ApplicationDb.PRIMARY,
    createDatabase = { configure ->
        Database.connect(dataSource, databaseConfig = DatabaseConfig { configure() })
    },
)
withTables(fixture, Orders) { key ->
    // Seed/FK/domain cleanup remains in the application wrapper.
}
```

- Existing enum functions, default arguments, and deprecated compatibility aliases remain available. New overloads accept fixtures for DB, table and schema helpers, including JDBC suspend variants.
- Same-fixture calls are FIFO; distinct fixtures proceed independently. Active nested calls of the same fixture fail fast, including inherited coroutine contexts. A completed entry no longer blocks later calls.
- `database` is null until initialization and shutdown-hook registration succeed, then exposes the baseline wrapper. Even the first `configure` call creates a separate temporary wrapper; after the transaction finishes its provider registration is removed. Returning the baseline wrapper for temporary configuration is rejected.
- Creation or shutdown-hook registration failure allows a later initialization attempt. Each actual wrapper creation runs the legacy `beforeConnection` once. External pools/containers remain caller-owned; the provider does not close them.
- `currentJdbcTestDbFixture` identifies the fixture inside the transaction and survives commit. Legacy enum calls retain `currentTestDB` behavior. Transactions execute the body once (`maxAttempts = 1`).
- Cancellation remains cancellation. Only required cleanup is protected. Body failure wins over cleanup/recovery failures, which are retained in occurrence order.
- Pass only exclusively owned test tables/schemas: pre-drop and cascade cleanup are intentional. Partial creation is cleaned up unless `dropTables = false`; unsupported schema dialects do not execute the body. Database outages or injected drop failures can leave residue.
- Provider logs do not include custom keys, URLs, configuration or callback exception messages. Driver/application logging is caller policy. Coroutine debug stacktrace recovery may copy exceptions; the helper does not replace the primary failure.
- Declare these artifacts with `testImplementation`, under the application's `bluetape4k-dependencies` BOM. Test runtime support is not application main runtime. Internal Exposed cleanup failures that upstream only logs remain outside the suppression guarantee (issue #817).

Downstream workshop/clinic migration and publication are separate work; this provider change does not complete issue #815 by itself.
