# bluetape4k-exposed

[![CI](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md)

[JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM을 위한 Kotlin 확장 라이브러리. Repository 패턴, 캐시 통합, JSON Column 직렬화, 암호화, Spring Boot 자동 설정을 제공합니다.

---

## 주요 기능

- **Repository 패턴** — Exposed DSL 기반 타입 안전한 JDBC 및 R2DBC(코루틴) Repository 추상화
- **캐시 통합** — Caffeine(로컬), Lettuce/Redisson(분산 Redis) 캐시 백엔드
- **JSON Column** — Jackson 2.x, Jackson 3.x, Fastjson2 Column 직렬화
- **암호화** — Google Tink 기반 암호화 Column
- **DB 특화 확장** — PostgreSQL 및 MySQL 8 다이얼렉트 헬퍼
- **Spring Boot** — Spring Boot 4.x 자동 설정 (JDBC + R2DBC)
- **메트릭** — `exposed-measured`를 통한 Micrometer 통합

## 모듈 목록

| 모듈 | 설명 |
|------|------|
| `bluetape4k-exposed-core` | 핵심 Column 타입, DSL 헬퍼, 확장 함수 |
| `bluetape4k-exposed-dao` | DAO Entity 확장, 라이프사이클 훅 |
| `bluetape4k-exposed-jdbc` | JDBC 기반 Repository 패턴, 트랜잭션 DSL |
| `bluetape4k-exposed-r2dbc` | R2DBC 코루틴 네이티브 Repository, suspend 트랜잭션 |
| `bluetape4k-exposed-jdbc-tests` | JDBC 통합 테스트 픽스처 |
| `bluetape4k-exposed-r2dbc-tests` | R2DBC 통합 테스트 픽스처 |
| `bluetape4k-exposed-cache` | 캐시 추상화 인터페이스 |
| `bluetape4k-exposed-jdbc-caffeine` | JDBC + Caffeine 로컬 캐시 |
| `bluetape4k-exposed-jdbc-lettuce` | JDBC + Lettuce Redis 분산 캐시 |
| `bluetape4k-exposed-jdbc-redisson` | JDBC + Redisson Redis 분산 캐시 |
| `bluetape4k-exposed-r2dbc-caffeine` | R2DBC + Caffeine 로컬 캐시 |
| `bluetape4k-exposed-r2dbc-lettuce` | R2DBC + Lettuce Redis 분산 캐시 |
| `bluetape4k-exposed-r2dbc-redisson` | R2DBC + Redisson Redis 분산 캐시 |
| `bluetape4k-exposed-jackson2` | Jackson 2.x JSON Column 직렬화 |
| `bluetape4k-exposed-jackson3` | Jackson 3.x JSON Column 직렬화 |
| `bluetape4k-exposed-fastjson2` | Fastjson2 JSON Column 직렬화 |
| `bluetape4k-exposed-tink` | Google Tink 암호화 Column |
| `bluetape4k-exposed-measured` | Micrometer 메트릭 통합 |
| `bluetape4k-exposed-postgresql` | PostgreSQL 다이얼렉트 확장 |
| `bluetape4k-exposed-mysql8` | MySQL 8 다이얼렉트 확장 |
| `bluetape4k-spring-boot-exposed-jdbc` | Spring Boot 4.x JDBC 자동 설정 |
| `bluetape4k-spring-boot-exposed-r2dbc` | Spring Boot 4.x R2DBC 자동 설정 |
| `bluetape4k-spring-boot-batch-exposed` | Spring Boot 4.x Batch 통합 |

## 빠른 시작

### Gradle 의존성 추가

```kotlin
dependencies {
    // 핵심 JDBC
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc:1.8.0-SNAPSHOT")
    // R2DBC (코루틴)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc:1.8.0-SNAPSHOT")
    // Redis 캐시 (Lettuce)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce:1.8.0-SNAPSHOT")
    // Jackson JSON Column
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2:1.8.0-SNAPSHOT")
    // Spring Boot 자동 설정
    implementation("io.github.bluetape4k.exposed:bluetape4k-spring-boot-exposed-jdbc:1.8.0-SNAPSHOT")
}
```

스냅샷은 Maven Central Snapshots에 배포됩니다:

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
}

// 사용 예
val db = Database.connect(dataSource)
SchemaUtils.create(UserTable)

val repo = UserRepository(db)
val id = repo.create("홍길동", "gildong@example.com")
val user = repo.findById(id)
```

### R2DBC 코루틴 Repository

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
```

### JSON Column (Jackson)

```kotlin
import io.bluetape4k.exposed.jackson2.json

data class Address(val street: String, val city: String)

object ContactTable : LongIdTable("contacts") {
    val name = varchar("name", 255)
    val address = json<Address>("address")  // JSON 텍스트로 저장
}
```

### 암호화 Column (Tink)

```kotlin
import io.bluetape4k.exposed.tink.encrypted

object SecretTable : LongIdTable("secrets") {
    val sensitiveData = encrypted("data")  // AES-GCM으로 암호화 저장
}
```

### Spring Boot 자동 설정

```kotlin
@SpringBootApplication
@EnableExposedJdbc
class MyApplication

// application.yml
// spring:
//   datasource:
//     url: jdbc:postgresql://localhost:5432/mydb
```

## 요구사항

- JVM 21+
- Kotlin 2.3+
- JetBrains Exposed 0.60+

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
