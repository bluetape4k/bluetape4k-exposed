---
manualId: "bluetape4k-exposed-duckdb"
id: "bluetape4k-exposed-duckdb"
title: "Exposed DuckDB 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-duckdb"
sourceDir: "exposed/duckdb"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-duckdb
---

# Exposed DuckDB 어댑터

`bluetape4k-exposed-duckdb`는 Exposed JDBC DSL을 임베디드 DuckDB에 연결합니다. PostgreSQL 계열 방언을 등록하고 DuckDB JDBC가 구현하지 않은 generated-key `prepareStatement` 오버로드를 보정합니다.

## 해결하려는 문제 {#problem}

DuckDB는 로컬 분석에 편리하지만 연결 수명과 JDBC 메타데이터가 서버형 RDBMS와 다릅니다. 이 모듈은 드라이버 차이를 저장소 코드 곳곳에 흩뜨리지 않고 어댑터 경계에서 처리합니다.

## 언제 사용하는가 {#when-to-use}

프로세스 내부 분석, 로컬 파일, 반복 가능한 데이터 가공, DuckDB SQL이 유용한 테스트에 사용합니다. 여러 애플리케이션이 공유하는 트랜잭션 서버를 대신하는 용도로는 맞지 않습니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-duckdb")
}
```

## 핵심 개념 {#concepts}

- `inMemory()`는 새 연결마다 독립된 DB를 만듭니다.
- `file(path)`와 `readOnly(path)`는 파일을 영속 또는 읽기 전용으로 엽니다.
- `DuckDBDialectMetadata`는 지원되지 않는 imported-key 조회를 건너뜁니다.
- `queryFlow`는 트랜잭션 안에서 전체 결과를 목록으로 만든 뒤 emit하므로 행 단위 스트리밍이 아닙니다.

## 빠른 시작 {#quick-start}

```kotlin
val db = DuckDBDatabase.file("/tmp/events.duckdb")
transaction(db) {
    SchemaUtils.create(Events)
    Events.insert { it[id] = 1L; it[region] = "kr" }
    val rows = Events.selectAll().limit(100).toList()
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 일회성 DB | `DuckDBDatabase.inMemory()` |
| 트랜잭션 사이 상태 공유 | `DuckDBDatabase.file(path)` |
| 읽기 전용 분석 | `DuckDBDatabase.readOnly(path)` |
| Coroutine에서 블로킹 JDBC 실행 | `suspendTransaction` |
| Flow 소비 | `queryFlow` |

## 권장 패턴 {#patterns}

여러 트랜잭션이 같은 상태를 봐야 하면 파일 DB나 연결 하나짜리 풀을 사용하세요. 큰 결과는 SQL에서 집계하거나 페이지로 나눕니다. JDBC 호출은 `Dispatchers.IO`나 Virtual Thread dispatcher에서 실행합니다.

## 연동 모듈 {#integrations}

DuckDB JDBC 드라이버가 API 의존성으로 들어오며 외부 서비스나 Testcontainers는 필요 없습니다. 1.11 테스트는 `SchemaUtils`, 삽입, 필터, 집계, 정렬, `limit`를 실제 임베디드 DB에서 검증합니다.

## 설정 {#configuration}

드라이버가 네이티브 코드를 읽으므로 Java 25 이상 테스트에는 `--enable-native-access=ALL-UNNAMED`가 필요합니다. DB 파일의 위치·권한·백업·삭제 정책은 애플리케이션이 정합니다.

## 실패 방식 {#failures}

- 서로 다른 인메모리 연결은 데이터를 공유하지 않습니다.
- imported-key 메타데이터가 없으므로 메타데이터 조회만으로 FK 존재를 판단할 수 없습니다.
- `queryFlow`는 첫 emit 전에 전체 결과를 메모리에 올릴 수 있습니다.
- PostgreSQL 계열 SQL 생성이 모든 DuckDB 기능을 포괄하지는 않습니다.

## 운영 {#operations}

DB 파일 크기, 쿼리 시간, 메모리 압력, 동시 접근을 관찰하세요. 읽기 전용 소비자는 `readOnly`로 분리하고 애플리케이션이 만든 풀과 연결은 종료 시 닫습니다.

## 테스트 {#testing}

연결을 넘나드는 테스트는 임시 파일을 쓰고, 한 연결 안에서 끝나면 `inMemory()`를 사용합니다. PostgreSQL에서 물려받은 문법과 nullable·시간 컬럼은 생성 SQL뿐 아니라 실제 결과까지 검증하세요.

## 학습 경로 {#workshops}

[데이터베이스 어댑터 표](../guides/database-adapter-matrix.md)에서 차이를 확인한 뒤 [OLTP와 OLAP 선택 가이드](../guides/oltp-vs-olap.md)를 읽으세요. 공유 가용성과 DB 수준 동시성 제어가 필요해질 때 서버형 어댑터로 옮깁니다.

## 제약 사항 {#limitations}

1.11은 행 단위 Flow 스트리밍, imported-key 메타데이터, 분산 서비스 경계를 제공하지 않습니다. PostgreSQL 방언 상속은 검증한 범위만 보장합니다.

## 소스 {#sources}

- [`DuckDBDatabase`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/DuckDBDatabase.kt)
- [`DuckDBExtensions`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/DuckDBExtensions.kt)
- [`DuckDBDialectMetadata`](../../../../exposed/duckdb/src/main/kotlin/io/bluetape4k/exposed/duckdb/dialect/DuckDBDialectMetadata.kt)
- [DB 테스트](../../../../exposed/duckdb/src/test/kotlin/io/bluetape4k/exposed/duckdb/DuckDBDatabaseTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### DuckDB Exposed integration boundary

[![DuckDB Exposed integration boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-diagram-01.svg)

_배포본 README: [`exposed/duckdb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/duckdb/README.ko.md)_

### DuckDB query 흐름 materialization

[![DuckDB query 흐름 materialization](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-duckdb-flow-02.svg)

_배포본 README: [`exposed/duckdb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/duckdb/README.ko.md)_

<!-- release-readme-diagrams:end -->
