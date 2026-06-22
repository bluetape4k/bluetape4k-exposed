# Module exposed-trino

[English](./README.md) | 한국어

JetBrains Exposed ORM과 Trino JDBC를 통합하는 모듈입니다. PostgreSQL Dialect 기반으로 Trino에서 Exposed DSL을 사용하고, 코루틴 기반 suspend 트랜잭션과 Flow 쿼리를 제공합니다.

## 개요

`exposed-trino`는 다음을 제공합니다:

- **TrinoDialect**: `PostgreSQLDialect` 상속, Exposed ORM과 Trino 호환 (ALTER COLUMN TYPE / multiple generated keys 비활성화)
- **TrinoDialectMetadata**: `getImportedKeys` 미지원 우회 (FK 제약 캐싱 no-op)
- **TrinoConnectionWrapper**: Trino JDBC `prepareStatement` 오버로드 호환 래퍼, 실제 JDBC 연결을 `autoCommit=true`로 고정
- **TrinoDatabase**: JDBC URL 또는 호스트/포트/카탈로그/스키마 기반 연결 팩토리 (`object`)
- **suspendTransaction**: `Dispatchers.IO`에서 블로킹 JDBC를 suspend 함수로 래핑
- **queryFlow**: 트랜잭션 안에서 결과를 materialize 한 뒤 `Flow<T>`로 emit
- **trinoBatchInsert**: 커넥터 의존 Trino 쓰기를 위한 Exposed `batchInsert` chunk wrapper
- **TrinoTable**: Trino DDL에서 unsupported PRIMARY KEY / NULL 구문을 제거하는 테이블 베이스 클래스
- **@TrinoUnsupported**: Trino 미지원 기능 마커 어노테이션

## 의존성 추가

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-trino:${bluetape4kVersion}")
    // 또는 Maven 좌표
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-trino:${version}")
}
```

## Trino JDBC 호환 경계

![Trino JDBC compatibility boundary diagram](../../docs/images/readme-diagrams/exposed-exposed-trino-diagram-01.png)

### Flow materialization 계약

![Trino Flow materialization contract diagram](../../docs/images/readme-diagrams/exposed-exposed-trino-flow-02.png)

## 기본 사용법

### 1. Trino 데이터베이스 연결

```kotlin
import io.bluetape4k.exposed.trino.TrinoDatabase

// 호스트/포트/카탈로그/스키마로 연결
val db = TrinoDatabase.connect(
    host = "trino-coordinator",
    port = 8080,
    catalog = "hive",
    schema = "default",
    user = "analyst",
)

// 또는 JDBC URL 직접 지정
val db = TrinoDatabase.connect(
    jdbcUrl = "jdbc:trino://localhost:8080/memory/default",
    user = "trino",
)

// 또는 DataSource 기반 연결 (HikariCP 등 커넥션 풀)
val hikariConfig = HikariConfig().apply {
    jdbcUrl = "jdbc:trino://trino-coordinator:8080/hive/default"
    username = "analyst"
    driverClassName = "io.trino.jdbc.TrinoDriver"
    maximumPoolSize = 10
}
val db = TrinoDatabase.connect(HikariDataSource(hikariConfig))
```

> **주의:** `connect(dataSource)`는 풀에서 획득한 각 커넥션을 `TrinoConnectionWrapper`로 감싸
> `autoCommit=true`를 강제합니다. 래퍼 생성 실패 시 원본 커넥션을 자동으로 닫아 leak을 방지합니다.

### 1.1 JDBC 성능 및 Session Option

대용량 결과셋이나 optimizer 민감 workload에서는 `TrinoConnectionOptions`로
Trino JDBC property를 타입 안전하게 전달할 수 있습니다. 기존 `connect`
오버로드는 그대로 유지됩니다.

```kotlin
import io.bluetape4k.exposed.trino.TrinoConnectionOptions
import io.bluetape4k.exposed.trino.TrinoDatabase

val db = TrinoDatabase.connect(
    jdbcUrl = "jdbc:trino://trino-coordinator:8080/hive/default",
    user = "analyst",
    options = TrinoConnectionOptions(
        explicitPrepare = false,
        encoding = "json+zstd",
        validateConnection = true,
        source = "bluetape4k-exposed",
        clientTags = listOf("exposed", "analytics"),
        sessionProperties = mapOf("join_distribution_type" to "AUTOMATIC"),
    )
)
```

실제 pushdown 지원은 connector별로 다릅니다. 대상 catalog에서 `EXPLAIN`으로
predicate, projection, aggregation, top-N, limit pushdown 같은 안정적인 신호만
확인하고, 테스트에서는 전체 plan snapshot 비교를 피하세요.

### 2. 동기 트랜잭션

```kotlin
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

transaction(db) {
    SchemaUtils.create(Events)
    Events.insert {
        it[eventId] = 1L
        it[region] = "kr"
    }
    val rows = Events.selectAll().toList()
}
```

> DDL을 Exposed에서 생성할 때는 일반 `Table` 대신 `TrinoTable` 상속을 권장합니다.
> Trino Memory 커넥터는 PRIMARY KEY / CONSTRAINT 구문을 지원하지 않으므로 기본 `Table`의 DDL을 그대로 쓰면 실패할 수 있습니다.

### 3. suspend 트랜잭션

```kotlin
import io.bluetape4k.exposed.trino.suspendTransaction

val rows = suspendTransaction(db) {
    Events.selectAll().where { Events.region eq "kr" }.toList()
}
```

Virtual Thread 디스패처와 함께 사용:

```kotlin
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

val vtDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
val rows = suspendTransaction(db, vtDispatcher) {
    Events.selectAll().toList()
}
```

### 4. Flow 쿼리

```kotlin
import io.bluetape4k.exposed.trino.queryFlow

queryFlow(db) {
    Events.selectAll().where { Events.region eq "kr" }
}.collect { row ->
    println(row[Events.eventId])
}
```

> `queryFlow`는 JDBC `ResultSet` 수명과 Exposed 트랜잭션 경계를 안전하게 유지하기 위해
> 트랜잭션 안에서 결과를 `List`로 materialize 한 뒤 emit 합니다.
> API는 `Flow`이지만, 진정한 row-by-row 스트리밍 커서는 아닙니다.
> 매우 큰 결과셋은 페이지네이션 또는 전용 배치 전략을 별도로 고려해야 합니다.

### 5. 페이지 단위 Flow 쿼리

대용량 결과셋을 하나의 트랜잭션에서 모두 materialize 하지 않으려면
`pagedQueryFlow`를 사용합니다. 각 page는 별도 Exposed 트랜잭션 안에서
로드되고, 트랜잭션이 닫힌 뒤 emit 됩니다.

```kotlin
import io.bluetape4k.exposed.trino.TrinoPagedQueryOptions
import io.bluetape4k.exposed.trino.pagedQueryFlow
import org.jetbrains.exposed.v1.core.SortOrder

pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 500)) { limit, offset ->
    Events.selectAll()
        .where { Events.region eq "kr" }
        .orderBy(Events.eventId to SortOrder.ASC)
        .limit(limit)
        .offset(offset)
}.collect { row ->
    println(row[Events.eventId])
}
```

대용량 결과셋 가이드:

- `queryFlow`는 기존의 안전한 materialize 후 emit 동작을 유지합니다.
- `pagedQueryFlow`는 대용량 JDBC 결과셋에 권장되는 API입니다.
- `limit`, `offset`과 함께 항상 결정적인 `orderBy`를 사용하세요.
- 블록은 전달받은 `limit` 이하의 row만 반환해야 합니다.
- `pageSize`는 애플리케이션 쪽 materialize 크기를 제한합니다. Trino JDBC 처리량 튜닝은
  대용량 결과 전송을 위한 Trino spooling protocol을 포함해 드라이버/클러스터 프로토콜 영역입니다.
- 취소되면 다음 page 요청을 시작하지 않습니다. 진행 중인 page 트랜잭션은 닫힌 뒤 컬렉션이 중단됩니다.
- 진정한 row-by-row 커서 스트리밍은 `ResultSet` 수명을 트랜잭션 밖 Flow 컬렉션과 결합하므로 아직 노출하지 않습니다.

### 6. Batch 쓰기 헬퍼

대상 Trino catalog가 `INSERT`를 지원하고, Exposed `batchInsert`에 명시적인
client-side chunking을 적용하려면 `trinoBatchInsert`를 사용합니다.

```kotlin
import io.bluetape4k.exposed.trino.TrinoBatchInsertOptions
import io.bluetape4k.exposed.trino.trinoBatchInsert

transaction(db) {
    Events.trinoBatchInsert(events, TrinoBatchInsertOptions(chunkSize = 500)) { event ->
        this[Events.eventId] = event.id
        this[Events.eventName] = event.name
        this[Events.region] = event.region
    }
}
```

Batch 쓰기 가이드:

- Trino는 `INSERT INTO ... query`와 multi-row `VALUES` 문법을 지원하지만,
  실제 쓰기 지원 여부는 커넥터별로 다릅니다.
- `trinoBatchInsert`는 Exposed JDBC `batchInsert`를 제한된 chunk로 감싼
  helper이며, Trino connector 전용 bulk-loader protocol은 아닙니다.
- generated keys는 안정적인 Trino 쓰기 계약이 아니므로
  `shouldReturnGeneratedValues` 기본값은 `false`입니다.
- 후속 chunk가 실패하면 앞선 chunk는 이미 반영되어 있을 수 있습니다. 이 모듈은
  Trino 쓰기에 대해 rollback 또는 all-or-nothing 의미를 보장하지 않습니다.
- JDBC connector의 `write.batch-size` 같은 connector-side write tuning은
  Trino catalog 설정 영역입니다.

## ⚠️ 트랜잭션 동작 주의사항

Trino는 ACID 트랜잭션을 지원하지 않습니다. `transaction {}` 블록을 사용할 수 있지만, 아래 표를 참고하여 동작 차이를 반드시 인지하세요.

| 동작                 | Trino            | 일반 RDBMS     |
|--------------------|------------------|--------------|
| 원자성                | ❌ 미보장            | ✅ 보장         |
| Rollback           | ❌ no-op          | ✅ 동작         |
| Nested transaction | ⚠️ 호출 허용, 원자성 없음 | ✅ 지원         |
| Savepoint          | ❌ 미지원            | ✅ 지원         |
| autocommit 모드      | 항상 ON (변경 불가)    | ON/OFF 전환 가능 |

**실질적 영향**:

- `transaction {}` 블록 내 다중 DML 실행 시, 중간 실패가 발생하면 앞선 DML은 **롤백되지 않습니다**.
- 쓰기 블록에서는 부분 반영(partial write) 위험을 항상 고려해야 합니다.
- 읽기 전용 쿼리(`SELECT`)는 일반적으로 안전하게 사용 가능합니다.

## 지원/미지원 기능

### Trino 일반 계약 (범용)

| 기능                        | 지원 여부     | 비고                                       |
|---------------------------|-----------|------------------------------------------|
| SELECT / JOIN / 집계        | ✅         | 표준 SQL                                   |
| INSERT / batch INSERT     | ⚠️ 커넥터 의존 | `trinoBatchInsert`는 Memory 기준 검증, 실제 지원 범위는 커넥터가 결정 |
| UPDATE / DELETE           | ⚠️ 커넥터 의존 | 모듈은 Exposed DSL을 제공하지만 실제 지원 범위는 커넥터가 결정 |
| CREATE TABLE / DROP TABLE | ⚠️ 커넥터 의존 | 테스트는 Memory 커넥터 기준으로 검증                  |
| DDL via SchemaUtils       | ⚠️ 커넥터 의존 | `TrinoTable` 사용 권장                       |
| 윈도우 함수 (GROUPS 모드)        | ✅         | `supportsWindowFrameGroupsMode = true`   |
| 트랜잭션 원자성                  | ❌         | autocommit 전용                            |
| Rollback                  | ❌         | no-op                                    |
| Savepoint                 | ❌         | 미지원                                      |
| ALTER COLUMN TYPE         | ❌         | `supportsColumnTypeChange = false`       |
| Multiple generated keys   | ❌         | `supportsMultipleGeneratedKeys = false`  |
| FK 제약 메타데이터 조회            | ❌         | `getImportedKeys` 미지원 → no-op            |

### Memory 커넥터 테스트 범위 (테스트 환경 한정)

Testcontainers를 통한 Trino Memory 커넥터 환경에서 검증된 기능입니다.

| 기능                        | 검증 여부 | 비고                        |
|---------------------------|-------|---------------------------|
| CREATE/DROP TABLE         | ✅     | Memory 커넥터                |
| INSERT 단건/다건              | ✅     | Memory 커넥터                |
| trinoBatchInsert          | ✅     | Exposed batchInsert chunk wrapper |
| SELECT / WHERE / ORDER BY | ✅     |                           |
| COUNT / 집계 함수             | ✅     |                           |
| suspendTransaction        | ✅     | Dispatchers.IO            |
| queryFlow                 | ✅     | materialize 후 emit        |
| pagedQueryFlow            | ✅     | page 단위 materialize 후 emit |
| TrinoConnectionWrapper 호환 | ✅     | prepareStatement 오버로드     |
| JDBC 드라이버 자동 등록           | ✅     | TrinoDatabase 접근 시 init{} |

## 주요 파일/클래스 목록

| 파일                                | 설명                                                            |
|-----------------------------------|---------------------------------------------------------------|
| `TrinoDatabase.kt`                | 연결 팩토리 (호스트/포트/카탈로그 또는 JDBC URL)                              |
| `TrinoConnectionWrapper.kt`       | Trino JDBC 호환 Connection 래퍼 (실제 JDBC 연결을 autocommit=true로 고정) |
| `TrinoExtensions.kt`              | `suspendTransaction`, `queryFlow` 확장 함수                       |
| `TrinoTable.kt`                   | Trino unsupported DDL 구문(PRIMARY KEY, 명시적 NULL) 제거            |
| `TrinoUnsupported.kt`             | Trino 미지원 기능 마커 어노테이션                                         |
| `dialect/TrinoDialect.kt`         | PostgreSQLDialect 상속 Trino 다이얼렉트                              |
| `dialect/TrinoDialectMetadata.kt` | FK 제약 캐싱 no-op 구현                                             |

## 테스트

```bash
./gradlew :bluetape4k-exposed-trino:test
```

핵심 회귀 테스트 예:

```bash
./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoConnectionWrapperTest"
./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoDatabaseTest"
./gradlew :bluetape4k-exposed-trino:test --tests "io.bluetape4k.exposed.trino.TrinoTransactionAtomicityTest"
```

## Phase 2 로드맵

다음 기능은 이후 릴리즈에서 추가될 예정입니다.

| 기능                       | 설명                                             |
|--------------------------|------------------------------------------------|
| `exposed-bigquery-trino` | BigQuery → Trino → Exposed 파이프라인 통합 모듈         |
| 커넥터별 bulk loader          | 커넥터가 제공하는 전용 non-Exposed bulk write protocol |
| 결과셋 스트리밍                 | 안전한 커서 수명 계약이 생길 때까지 진정한 row-by-row 스트리밍 보류 |

## 참고

- [Trino](https://trino.io/)
- [Trino JDBC Driver](https://trino.io/docs/current/client/jdbc.html)
- [Trino INSERT syntax](https://trino.io/docs/current/sql/insert.html)
- [Trino SQL statement support](https://trino.io/docs/current/language/sql-support.html)
- [JetBrains Exposed](https://github.com/JetBrains/Exposed)
- [exposed-duckdb](../exposed-duckdb/README.ko.md) — 유사한 in-process 분석 DB 통합 참고
