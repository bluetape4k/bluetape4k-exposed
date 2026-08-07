---
manualId: "bluetape4k-exposed-cockroachdb"
id: "bluetape4k-exposed-cockroachdb"
title: "Exposed CockroachDB 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-cockroachdb"
sourceDir: "exposed/cockroachdb"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb
---

# Exposed CockroachDB 어댑터

`bluetape4k-exposed-cockroachdb`는 PostgreSQL JDBC로 Exposed를 연결하고 직렬화 실패 때 전체 트랜잭션을 다시 실행하는 CockroachDB 전용 경계를 제공합니다.

## 해결하려는 문제 {#problem}

CockroachDB는 PostgreSQL wire protocol과 호환되지만 분산 serializable 트랜잭션은 SQLSTATE `40001`을 반환할 수 있어 작업 전체를 처음부터 다시 해야 합니다. PostgreSQL 기능도 모두 같지는 않습니다.

## 언제 사용하는가 {#when-to-use}

CockroachDB의 분산성이 필요한 트랜잭션 workload에서 작업 전체를 재시도에 안전하게 만들 수 있을 때 사용합니다. PostgreSQL을 지원한다는 이유만으로 선택하지는 마세요.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-cockroachdb")
}
```

## 핵심 개념 {#concepts}

`CockroachDatabase`는 host/port/database, JDBC URL, `DataSource`를 받습니다. `withCockroachTransaction`은 최상위 serializable 트랜잭션을 열고 CockroachDB restart 오류만 제한된 backoff로 재시도합니다. compatibility ledger가 검증·부분 지원·미지원 PostgreSQL 기능을 기록합니다.

## 빠른 시작 {#quick-start}

```kotlin
val db = CockroachDatabase.connect(host = "localhost", database = "app")
withCockroachTransaction(db) {
    Accounts.update({ Accounts.id eq id }) { it[balance] = newBalance }
}
```

## 작업별 API {#api-by-task}

| 작업 | API |
| --- | --- |
| 연결 | `CockroachDatabase.connect` |
| 재시도 가능한 작업 | `withCockroachTransaction` |
| 재시도 설정 | `CockroachTransactionRetryOptions` |
| SQLSTATE 40001 판별 | `isCockroachRetryableTransactionError` |

## 권장 패턴 {#patterns}

기존 Exposed 트랜잭션 밖에서 helper를 호출하세요. 블록 안의 외부 부수 효과는 중복 실행에도 안전하게 만들거나 commit 뒤로 미룹니다. batch와 keyset page를 작게 유지하면 경합과 재시도 비용이 줄어듭니다.

## 연동 모듈 {#integrations}

PostgreSQL JDBC가 API 의존성입니다. 1.11 테스트는 Testcontainers CockroachDB에서 연결, 스키마 생성·삭제, compatibility matrix, 재시도 분류·backoff·전체 작업 재실행을 검증합니다.

## 설정 {#configuration}

기본 port는 `26257`, DB는 `defaultdb`, 격리 수준은 `SERIALIZABLE`입니다. TLS, credential, pool 수명, 최대 시도 횟수, backoff를 배포 환경에 맞추세요.

## 실패 방식 {#failures}

재시도 대상이 아닌 SQL 오류는 즉시 실패하고 횟수를 다 쓰면 예외가 전달됩니다. 트랜잭션 안의 외부 호출은 반복될 수 있습니다. advisory lock과 range type은 미지원이며 다른 PostgreSQL 기능도 부분 지원일 수 있습니다.

## 운영 {#operations}

재시도 횟수, SQLSTATE, 경합, 트랜잭션 시간, backoff, pool 대기, batch 크기를 관찰하세요. 높은 시도 횟수로 retry storm을 숨기지 마세요.

## 테스트 {#testing}

CockroachDB 컨테이너에서 재시도 가능한 충돌을 강제로 만드세요. 블록 전체가 반복되는지, 일반 오류는 반복하지 않는지, 외부 효과가 안전한지, DDL이 ledger와 맞는지, paging 순서가 안정적인지 확인합니다.

## 학습 경로 {#workshops}

[어댑터 표](../guides/database-adapter-matrix.md)에서 PostgreSQL과 비교하고 [OLTP와 OLAP 가이드](../guides/oltp-vs-olap.md)를 읽으세요. 일반 JDBC 트랜잭션 경계를 이해한 뒤 retry helper를 붙입니다.

## 제약 사항 {#limitations}

PostgreSQL wire 호환성은 기능 동등성이 아닙니다. helper는 문서화된 restart 오류만 처리하며 외부 부수 효과를 트랜잭션으로 만들거나 경합을 없애지 못합니다.

## 소스 {#sources}

- [`CockroachDatabase`](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDatabase.kt)
- [`CockroachTransaction`](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachTransaction.kt)
- [Compatibility ledger](../../../../exposed/cockroachdb/src/main/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDbCompatibility.kt)
- [DB 테스트](../../../../exposed/cockroachdb/src/test/kotlin/io/bluetape4k/exposed/cockroachdb/CockroachDatabaseTest.kt)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### CockroachDB helper boundary

[![CockroachDB helper boundary](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-diagram-01.svg)

_배포본 README: [`exposed/cockroachdb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cockroachdb/README.ko.md)_

### CockroachDB transaction retry 흐름

[![CockroachDB transaction retry 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-flow-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/exposed-cockroachdb-flow-02.svg)

_배포본 README: [`exposed/cockroachdb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/exposed/cockroachdb/README.ko.md)_

<!-- release-readme-diagrams:end -->
