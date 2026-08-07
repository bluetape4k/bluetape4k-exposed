---
manualId: "bluetape4k-exposed-bigquery"
id: "bluetape4k-exposed-bigquery"
title: "Exposed BigQuery 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-bigquery"
sourceDir: "exposed/bigquery"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-bigquery
---

# Exposed BigQuery 어댑터

`bluetape4k-exposed-bigquery`는 Exposed DSL 문장을 GoogleSQL로 바꿔 BigQuery REST Query Job으로 실행합니다. JDBC 트랜잭션 어댑터가 아니라 SQL 생성과 원격 실행을 이어 주는 모듈입니다.

## 해결하려는 문제 {#problem}

BigQuery JDBC 드라이버 없이도 Exposed 테이블과 쿼리 정의를 재사용할 수 있습니다. `BigQueryContext`는 내부 H2 PostgreSQL 모드 DB를 SQL 생성에만 쓰고 변환한 문장은 BigQuery API로 보냅니다.

## 언제 사용하는가 {#when-to-use}

Query Job 방식이 맞는 SELECT, 기본 INSERT·UPDATE·DELETE, dry run, 제한된 DDL에 사용합니다. 한 작업 단위에 JDBC 원자성이나 DAO 동작이 필요하면 다른 영속성 경계를 선택하세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-bigquery")
}
```

## 핵심 개념 {#concepts}

- `BigQueryContext`가 REST client, project/dataset, SQL 생성 DB, dispatcher를 묶습니다.
- `Query.withBigQuery()`로 목록·Flow·단건·dry run executor를 만듭니다.
- `BigQueryQueryOptions`는 비용 상한, label, priority, location, destination table, timeout, cache 사용을 설정합니다.
- REST 호출마다 Query Job이 따로 생기며 여러 호출을 되돌리는 rollback은 없습니다.

## 빠른 시작 {#quick-start}

```kotlin
val context = BigQueryContext.create(bigquery, "project", "analytics")
with(context) {
    Events.selectAll().withBigQuery(
        BigQueryQueryOptions(maximumBytesBilled = 10_000_000)
    ).dryRun()
    val rows = Events.selectAll().limit(100).withBigQuery().toList()
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| Exposed 쿼리 실행 | `withBigQuery().toList()` / `toFlow()` |
| 문법·비용 검증 | `dryRun()` / `validateRawQuery()` |
| DML | `execInsert`, `execUpdate`, `execDelete` |
| DDL | `execCreateTable`, `execDropTable` |
| 원시 GoogleSQL | `runRawQuery` |

## 권장 패턴 {#patterns}

사용자 입력이나 비용이 큰 쿼리는 항상 dry run하고 `maximumBytesBilled`를 설정하세요. 큰 결과는 page token을 따라 페이지별로 emit하는 `toFlow`를 사용합니다. DML 호출은 각각 독립적으로 재시도할 수 있게 만들고 여러 호출을 한 트랜잭션으로 보지 않습니다.

## 연동 모듈 {#integrations}

공개 실행 경계는 `google-api-services-bigquery-v2`이고 H2는 내부 SQL 생성기입니다. 테스트는 로컬 에뮬레이터나 Testcontainers BigQuery emulator를 선택합니다. `examples-exposed-bigquery-dry-run`은 유료 실행 없이 검증하는 경로를 보여 줍니다.

## 설정 {#configuration}

인증된 `Bigquery` client와 project, dataset, location, credential을 맞추세요. 비용 귀속 label, timeout, billed-byte 상한을 설정합니다. 기본은 Standard SQL이며 별도 값이 없으면 요청 timeout은 30초입니다.

## 실패 방식 {#failures}

- 뒤 Query Job이 실패해도 앞에서 성공한 Job은 되돌아가지 않습니다.
- H2가 SQL을 만들었다고 BigQuery가 받아들인다는 뜻은 아닙니다. 서버 검증은 dry run으로 합니다.
- `toList`는 여러 페이지를 모두 heap에 모읍니다.
- SchemaUtils 자동화, sequence, generated key, 컬럼 타입 변경에는 제한이 있습니다.

## 운영 {#operations}

Job ID, label, 처리·과금 byte, slot time, cache 사용, location, 오류를 기록하세요. 생성 SQL을 진단에 남기되 민감한 literal은 로그에 노출하지 않습니다.

## 테스트 {#testing}

SQL 생성과 옵션은 단위 테스트하고 API 동작은 에뮬레이터에서 확인합니다. 권한과 location 차이는 작은 운영 프로젝트 smoke test로 보완하세요. page token, 페이지 사이 취소, dry-run 상한, decimal·timestamp·null 변환을 검증합니다.

## 학습 경로 {#workshops}

[BigQuery dry-run 예제](../../../../examples/exposed-bigquery-dry-run/README.ko.md)부터 실행하세요. 이어서 [어댑터 표](../guides/database-adapter-matrix.md)와 [OLTP와 OLAP 가이드](../guides/oltp-vs-olap.md)에서 BigQuery 직접 실행과 Trino 경유를 비교합니다.

## 제약 사항 {#limitations}

JDBC 트랜잭션, 완전한 DAO 호환, 모든 SchemaUtils DDL, REST Job 사이 rollback은 제공하지 않습니다. join/alias 결과 접근과 스키마 변환은 실제 쿼리로 검증해야 합니다.

## 소스 {#sources}

- [`BigQueryContext`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryContext.kt)
- [`BigQueryQueryExecutor`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryExecutor.kt)
- [`BigQueryQueryOptions`](../../../../exposed/bigquery/src/main/kotlin/io/bluetape4k/exposed/bigquery/BigQueryQueryOptions.kt)
- [Context 테스트](../../../../exposed/bigquery/src/test/kotlin/io/bluetape4k/exposed/bigquery/BigQueryContextUnitTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### BigQuery REST execution boundary 다이어그램

[![BigQuery REST execution boundary 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-diagram-01.svg)

_배포본 README: [`exposed/bigquery/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bigquery/README.ko.md)_

### BigQuery query job 수명 주기 처리 흐름

[![BigQuery query job 수명 주기 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-bigquery-flow-02.svg)

_배포본 README: [`exposed/bigquery/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/bigquery/README.ko.md)_

<!-- release-readme-diagrams:end -->
