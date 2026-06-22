# Module exposed-r2dbc-tests

English | [한국어](./README.ko.md)

## Overview

A shared test-infrastructure module for testing code built on [Exposed R2DBC](https://github.com/JetBrains/Exposed). It provides coroutine-aware database fixtures, dialect selection, Testcontainers bootstrap, and schema/table cleanup helpers so module tests can focus on behavior instead of connection setup.

## Test Infrastructure

The architecture view shows the public testing surface first: test classes extend `AbstractExposedR2dbcTest`, receive `TestDB` values from `enabledDialects()`, and run assertions inside suspend helpers such as `withDb`, `withTables`, and `withSchemas`.

![Test Infrastructure Structure diagram](../../docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-01.png)

### `withTables` Test Lifecycle

The lifecycle view follows the most common helper. `withTables` delegates to `withDb`, serializes work per `TestDB`, creates the requested tables, commits before cleanup, and retries table drop in a top-level suspend transaction if normal cleanup fails.

![withTables R2DBC test lifecycle diagram](../../docs/images/readme-diagrams/exposed-r2dbc-tests-diagram-02.png)

## Adding the Dependency

```kotlin
dependencies {
    testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc-tests:${version}")
}
```

## Key Features

- **Common test base**: `AbstractExposedR2dbcTest` provides the base structure for R2DBC tests
- **Multiple database support**: supports H2, MySQL, MariaDB, and PostgreSQL R2DBC tests
- **Testcontainers integration**: supports real database tests through Docker-based containers
- **Coroutine-native helpers**: database helpers are `suspend` functions and fit naturally inside `runTest`
- **Table and schema utilities**: reusable entities and tables for tests

## Supported Databases

| Database           | TestDB       | R2DBC Driver       |
|--------------------|--------------|--------------------|
| H2                 | `H2`         | `r2dbc-h2`         |
| H2 MySQL mode      | `H2_MYSQL`   | `r2dbc-h2`         |
| H2 MariaDB mode    | `H2_MARIADB` | `r2dbc-h2`         |
| H2 PostgreSQL mode | `H2_PSQL`    | `r2dbc-h2`         |
| MariaDB            | `MARIADB`    | `r2dbc-mariadb`    |
| MySQL 8.0          | `MYSQL_V8`   | `r2dbc-mysql`      |
| PostgreSQL         | `POSTGRESQL` | `r2dbc-postgresql` |

## Usage Examples

### Write a Basic Test

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

object Users: LongIdTable("users") {
    val name = varchar("name", 50)
    val email = varchar("email", 100)
}

class UserRepositoryTest: AbstractExposedR2dbcTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `should insert and find user`(testDB: TestDB) = runTest {
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

### `withDb` - When You Only Need a DB Connection

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should connect to database`(testDB: TestDB) = runTest {
    withDb(testDB) {
        // runs inside a suspend transaction
        val isConnected = true // connection check logic
        assertTrue(isConnected)
    }
}
```

### `withTables` - Automatic Table Create/Drop

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import kotlinx.coroutines.test.runTest

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should create and drop tables`(testDB: TestDB) = runTest {
    withTables(testDB, Users, Orders) {
        // tables are created automatically before the test
        // tables are dropped automatically after the test

        Users.insert { /* ... */ }
        Orders.insert { /* ... */ }

        // test logic
    }
}
```

### Test Only a Specific Database

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import kotlinx.coroutines.test.runTest

class PostgresOnlyTest: AbstractExposedR2dbcTest() {

    // PostgreSQL only
    companion object {
        @JvmStatic
        fun databases() = TestDB.ALL_POSTGRES
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `postgres specific test`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            // PostgreSQL-specific test
        }
    }
}
```

### Test by Database Group

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import kotlinx.coroutines.test.runTest

class MySQLLikeTest: AbstractExposedR2dbcTest() {

    companion object {
        // MySQL variants + H2 MySQL mode
        @JvmStatic
        fun databases() = TestDB.ALL_MYSQL_LIKE

        // PostgreSQL + H2 PostgreSQL mode
        @JvmStatic
        fun postgresDatabases() = TestDB.ALL_POSTGRES_LIKE
    }

    @ParameterizedTest
    @MethodSource("databases")
    fun `mysql compatible test`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            // test on MySQL-compatible databases
        }
    }
}
```

### Flow-Based Streaming Query

```kotlin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals

@ParameterizedTest
@MethodSource(ENABLE_DIALECTS_METHOD)
fun `should stream query results`(testDB: TestDB) = runTest {
    withTables(testDB, Users) {
        // insert multiple records
        repeat(100) { i ->
            Users.insert {
                it[name] = "User$i"
                it[email] = "user$i@example.com"
            }
        }

        // stream results with Flow
        val users = Users.selectAll().toList()
        assertEquals(100, users.size)
    }
}
```

## `TestDB` Configuration

```kotlin
object TestDBConfig {
    // true: use Testcontainers (default)
    // false: use locally installed DB servers directly
    var useTestcontainers = true

    // true: use only in-memory H2 for fast local tests
    // false: use H2 + PostgreSQL + MySQL V8 (requires Testcontainers)
    var useFastDB = false
}
```

The module default is `useFastDB = false`, so `enabledDialects()` returns H2,
PostgreSQL, and MySQL 8.0 unless the environment narrows the matrix. Set
`useFastDB = true` for an H2-only fast path. `EXPOSED_TEST_DB=POSTGRESQL` or
`EXPOSED_TEST_DB=MYSQL_V8` narrows CI runs to H2 plus one real driver. Docker is
needed for Testcontainers-backed databases.

## Test Schema and Data

### Shared Table Schemas

| File                             | Description              |
|----------------------------------|--------------------------|
| `shared/entities/BoardSchema.kt` | `Board` table            |
| `shared/mapping/PersonSchema.kt` | `Person` mapping table   |
| `shared/mapping/OrderSchema.kt`  | `Order` mapping table    |
| `shared/samples/BankSchema.kt`   | bank account table       |
| `shared/samples/UserCities.kt`   | user-city relation table |
| `shared/dml/DMLTestData.kt`      | DML test data            |

## Testcontainers Configuration

```kotlin
import io.bluetape4k.exposed.r2dbc.tests.Containers

// MariaDB container
Containers.MariaDB

// MySQL 8.0 container
Containers.MySQL8

// PostgreSQL container
Containers.Postgres
```

## JDBC vs R2DBC Test Comparison

| Feature         | exposed-tests              | exposed-r2dbc-tests      |
|-----------------|----------------------------|--------------------------|
| API             | JDBC                       | R2DBC                    |
| Execution model | synchronous / asynchronous | coroutine-native         |
| `withDb`        | `withDb`                   | `suspend fun withDb`     |
| `withTables`    | `withTables`               | `suspend fun withTables` |
| Transaction     | `JdbcTransaction`          | `R2dbcTransaction`       |

## Feature Details

| File                          | Description                                                                                                                                                           |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AbstractExposedR2dbcTest.kt` | base class for R2DBC tests                                                                                                                                            |
| `TestDB.kt`                   | definitions of supported R2DBC databases                                                                                                                              |
| `TestDBConfig.kt`             | test-environment settings (`useTestcontainers`, `useFastDB`)                                                                                                          |
| `Containers.kt`               | Testcontainers management                                                                                                                                             |
| `withDb.kt`                   | R2DBC DB connection utility                                                                                                                                           |
| `withTables.kt`               | R2DBC table utility                                                                                                                                                   |
| `withAutoCommit.kt`           | AutoCommit mode utility                                                                                                                                               |
| `withSchemas.kt`              | schema utility                                                                                                                                                        |
| `Assertions.kt`               | assertion helpers for tests (`assertTrue`, `assertFalse`, `assertEquals`, `assertNotEquals`, `assertFailAndRollback`, `expectException`, `expectExceptionSuspending`) |
| `TestSupports.kt`             | test helper utilities (`inProperCase`, `currentDialectTest`, `insertAndSuspending`, and more)                                                                         |

## Example R2DBC Connection Strings

```kotlin
// H2
"r2dbc:h2:mem:///regular;DB_CLOSE_DELAY=-1;"

// H2 MySQL mode
"r2dbc:h2:mem:///mysql;DB_CLOSE_DELAY=-1;MODE=MySQL;"

// MariaDB
"r2dbc:mariadb://user:pass@host:3306/database"

// MySQL
"r2dbc:mysql://user:pass@host:3306/database"

// PostgreSQL
"r2dbc:postgresql://user:pass@host:5432/database"
```

## Notes

- R2DBC helpers are `suspend` functions and should normally be called from `runTest`
- MySQL 5.7 is excluded due to R2DBC driver compatibility issues
- Docker is required when using Testcontainers
- Flow-based streaming queries are supported
