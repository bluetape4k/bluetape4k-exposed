---
manualId: "bluetape4k-exposed-clickhouse"
id: "bluetape4k-exposed-clickhouse"
title: "Exposed ClickHouse 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-clickhouse"
sourceDir: "exposed/clickhouse"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-clickhouse
---

# Exposed ClickHouse 어댑터

`bluetape4k-exposed-clickhouse`는 Exposed JDBC 쿼리와 ClickHouse 전용 엔진·타입·집계·날짜 함수를 ClickHouse의 autocommit OLAP 모델에 맞춥니다.

## 해결하려는 문제 {#problem}

Exposed가 기대하는 JDBC 트랜잭션과 관계형 DDL은 ClickHouse와 다릅니다. 어댑터는 방언을 등록하고 autocommit을 강제하며, 테이블 DDL을 정제하고 ClickHouse 전용 DSL을 제공합니다.

## 언제 사용하는가 {#when-to-use}

ClickHouse가 분석 조회와 append 중심 쓰기를 맡을 때 사용합니다. Exposed의 `transaction {}` 블록을 여러 문장의 원자적 작업 단위로 보면 안 됩니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-clickhouse")
}
```

## 핵심 개념 {#concepts}

- `ClickHouseConnectionWrapper`는 autocommit을 유지하며 `commit`과 `rollback`은 아무 일도 하지 않습니다.
- `ClickHouseTable`은 `CREATE TABLE`만 남기고 관계형 제약을 걷어낸 뒤 엔진 절을 붙입니다.
- MergeTree DSL, unsigned·array·low-cardinality·시간 타입과 분석 함수를 제공합니다.
- `queryFlow`는 결과를 모두 목록으로 만든 뒤 emit합니다.

## 빠른 시작 {#quick-start}

```kotlin
val db = ClickHouseDatabase.connect(
    host = "localhost", port = 8123, database = "default",
    user = "default", password = "",
)
transaction(db) { Events.selectAll().limit(100).toList() }
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 연결 | `ClickHouseDatabase.connect` |
| 엔진 DDL | `ClickHouseTable`, `mergeTree { ... }` |
| Coroutine 연결 | `suspendTransaction` |
| Flow 소비 | `queryFlow` |
| 미지원 동작 명시 | `ClickHouseUnsupported` |

## 권장 패턴 {#patterns}

쓰기 문장마다 독립적으로 재시도할 수 있게 만들고 가능하면 중복 실행에도 안전하게 설계하세요. 수집은 batch insert와 알맞은 MergeTree 엔진을 사용합니다. 큰 결과는 DB에서 집계하거나 페이지로 나눕니다.

## 연동 모듈 {#integrations}

ClickHouse JDBC 드라이버를 포함합니다. 1.11 테스트는 Testcontainers ClickHouse에서 연결, DDL, 단건·배치 삽입, 엔진 절, 함수, 사용자 타입을 검증합니다. `examples-exposed-clickhouse-oltp-olap`에서 비교 실행을 볼 수 있습니다.

## 설정 {#configuration}

간편 연결은 HTTP 포트 `8123`을 쓰며 전체 JDBC URL도 받을 수 있습니다. 엔진, `ORDER BY`, 파티션 키, 보존 정책은 스키마 검토 항목으로 다루세요.

## 실패 방식 {#failures}

- 뒤 문장이 실패해도 앞에서 실행한 DML은 되돌아가지 않습니다.
- `INSERT IGNORE`, upsert, `RETURNING`은 지원하지 않습니다.
- 일반적인 PK·FK·시퀀스·컬럼 타입 변경 DDL은 그대로 사용할 수 없습니다.
- `queryFlow`가 큰 결과를 한꺼번에 메모리에 올릴 수 있습니다.

## 운영 {#operations}

삽입 batch 크기, 거부된 행, 쿼리 시간, part/merge, 메모리를 관찰하세요. 재시도 범위는 논리 batch보다 작게 유지하고 중복 쓰기 가능성이 있으면 deduplication key를 남깁니다.

## 테스트 {#testing}

격리한 ClickHouse 컨테이너에서 생성한 엔진 DDL과 실제 조회 결과를 함께 검증하세요. DML이 둘 이상인 흐름에는 부분 반영 테스트를 추가합니다.

## 학습 경로 {#workshops}

[어댑터 표](../guides/database-adapter-matrix.md)를 확인한 뒤 [ClickHouse OLTP/OLAP 예제](../../../../examples/exposed-clickhouse-oltp-olap/README.ko.md)를 실행하세요. [OLTP와 OLAP 가이드](../guides/oltp-vs-olap.md)는 트랜잭션 쓰기를 다른 DB에 남겨야 하는 경우를 설명합니다.

## 제약 사항 {#limitations}

이 래퍼는 Exposed 호출을 가능하게 할 뿐 ClickHouse에 트랜잭션·savepoint·관계형 제약·rollback을 추가하지 않습니다. 1.11 테스트로 확인한 엔진·타입·함수 범위만 지원 대상으로 봅니다.

## 소스 {#sources}

- [`ClickHouseDatabase`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabase.kt)
- [`ClickHouseTable`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseTable.kt)
- [`ClickHouseUnsupported`](../../../../exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseUnsupported.kt)
- [DB 테스트](../../../../exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseDatabaseTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### ClickHouse Exposed integration 아키텍처

[![ClickHouse Exposed integration 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-diagram-01.svg)

_배포본 README: [`exposed/clickhouse/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/clickhouse/README.ko.md)_

### ClickHouse DDL 수명 주기

[![ClickHouse DDL 수명 주기](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-clickhouse-flow-02.svg)

_배포본 README: [`exposed/clickhouse/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/clickhouse/README.ko.md)_

<!-- release-readme-diagrams:end -->
