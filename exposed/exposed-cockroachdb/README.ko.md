# Module exposed-cockroachdb

[English](./README.md) | 한국어

JetBrains Exposed ORM을 위한 최소 CockroachDB JDBC 통합 모듈입니다. 이 모듈은
`bluetape4k-exposed`에서 CockroachDB를 지원하기 위한 첫 경로로 PostgreSQL wire
JDBC 연결과 실제 Testcontainers 기반 smoke test를 검증합니다.

## 범위

`exposed-cockroachdb`는 다음을 제공합니다:

- **CockroachDatabase**:
  `jdbc:postgresql://<host>:<sql_port>/<database>` CockroachDB URL 연결 팩토리
- **Testcontainers smoke coverage**:
  `bluetape4k-testcontainers`의 `CockroachServer`를 사용하는 단일 노드 CockroachDB 테스트

CockroachDB는 PostgreSQL wire protocol과 호환되지만 PostgreSQL과 동일하지는
않습니다. 이 모듈은 의도적으로 custom Exposed dialect를 등록하지 않으며 넓은
PostgreSQL DDL parity를 주장하지 않습니다.

## 범위 밖

- Custom CockroachDB dialect 등록
- PostgreSQL compatibility 및 DDL boundary matrix
- Serializable transaction retry helper API
- R2DBC 지원

위 항목은 parent epic의 CockroachDB follow-up issue에서 다룹니다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb:${version}")
}
```

이 모듈은 CockroachDB의 PostgreSQL wire protocol을 사용하므로 PostgreSQL JDBC
driver를 사용합니다:

```kotlin
implementation("org.postgresql:postgresql")
```

## 기본 사용법

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

## Testcontainers 사용법

```kotlin
import io.bluetape4k.testcontainers.database.CockroachServer

val cockroach = CockroachServer.Launcher.cockroach
val db = CockroachDatabase.connect(
    jdbcUrl = cockroach.url,
    user = cockroach.username ?: CockroachServer.USERNAME,
    password = cockroach.password ?: CockroachServer.PASSWORD,
)
```

## 검증

```bash
./gradlew :bluetape4k-exposed-cockroachdb:test --no-configuration-cache --no-daemon
```
