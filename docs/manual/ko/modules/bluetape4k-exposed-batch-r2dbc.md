---
manualId: "bluetape4k-exposed-batch-r2dbc"
id: "bluetape4k-exposed-batch-r2dbc"
title: "Exposed 배치 R2DBC adapter"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch-r2dbc"
sourceDir: "utils/batch/r2dbc"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc
---

# Exposed 배치 R2DBC adapter

> `batch-core`를 위한 suspend 가능한 R2DBC 저장소, keyset reader, batch writer입니다.

## 문제 {#problem}

reactive 애플리케이션은 JDBC class를 import하지 않고 배치 상태를 영속화해야
합니다. 이 artifact가 자체 R2DBC table과 mapping 정의를 소유합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 `R2dbcDatabase`, pool, suspend transaction 수명주기를 소유할
때 선택하세요. blocking Exposed transaction에는 JDBC artifact를 사용합니다.

## 의존성 좌표 {#coordinates}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-r2dbc")
    runtimeOnly("io.github.bluetape4k:bluetape4k-jackson3")
}
```

## 핵심 개념 {#concepts}

`ExposedR2dbcBatchJobRepository`는 하나의 `suspendTransaction` 안에서
owner/version CAS를 적용합니다. R2DBC table과 mapper는 JDBC package를
재사용하지 않습니다.

## 빠르게 시작하기 {#quick-start}

애플리케이션이 소유한 `R2dbcDatabase`를 만들고 batch table을 생성한 뒤
`CheckpointJson.jackson3()` 또는 사용자 정의 serializer를 선택해 suspend
scope에서 core runner를 실행합니다.

## 작업별 API {#api-by-task}

- 영속 suspend 상태에는 `ExposedR2dbcBatchJobRepository`를 사용합니다.
- keyset page와 checkpoint 복원에는 `ExposedR2dbcBatchReader`를 사용합니다.
- suspend batch 저장에는 `ExposedR2dbcBatchWriter`를 사용합니다.

## 권장 패턴 {#patterns}

메모리의 non-suspend lock 밖에서 suspend 데이터베이스 작업을 실행하고,
reader 순서를 안정적으로 유지하며 writer 효과를 멱등하게 만드세요.

## 연동 {#integrations}

이 adapter는 `batch-core`, Exposed R2DBC, Bluetape coroutine/R2DBC utility에
의존합니다. production에서 JDBC adapter를 의존하지 않습니다.

## 설정 {#configuration}

R2DBC pool, dialect, page size, dispatcher를 설정합니다. 데이터베이스 생성,
transaction 조합, metrics, 종료는 애플리케이션이 소유합니다.

## 마이그레이션과 호환성 {#migration}

기존 aggregator 의존성을 `batch-core`와 이 adapter로 나누고 버전은 생태계
BOM에서 공급하세요. 신규 코드는 public
`io.bluetape4k.batch.CheckpointJson`을 import해야 하며 deprecated
`io.bluetape4k.batch.internal.CheckpointJson` 생성자와 mapper overload는 JVM
호환 bridge로만 유지됩니다. 사용자 정의 저장소는
`saveCheckpointAndReturn`를 구현하여 owner/version CAS를 보존해야 합니다.
`CheckpointJson.jackson3()`를 사용하려면 선택적 `bluetape4k-jackson3` runtime을
추가하고, 아니면 명시적인 allowlist를 가진 사용자 정의 serializer를 주입하세요.

저장소 루트에서 호환성 fixture를 실행하세요.

```bash
bash scripts/batch/validate_consumer_fixtures.sh
```

검증 범위는 `aggregator-runtime`, `core-custom-json`, `jdbc-runtime`,
`r2dbc-jackson3-runtime`, `legacy-binary-runtime`과 Maven JDBC consumer입니다.

## 실패 유형과 해결 방법 {#failures}

잘못된 owner, 오래된 version, 미청구 execution, 0행 CAS 갱신은 fail-closed로
실패합니다. 취소를 일반 실패로 바꾸지 않습니다.
청크를 커밋한 뒤 스텝이 `FAILED`가 되어도 마지막 checkpoint를 유지합니다. 실패
보고서의 null checkpoint는 새 값이 없다는 뜻이므로 R2DBC 완료 갱신은 저장된 값을
지우지 않고, 재시작은 해당 key 다음부터 이어갑니다.

## 운영 {#operations}

suspend transaction 지연, lease 만료, CAS 충돌, checkpoint 크기, driver 오류를
관측합니다. schema 생성과 migration은 adapter 밖에서 수행합니다.

## 테스트 {#testing}

H2, PostgreSQL, MySQL_V8 순서로 `./gradlew :bluetape4k-exposed-batch-r2dbc:test`를
실행합니다. repository CAS, reader/writer 왕복, 재시작, R2DBC mapping을 검증합니다.

## 학습 경로와 예제 {#workshops}

[core 매뉴얼](bluetape4k-exposed-batch-core.md)을 읽은 뒤 transaction 모델을
선택하기 전에 [JDBC adapter](bluetape4k-exposed-batch-jdbc.md)와 비교하세요.

## 제약 사항 {#limitations}

pool, schema migration, 외부 효과의 exactly-once를 소유하지 않습니다.
checkpoint 저장 의미는 at-least-once입니다.

## 근거 자료 {#sources}

- [`ExposedR2dbcBatchJobRepository.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchJobRepository.kt)
- [`BatchJobExecutionTable.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/tables/BatchJobExecutionTable.kt)
- [`ExposedR2dbcBatchReader.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchReader.kt)
