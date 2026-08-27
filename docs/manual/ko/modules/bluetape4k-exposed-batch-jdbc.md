---
manualId: "bluetape4k-exposed-batch-jdbc"
id: "bluetape4k-exposed-batch-jdbc"
title: "Exposed 배치 JDBC adapter"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch-jdbc"
sourceDir: "utils/batch/jdbc"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc
---

# Exposed 배치 JDBC adapter

> `batch-core` runtime을 위한 JDBC 저장소, keyset reader, batch writer입니다.

## 문제 {#problem}

애플리케이션은 core artifact에 JDBC class를 결합하지 않고 배치 실행을
영속화해야 합니다. 이 adapter가 Exposed JDBC table과 transaction을 소유합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 JDBC `Database`, connection pool, transaction 수명주기를 소유할
때 사용하세요. 완전한 suspend 경로가 필요하면 R2DBC artifact를 선택합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-jdbc")
}
```

## 핵심 개념 {#concepts}

`ExposedJdbcBatchJobRepository`는 owner, lease, version, 상태, 처리 건수,
parameter, checkpoint를 저장합니다. owner-aware 갱신은 owner와 version이
일치하고 정확히 1행을 갱신해야 합니다.

## 빠르게 시작하기 {#quick-start}

애플리케이션이 소유한 `Database`를 만들고 배치 두 table을 생성한 뒤
`CheckpointJson` 구현을 repository에 전달합니다. reader와 writer는 애플리케이션이
제공하는 동일한 JDBC transaction 경계를 사용합니다.

## 작업별 API {#api-by-task}

- 영속 잡·스텝 상태에는 `ExposedJdbcBatchJobRepository`를 사용합니다.
- keyset page 읽기와 checkpoint에는 `ExposedJdbcBatchReader`를 사용합니다.
- 명시적인 중복 정책이 필요한 batch insert에는 `ExposedJdbcBatchWriter`를 사용합니다.

## 권장 패턴 {#patterns}

transaction을 짧게 유지하고 reader 정렬을 안정적으로 만들며, ID-only
checkpoint overload는 신뢰된 운영용 escape hatch로만 사용합니다.

## 연동 {#integrations}

이 adapter는 `batch-core`와 Exposed JDBC에 의존합니다. R2DBC adapter를 import하지
않으며 Spring Boot도 요구하지 않습니다.

## 설정 {#configuration}

JDBC pool, dialect, page size, 애플리케이션 transaction 범위를 설정합니다.
adapter가 애플리케이션의 `Database`를 만들거나 닫지 않습니다.

## 마이그레이션과 호환성 {#migration}

기존 aggregator 의존성을 `batch-core`와 이 adapter로 나누고 버전은 생태계
BOM에서 공급하세요. 새 코드는 public
`io.bluetape4k.batch.CheckpointJson`을 사용해야 합니다. deprecated
`io.bluetape4k.batch.internal.CheckpointJson`을 받는 생성자와 legacy mapper
overload는 JVM 호환 bridge로만 남아 있습니다. 사용자 정의 저장소도
`saveCheckpointAndReturn`를 구현하여 checkpoint 갱신에서 owner/version CAS를
유지하세요. Jackson 3 전략은 선택 사항이므로 `bluetape4k-jackson3`를 추가하거나
allowlist를 가진 사용자 정의 `CheckpointJson`을 주입합니다.

저장소 루트에서 호환성 fixture를 실행하세요.

```bash
bash scripts/batch/validate_consumer_fixtures.sh
```

이 gate는 `aggregator-runtime`, `core-custom-json`, `jdbc-runtime`,
`r2dbc-jackson3-runtime`, `legacy-binary-runtime`과 Maven JDBC consumer를
published-style artifact로 compile하고 실행합니다.

## 실패 유형과 해결 방법 {#failures}

잘못된 owner, 오래된 version, 미청구 execution, 0행 CAS 갱신은 명시적으로
실패합니다. lease를 잃으면 외부 효과를 재시도하기 전에 정합성을 확인합니다.
청크를 커밋한 뒤 스텝이 `FAILED`가 되어도 마지막 checkpoint를 유지합니다. 실패
보고서의 null checkpoint는 새 값이 없다는 뜻이므로 JDBC 완료 갱신은 저장된 값을
지우지 않고, 재시작은 해당 key 다음부터 이어갑니다.

## 운영 {#operations}

데이터베이스 lock 지연, lease 만료, CAS 충돌, checkpoint 크기, writer 중복
오류를 관측합니다. schema 소유와 migration은 애플리케이션에 둡니다.

## 테스트 {#testing}

H2, PostgreSQL, MySQL_V8 순서로 `./gradlew :bluetape4k-exposed-batch-jdbc:test`를
실행합니다. repository CAS, 재시작, reader paging, writer, Exposed mapper 왕복을
검증합니다.

## 학습 경로와 예제 {#workshops}

[core 매뉴얼](bluetape4k-exposed-batch-core.md)을 먼저 읽고 transaction 모델을
고를 때 [R2DBC adapter](bluetape4k-exposed-batch-r2dbc.md)와 비교하세요.

## 제약 사항 {#limitations}

schema migration, connection pool 소유, 외부 효과의 exactly-once를 제공하지
않습니다. checkpoint 저장 의미는 at-least-once입니다.

## 근거 자료 {#sources}

- [`ExposedJdbcBatchJobRepository.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchJobRepository.kt)
- [`BatchJobExecutionTable.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/tables/BatchJobExecutionTable.kt)
- [`ExposedJdbcBatchReader.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchReader.kt)
