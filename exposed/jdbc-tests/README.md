# Module exposed-jdbc-tests

English | [한국어](./README.ko.md)

## Overview

Shared JDBC test infrastructure for Exposed-based modules. It gives test authors a stable `TestDB` selector, transaction-scoped helpers, schema/table fixture utilities, and reusable sample schemas so one test can run against fast H2 feedback or real MySQL/PostgreSQL coverage.

## Dialect Coverage

![JDBC test dialect coverage](../../docs/images/readme-diagrams/exposed-jdbc-tests-diagram-01.png)

### Test Lifecycle

![JDBC test lifecycle](../../docs/images/readme-diagrams/exposed-jdbc-tests-sequence-01.png)

## Adding Dependencies

```kotlin
dependencies {
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests:${version}")
}
```

## Key Features

- **Common test base**: `AbstractExposedTest` fixes the default timezone to UTC and exposes `ENABLE_DIALECTS_METHOD` for parameterized tests.
- **Dialect selection**: `TestDB.enabledDialects()` combines `useFastDB`, `EXPOSED_TEST_DB`, and the default H2/PostgreSQL/MySQL 8 set.
- **Scoped JDBC helpers**: `withDb`, `withTables`, `withSchemas`, and auto-commit variants run inside one Exposed transaction and clean up fixtures.
- **Coroutine variants**: suspending helpers mirror the blocking JDBC helpers while using `newSuspendedTransaction`.
- **Shared schemas and assertions**: reusable movie, board, blog, person, order, and composite-id fixtures keep module tests concise.

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

- Detekt static analysis is disabled for this test-support module.
- Docker is required when `TestDBConfig.useTestcontainers` is `true`.
- Set `TestDBConfig.useTestcontainers = false` when a local PostgreSQL/MySQL/MariaDB server should be used instead of Testcontainers.
- `EXPOSED_TEST_DB=POSTGRESQL` or `EXPOSED_TEST_DB=MYSQL_V8` adds that real DB next to H2 for CI-style matrix runs.
