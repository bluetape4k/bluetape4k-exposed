# bluetape4k-exposed

[![CI](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[한국어](README.ko.md)

Kotlin extensions for [JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM — providing Repository patterns, cache integrations, JSON column serialization, encryption, and Spring Boot auto-configuration.

---

## Features

- **Repository Pattern** — Type-safe JDBC and R2DBC (coroutine) repository abstractions built on Exposed DSL
- **Cache Integrations** — Caffeine (local), Lettuce and Redisson (distributed Redis) cache backends
- **JSON Columns** — Jackson 2.x, Jackson 3.x, and Fastjson2 column serializers
- **Encryption** — Google Tink-based encrypted columns
- **Database-specific Extensions** — PostgreSQL and MySQL 8 dialect helpers
- **Spring Boot** — Spring Boot 4.x auto-configuration (JDBC + R2DBC)
- **Metrics** — Micrometer integration via `exposed-measured`

## Modules

| Module | Description |
|--------|-------------|
| `bluetape4k-exposed-core` | Core Column types, DSL helpers, extension functions |
| `bluetape4k-exposed-dao` | DAO Entity extensions, lifecycle hooks |
| `bluetape4k-exposed-jdbc` | JDBC-based Repository pattern, transaction DSL |
| `bluetape4k-exposed-r2dbc` | R2DBC coroutine-native Repository, suspend transactions |
| `bluetape4k-exposed-jdbc-tests` | JDBC integration test fixtures |
| `bluetape4k-exposed-r2dbc-tests` | R2DBC integration test fixtures |
| `bluetape4k-exposed-cache` | Cache abstraction interfaces |
| `bluetape4k-exposed-jdbc-caffeine` | JDBC + Caffeine local cache |
| `bluetape4k-exposed-jdbc-lettuce` | JDBC + Lettuce Redis distributed cache |
| `bluetape4k-exposed-jdbc-redisson` | JDBC + Redisson Redis distributed cache |
| `bluetape4k-exposed-r2dbc-caffeine` | R2DBC + Caffeine local cache |
| `bluetape4k-exposed-r2dbc-lettuce` | R2DBC + Lettuce Redis distributed cache |
| `bluetape4k-exposed-r2dbc-redisson` | R2DBC + Redisson Redis distributed cache |
| `bluetape4k-exposed-jackson2` | Jackson 2.x JSON column serialization |
| `bluetape4k-exposed-jackson3` | Jackson 3.x JSON column serialization |
| `bluetape4k-exposed-fastjson2` | Fastjson2 JSON column serialization |
| `bluetape4k-exposed-tink` | Google Tink encrypted columns |
| `bluetape4k-exposed-measured` | Micrometer metrics integration |
| `bluetape4k-exposed-postgresql` | PostgreSQL dialect extensions |
| `bluetape4k-exposed-mysql8` | MySQL 8 dialect extensions |
| `bluetape4k-spring-boot-exposed-jdbc` | Spring Boot 4.x JDBC auto-configuration |
| `bluetape4k-spring-boot-exposed-r2dbc` | Spring Boot 4.x R2DBC auto-configuration |
| `bluetape4k-spring-boot-batch-exposed` | Spring Boot 4.x batch integration |

## Quick Start

### Gradle

```kotlin
dependencies {
    // Core
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc:1.8.0-SNAPSHOT")
    // R2DBC (coroutines)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc:1.8.0-SNAPSHOT")
    // Redis cache
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce:1.8.0-SNAPSHOT")
    // Jackson JSON columns
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2:1.8.0-SNAPSHOT")
    // Spring Boot auto-configuration
    implementation("io.github.bluetape4k.exposed:bluetape4k-spring-boot-exposed-jdbc:1.8.0-SNAPSHOT")
}
```

Snapshots are published to Maven Central Snapshots. Add the repository:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    mavenCentral()
}
```

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
//     url: jdbc:postgresql://localhost:5432/mydb
```

## Requirements

- JVM 21+
- Kotlin 2.3+
- JetBrains Exposed 0.60+

## License

MIT — see [LICENSE](LICENSE).
