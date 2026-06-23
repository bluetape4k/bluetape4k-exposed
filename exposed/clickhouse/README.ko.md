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

6. **DDL에서 PRIMARY KEY 미지원** — `CREATE TABLE`에서 `PRIMARY KEY`와 `CONSTRAINT` 절이 제거됩니다. 엔진 DSL의 `ORDER BY`를 사용해 물리적 정렬 키를 정의하세요.

7. **컬럼 코멘트 제거** — `COMMENT ON COLUMN` 구문은 DDL 필터에 의해 제거되어 효과가 없습니다.

## 라이선스

MIT License — 자세한 내용은 [LICENSE](../../LICENSE)를 참조하세요.
