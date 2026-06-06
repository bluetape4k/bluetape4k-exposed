# bluetape4k-exposed

[![CI](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-exposed/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) | 한국어

![bluetape4k Exposed 작업대 일러스트](./docs/assets/exposed-workbench.png)

[JetBrains Exposed](https://github.com/JetBrains/Exposed) ORM을 위한 Kotlin 확장 라이브러리. Repository 패턴, 캐시 통합, JSON Column 직렬화, 암호화, Spring Boot 자동 설정을 제공합니다.

---

## 프로젝트 목적

`bluetape4k-exposed`는 JetBrains Exposed를 운영 환경에 맞는 Kotlin 데이터 툴킷으로 확장합니다.
JDBC/R2DBC Repository 패턴, cache-backed read path, JSON/암호화 Column, 데이터베이스별
dialect 확장, Spring Boot 4 자동 설정을 Exposed DSL 스타일 안에서 제공합니다.

## 주요 기능

- **Repository 패턴** — Exposed DSL 기반 타입 안전한 JDBC 및 R2DBC(코루틴) Repository 추상화
- **CTE Query DSL** — PostgreSQL/MySQL `WITH`, `WITH RECURSIVE` SELECT를 위한 JDBC/R2DBC 헬퍼
- **캐시 통합** — Caffeine(로컬), Lettuce/Redisson(분산 Redis) 캐시 백엔드
- **JSON Column** — Jackson 2.x, Jackson 3.x, Fastjson2 Column 직렬화
- **암호화** — Google Tink 기반 암호화 Column
- **DB 특화 확장** — PostgreSQL, MySQL 8, BigQuery, ClickHouse, Trino, DuckDB, Timefold persistence 헬퍼
- **Spring Boot** — Spring Boot 4.x 자동 설정 (JDBC, R2DBC, Batch, Spring Modulith JDBC 이벤트 발행 통합)
- **측정 단위 Column** — `bluetape4k-measured` 단위를 위한 Exposed Custom ColumnType

<!-- README_VISUAL_OVERVIEW:START -->
## Overview Diagram

![Bluetape4k Exposed overview diagram](docs/images/readme-diagrams/root-readme-overview-01.png)

## Module Relationship Diagram

![Bluetape4k Exposed module relationship diagram](docs/images/readme-diagrams/root-readme-module-relationships-01.png)
<!-- README_VISUAL_OVERVIEW:END -->

## 아키텍처

![bluetape4k-exposed architecture](docs/assets/exposed-architecture.png)

## 모듈 목록

| 모듈 | 설명 |
|------|------|
| `exposed-core` | 핵심 Column 타입, DSL 헬퍼, 확장 함수 |
| `exposed-dao` | DAO Entity 확장, 라이프사이클 훅 |
| `exposed-jdbc` | JDBC 기반 Repository 패턴, 트랜잭션 DSL |
| `exposed-r2dbc` | R2DBC 코루틴 네이티브 Repository, suspend 트랜잭션 |
| `exposed-jdbc-tests` | JDBC 통합 테스트 픽스처 |
| `exposed-r2dbc-tests` | R2DBC 통합 테스트 픽스처 |
| `exposed-cache` | 캐시 추상화 인터페이스 |
| `exposed-jdbc-caffeine` | JDBC + Caffeine 로컬 캐시 |
| `exposed-jdbc-lettuce` | JDBC + Lettuce Redis 분산 캐시 |
| `exposed-jdbc-redisson` | JDBC + Redisson Redis 분산 캐시 |
| `exposed-r2dbc-caffeine` | R2DBC + Caffeine 로컬 캐시 |
| `exposed-r2dbc-lettuce` | R2DBC + Lettuce Redis 분산 캐시 |
| `exposed-r2dbc-redisson` | R2DBC + Redisson Redis 분산 캐시 |
| `exposed-jackson2` | Jackson 2.x JSON Column 직렬화 |
| `exposed-jackson3` | Jackson 3.x JSON Column 직렬화 |
| `exposed-fastjson2` | Fastjson2 JSON Column 직렬화 |
| `exposed-tink` | Google Tink 암호화 Column |
| `exposed-measured` | 측정 단위용 Custom ColumnType 매핑 |
| `exposed-postgresql` | PostgreSQL 다이얼렉트 확장 |
| `exposed-mysql8` | MySQL 8 다이얼렉트 확장 |
| `exposed-bigquery` | BigQuery connector 지원 |
| `exposed-clickhouse` | ClickHouse connector 지원 |
| `exposed-trino` | Trino connector 지원 |
| `exposed-starrocks` | StarRocks local-first OLAP connector 지원 |
| `exposed-duckdb` | DuckDB embedded analytics 지원 |
| `exposed-timefold-solver-persistence` | Timefold Solver persistence 통합 |
| `exposed-spring-boot-jdbc` | Spring Boot 4.x JDBC 자동 설정 |
| `exposed-spring-boot-r2dbc` | Spring Boot 4.x R2DBC 자동 설정 |
| `exposed-spring-boot-batch` | Spring Boot 4.x Batch 통합 |
| `exposed-spring-modulith` | Exposed 기반 Spring Modulith JDBC 이벤트 발행 Repository |

## 빠른 시작

### Gradle 의존성 추가

```kotlin
dependencies {
    // 핵심 JDBC
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc:1.10.0")
    // R2DBC (코루틴)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc:1.10.0")
    // Redis 캐시 (Lettuce)
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-lettuce:1.10.0")
    // Jackson JSON Column
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-jackson2:1.10.0")
    // Spring Boot 자동 설정
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.10.0")
    // Exposed 기반 Spring Modulith JDBC 이벤트 발행
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-modulith:1.10.0")
}
```

스냅샷은 Maven Central Snapshots에 배포됩니다:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    mavenCentral()
}
```

### Exposed Gradle Plugin

Exposed table 정의에서 migration script를 생성하는 application 또는 example
module에는 JetBrains 공식 Exposed Gradle plugin을 사용합니다. Plugin 버전은
프로젝트가 사용하는 JetBrains Exposed 버전과 맞춥니다. bluetape4k repo에서는
중앙 `bluetape4k-dependencies` catalog에서 plugin alias를 가져와, plugin 버전이
공유 `exposed` 호환 라인을 따르도록 합니다.

```kotlin
plugins {
    alias(bt4k.plugins.exposed.plugin)
}
```

이 plugin은 `generateMigrations` workflow를 추가하며, `exposed.migrations`
block에서 table package와 대상 database 또는 Testcontainers image를 설정합니다.
자세한 내용은
[Exposed Gradle plugin 문서](https://www.jetbrains.com/help/exposed/exposed-gradle-plugin.html)와
[Gradle Plugin Portal entry](https://plugins.gradle.org/plugin/org.jetbrains.exposed.plugin)를
참고하세요.

Demo migration 생성은 weekly 및 pull request smoke workflow에서 검증합니다:

```bash
./gradlew :exposed-spring-boot-jdbc-demo:generateMigrations --filename=V1__create_products.sql
./gradlew :exposed-spring-boot-r2dbc-demo:generateMigrations --filename=V1__create_webflux_products.sql
```

### Database 예제

| 예제 | 목적 | 검증 |
|------|------|------|
| `examples-exposed-clickhouse-oltp-olap` | PostgreSQL OLTP에서 ClickHouse OLAP으로 forwarding 후 집계 분석 | `./gradlew :examples-exposed-clickhouse-oltp-olap:test` |
| `examples-exposed-bigquery-dry-run` | Credential 없이 BigQuery REST dry-run과 query-job option 검증 | `./gradlew :examples-exposed-bigquery-dry-run:test` |

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

### Common Table Expression (PostgreSQL / MySQL)

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

### Spring Modulith 이벤트 발행

`exposed-spring-modulith`는 Exposed DSL과 동일한
Exposed `DataSource`/`springTransactionManager`를 사용하는 JDBC-only Spring
Modulith `EventPublicationRepository`를 제공합니다. artifact 이름은 공식
Spring Modulith 저장소 모듈처럼 보이지 않도록 `exposed-spring-modulith`
형태로 둡니다.

```yaml
bluetape4k:
  spring:
    modulith:
      exposed:
        completion-mode: update
        initialize-schema: false
```

운영 스키마는 Flyway 또는 Liquibase 사용을 권장합니다. `initialize-schema`는
테스트와 작은 로컬 애플리케이션 용도입니다.

## 요구사항

- JVM 21+
- Kotlin 2.3+
- JetBrains Exposed 1.3+

## 라이선스

MIT — [LICENSE](LICENSE) 참고.
