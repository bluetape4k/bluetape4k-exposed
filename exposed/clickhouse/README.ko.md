[English](./README.md) | 한국어

# exposed-clickhouse

ClickHouse JDBC를 위한 Kotlin/Exposed 다이얼렉트입니다. Exposed의 테이블/쿼리 문법은 유지하면서 ClickHouse 엔진 절, 전용 컬럼 타입, 집계/날짜 함수, 블로킹 JDBC 작업을 코루틴에서 다루기 위한 헬퍼를 제공합니다.

## 아키텍처

![ClickHouse Exposed integration architecture](../../docs/images/readme-diagrams/exposed-clickhouse-diagram-01.png)

## 주요 기능

- **ClickHouseDatabase** — `connect(host, port, database)` 및 `connect(jdbcUrl)` 팩토리 함수로 JDBC 연결 설정
- **ClickHouseTable** — `engine: ClickHouseEngine` 파라미터를 받는 추상 기본 클래스; DDL 정제 및 ENGINE 절 주입 처리
- **MergeTree 엔진 DSL** — `mergeTree {}`, `replacingMergeTree {}`, `summingMergeTree {}`, `aggregatingMergeTree {}`, `Log`, `TinyLog`, `Memory` 타입 안전 DSL
- **풍부한 컬럼 타입** — `String`, `FixedString(N)`, `Int8`–`Int64`, `UInt8`–`UInt64`, `Float32/64`, `DateTime64`, `Date32`, `LowCardinality(T)`, `Array(T)`, `Nullable(T)`
- **날짜 함수** — `toYYYYMM()`, `dateDiff(unit, start, end)`, `toStartOfInterval()`
- **집계 함수** — `argMax()`, `argMin()`, `quantile(level)()`, `uniq()`, `uniqExact()`
- **코루틴 헬퍼** — `suspendTransaction {}`은 블로킹 Exposed 작업을 IO 디스패처에서 실행하고, `queryFlow {}`는 트랜잭션 안에서 결과를 먼저 materialize한 뒤 `Flow<T>`로 emit합니다.

## Table 옵션 지원 정책

Exposed `1.5.0`의 generic `Table.options`·`storageParameters`는 dialect별 안전성을
검사하지 않습니다. 다음 정책은 CREATE TABLE 생성에만 적용됩니다.

| 테이블 / DB | options | storageParameters |
| --- | --- | --- |
| 기본 Table / H2 | upstream 동작 유지, 빈 옵션 기준 DDL 검증 | 빈 목록 기준 검증 |
| 기본 Table / PostgreSQL | `USING heap` 생성·실행 검증 | `FillFactorParameter(70)`·`AutovacuumEnabledParameter(false)` 보존·실행 검증 |
| StarRocksTable | 비어 있지 않으면 `IllegalArgumentException` | 비어 있지 않으면 `IllegalArgumentException` |
| ClickHouseTable | 비어 있지 않으면 `IllegalArgumentException` | 비어 있지 않으면 `IllegalArgumentException` |

custom table에서는 typed·raw·사용자 정의 옵션을 모두 SQL 생성 전에 거부하며,
옵션의 `toSQL()`이나 문자열 정제로 안전성을 추정하지 않습니다. 빈 문자열 옵션도
지원으로 취급하지 않습니다. 이는 원래 raw 옵션을 지정하던 호출자에게 동작 변경입니다.

StarRocks는 기존의 고정 `ENGINE=OLAP PROPERTIES ("replication_num" = "1")`을
유지합니다. ClickHouse는 기존 `engine` DSL의 `orderBy`·`partitionBy`·`setting`을
사용합니다. MySQL engine/charset과 PostgreSQL WITH 파라미터를 이 설정으로 자동 변환하지 않습니다.
검증된 generic 옵션 allowlist는 현재 없으며, 임의 raw SQL 우회 API도 추가하지 않습니다.

### 수동 migration

[Exposed Table 옵션 계약](https://github.com/JetBrains/Exposed/blob/1.5.0/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Table.kt)에
따르면 옵션 변경은 migration diff로 추적되지 않습니다. 기존 테이블 변경은 현재 schema와
원하는 설정을 비교한 뒤 DB별 ALTER/재생성 SQL을 별도 migration으로 작성하고,
테스트 DB에서 데이터 보존과 복구 절차를 검증한 후 적용해야 합니다. `SchemaUtils.create`
재호출이나 자동 diff만으로 옵션이 갱신된다고 가정하지 마세요.

## 빠른 시작

```kotlin
// 1. ClickHouse 연결
val database = ClickHouseDatabase.connect(
    host = "localhost",
    port = 8123,
    database = "analytics"
)

// 2. 테이블 정의
object EventsTable : ClickHouseTable("events") {
    val eventDate = date32("event_date")
    val userId    = chInt64("user_id")
    val eventType = lowCardinalityString("event_type")
    val value     = chFloat64("value")

    override val engine = mergeTree {
        orderBy(eventDate, userId)
        partitionBy(eventDate.toYYYYMM())
        setting("index_granularity", 8192)
    }
}

// 3. 스키마 생성
transaction(database) {
    SchemaUtils.create(EventsTable)
}

// 4. 배치 삽입
transaction(database) {
    EventsTable.batchInsert(events) { e ->
        this[EventsTable.eventDate]  = e.date
        this[EventsTable.userId]     = e.userId
        this[EventsTable.eventType]  = e.type
        this[EventsTable.value]      = e.value
    }
}

// 5. 코루틴 쿼리 (논블로킹)
val results = suspendTransaction(database) {
    EventsTable
        .select(EventsTable.userId, EventsTable.value.sum())
        .groupBy(EventsTable.userId)
        .toList()
}
```

## 컬럼 타입

| ClickHouse 타입 | Kotlin 타입 | 빌더 |
|----------------|-------------|------|
| String | String | `chString(name)` |
| FixedString(N) | String | `fixedString(name, n)` |
| Int8 | Byte | `chInt8(name)` |
| Int16 | Short | `chInt16(name)` |
| Int32 | Int | `chInt32(name)` |
| Int64 | Long | `chInt64(name)` |
| UInt8 | UByte | `chUByte(name)` |
| UInt16 | UShort | `chUShort(name)` |
| UInt32 | UInt | `chUInt(name)` |
| UInt64 | ULong | `chULong(name)` |
| UInt64 | BigInteger | `chUInt64BigInt(name)` |
| Float32 | Float | `chFloat32(name)` |
| Float64 | Double | `chFloat64(name)` |
| DateTime64(n) | Instant | `dateTime64(name, precision)` |
| Date32 | LocalDate | `date32(name)` |
| LowCardinality(T) | T | `lowCardinality(name, innerType)` / `lowCardinalityString(name)` |
| Array(T) | List\<T\> | `chArray(name, innerType)` |
| Nullable(T) | T? | `chNullable(name, innerType)` |

## 엔진 DSL

```kotlin
// MergeTree — ClickHouseTable override에서 typed expression 사용
val engine1 = mergeTree {
    orderBy(EventsTable.eventDate, EventsTable.userId)
    partitionBy(EventsTable.eventDate.toYYYYMM())
    primaryKey(EventsTable.eventDate)
    setting("index_granularity", 8192)
    setting("storage_policy", "hot")
}

// ReplacingMergeTree — 버전 컬럼을 이용한 중복 제거
val engine2 = replacingMergeTree {
    orderBy(EventsTable.userId)
    versionColumn(EventsTable.eventDate)
}

// SummingMergeTree — 사전 집계
val engine3 = summingMergeTree {
    orderBy(EventsTable.eventType, EventsTable.eventDate)
    sumColumns(EventsTable.value)
}

// AggregatingMergeTree — 구체화된 뷰용
val engine4 = aggregatingMergeTree {
    orderBy(EventsTable.userId)
    partitionBy(EventsTable.eventDate.toYYYYMM())
}

// ClickHouse 문법을 Exposed가 아직 모델링하지 못하는 경우에만 raw fragment를 사용합니다.
// 이 API들은 statement delimiter, comment, quote, clause-boundary token을 거부합니다.
val rawEngine = mergeTree {
    unsafeRawOrderBy("event_date", "user_id")
    unsafeRawPartitionBy("toYYYYMM(event_date)")
}

// 경량 엔진
val logEngine    = Log
val tinyLog      = TinyLog
val memoryEngine = Memory
```

## 날짜 및 집계 함수

```kotlin
transaction(database) {
    // toYYYYMM — 연월 정수 추출
    EventsTable
        .select(toYYYYMM(EventsTable.eventDate).alias("month"), EventsTable.value.sum())
        .groupBy(toYYYYMM(EventsTable.eventDate))
        .toList()

    // dateDiff — 두 날짜의 차이
    val diff = dateDiff("day", EventsTable.eventDate, EventsTable.eventDate)

    // toStartOfInterval — 인터벌 경계로 내림
    val monthly = toStartOfInterval(EventsTable.eventDate, "1 MONTH")

    // argMax — 다른 컬럼이 최대일 때의 값
    val latestValue = argMax(EventsTable.value, EventsTable.eventDate)

    // quantile — 근사 분위수
    val p95 = quantile(0.95)(EventsTable.value)

    // uniq — HyperLogLog 카디널리티 추정
    val approxUniq = uniq(EventsTable.userId)

    // uniqExact — 정확한 카디널리티
    val exactUniq = uniqExact(EventsTable.userId)
}
```

## DDL 라이프사이클

![ClickHouse DDL lifecycle](../../docs/images/readme-diagrams/exposed-clickhouse-flow-02.png)

## 주의사항

1. **트랜잭션 원자성 없음** — `ClickHouseConnectionWrapper`에서 `commit()`과 `rollback()`은 no-op입니다. 실패 전에 실행된 DML은 **롤백되지 않습니다**. 멱등 삽입이나 ReplacingMergeTree를 이용한 중복 제거로 설계하세요.

2. **`modifyColumn` 미지원** — `alterTable { modifyColumn(...) }`은 빈 리스트를 반환합니다. 컬럼 타입 변경은 ClickHouse 네이티브 DDL로 직접 처리해야 합니다.

3. **JDBC 전용, R2DBC 미지원** — 이 모듈은 JDBC 기반입니다. R2DBC/리액티브 통합은 지원하지 않습니다.

4. **`LowCardinality` 래핑 순서** — ClickHouse는 `Nullable(LowCardinality(T))`를 지원하지 않습니다. 반드시 `LowCardinality(Nullable(T))` 순서를 사용하세요.

5. **HikariCP 설정** — `autoCommit=true`가 강제됩니다. 불필요한 연결 낭비를 막으려면 `minimumIdle=1`을 설정하세요.

6. **DDL 제약 변환** — Exposed가 생성한 `CREATE TABLE`에서 기본 키 제약·인라인 `REFERENCES`·컬럼 nullability 제약을 제거합니다. 인용된 문자열과 식별자, 주석, `Nullable(T)`, `DEFAULT` 식은 보존합니다. 엔진 DSL의 `ORDER BY`로 물리적 정렬 키를 정의하세요. 테이블 수준 외래 키 제약은 지원하지 않으므로 ClickHouse 테이블에는 외래 키를 선언하지 마세요. Exposed DDL에 한정된 어댑터이며 수동 SQL이나 다른 dialect를 위한 범용 파서는 아닙니다.

7. **컬럼 코멘트 제거** — `COMMENT ON COLUMN` 구문은 DDL 필터에 의해 제거되어 효과가 없습니다.

## 라이선스

MIT License — 자세한 내용은 [LICENSE](../../LICENSE)를 참조하세요.
