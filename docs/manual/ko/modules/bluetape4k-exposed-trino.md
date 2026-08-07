---
manualId: "bluetape4k-exposed-trino"
id: "bluetape4k-exposed-trino"
title: "Exposed Trino 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-trino"
sourceDir: "exposed/trino"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-trino
---

# Exposed Trino 어댑터

`bluetape4k-exposed-trino`는 Exposed JDBC DSL을 Trino coordinator에 연결하면서 catalog·schema·autocommit 경계를 드러냅니다.

## 해결하려는 문제 {#problem}

Trino는 하나의 트랜잭션 DB가 아니라 여러 connector를 묶는 질의 엔진입니다. 이 모듈은 JDBC 방언과 연결 옵션을 등록하고 미지원 PK DDL을 제거하되, 클라이언트 블록을 원자적인 것처럼 꾸미지 않습니다.

## 언제 사용하는가 {#when-to-use}

여러 catalog에 Exposed로 만든 쿼리를 보낼 때 사용합니다. DML과 DDL 지원 여부는 coordinator가 아니라 대상 connector의 능력으로 판단해야 합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-trino")
}
```

## 핵심 개념 {#concepts}

- `TrinoDatabase.connect`는 host/port/catalog/schema, JDBC URL, `DataSource`를 지원합니다.
- `TrinoConnectionWrapper`는 autocommit을 유지하며 commit/rollback은 아무 일도 하지 않습니다.
- `TrinoTable`은 PK와 명시적인 nullable DDL을 지우되 `NOT NULL`은 보존합니다.
- `TrinoConnectionOptions`가 드라이버 속성을 담당합니다.

## 빠른 시작 {#quick-start}

```kotlin
val db = TrinoDatabase.connect(
    host = "localhost", port = 8080,
    catalog = "memory", schema = "default", user = "analyst",
)
transaction(db) { Events.selectAll().limit(100).toList() }
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| coordinator 연결 | `TrinoDatabase.connect` |
| 풀 연결 | `TrinoDatabase.connect(dataSource)` |
| 단순 호환 DDL | `TrinoTable` 상속 |
| Coroutine에서 실행 | `suspendTransaction` |
| Flow 소비 | `queryFlow` |

## 권장 패턴 {#patterns}

catalog의 predicate pushdown을 기준으로 쿼리를 설계하고 Trino 실행 계획을 확인하세요. 여러 DML은 하나씩 독립적으로 처리합니다. 큰 결과는 SQL에서 페이지로 나누세요. Flow 어댑터도 결과를 먼저 목록으로 만듭니다.

## 연동 모듈 {#integrations}

Trino JDBC 드라이버를 포함합니다. 1.11 테스트는 Testcontainers Trino의 memory connector에서 DDL 정제, 조회, 삽입, 입력 검증, `DataSource`, 트랜잭션 비원자성을 확인합니다.

## 설정 {#configuration}

URL은 `jdbc:trino://host:port/catalog/schema` 형식입니다. 인증, SSL, role, session property는 coordinator 정책을 확인한 뒤 `TrinoConnectionOptions`에 넣습니다. 운영 pool의 수명은 애플리케이션이 소유합니다.

## 실패 방식 {#failures}

- `transaction {}`은 호출 호환 경계일 뿐 원자적 작업 단위가 아닙니다.
- PK·UK·FK와 DDL 지원은 connector마다 다릅니다.
- pushdown이 되지 않으면 예상보다 많은 원격 데이터를 읽을 수 있습니다.
- Flow materialization이 큰 heap을 사용할 수 있습니다.

## 운영 {#operations}

대기·실행 시간, 스캔 byte, 원격 읽기량, 재시도, coordinator 오류를 관찰하세요. 쿼리 제한과 resource group은 어댑터 밖에서 설정하고 취소 정책도 Trino query 수준까지 연결합니다.

## 테스트 {#testing}

컨테이너로 어댑터 동작을 검증한 다음 운영 catalog별 통합 테스트를 따로 두세요. memory connector 결과만 보고 BigQuery·Hive·Iceberg의 동작을 추정하면 안 됩니다.

## 학습 경로 {#workshops}

[데이터베이스 어댑터 표](../guides/database-adapter-matrix.md)와 [OLTP와 OLAP 가이드](../guides/oltp-vs-olap.md)를 읽으세요. 운영 connector에서 대표 쿼리 하나와 미지원 쓰기 하나를 먼저 확인합니다.

## 제약 사항 {#limitations}

1.11은 트랜잭션 원자성, 모든 connector에 통하는 DDL, 진짜 행 단위 Flow 스트리밍을 제공하지 않습니다. `TrinoUnsupported` 어노테이션도 connector 능력을 컴파일 시점에 찾아주지는 않습니다.

## 소스 {#sources}

- [`TrinoDatabase`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoDatabase.kt)
- [`TrinoTable`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoTable.kt)
- [`TrinoConnectionWrapper`](../../../../exposed/trino/src/main/kotlin/io/bluetape4k/exposed/trino/TrinoConnectionWrapper.kt)
- [DB 테스트](../../../../exposed/trino/src/test/kotlin/io/bluetape4k/exposed/trino/TrinoDatabaseTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Trino JDBC compatibility boundary 다이어그램

[![Trino JDBC compatibility boundary 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-diagram-01.svg)

_배포본 README: [`exposed/trino/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/trino/README.ko.md)_

### Trino 흐름 materialization contract 다이어그램

[![Trino 흐름 materialization contract 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-trino-flow-02.svg)

_배포본 README: [`exposed/trino/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/trino/README.ko.md)_

<!-- release-readme-diagrams:end -->
