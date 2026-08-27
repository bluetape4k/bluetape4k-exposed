---
manualId: "bluetape4k-exposed-batch-core"
id: "bluetape4k-exposed-batch-core"
title: "Exposed 배치 코어"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch-core"
sourceDir: "utils/batch/core"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch-core
---

# Exposed 배치 코어

> 데이터베이스 adapter와 분리된 배치 API, 코루틴 runner, DSL, 메모리 저장소, checkpoint 계약입니다.

## 문제 {#problem}

기존 aggregator가 모든 batch 클래스를 소유했습니다. 이 artifact는 runner와
공개 계약을 분리하여 데이터베이스 adapter가 필요 없는 애플리케이션의
의존성 범위를 줄입니다.

## 사용하기 좋은 경우 {#when-to-use}

메모리 실행, 사용자 정의 저장소, 공용 API 타입이 필요하면 `batch-core`를
사용하세요. Exposed 영속 저장이 필요할 때만 `batch-jdbc` 또는 `batch-r2dbc`를
추가합니다.

## 의존성 좌표 {#coordinates}

생태계 BOM을 사용하고 개별 버전은 생략합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch-core")
}
```

## 핵심 개념 {#concepts}

`BatchJobRepository`, `BatchReader`, `BatchWriter`, 실행 모델, DSL builder,
`BatchStepRunner`, 공개 `CheckpointJson` interface가 이 artifact에 있습니다.
소유자 기반 checkpoint 갱신은 version compare-and-set을 사용하며 runner는
반환된 version으로 local execution을 교체합니다.

## 빠르게 시작하기 {#quick-start}

폐기 가능한 프로세스나 테스트에서는 `InMemoryBatchJobRepository`를 사용합니다.

```kotlin
val repository = InMemoryBatchJobRepository()
val json = CheckpointJson.jackson3()
```

Jackson 3 factory는 선택적 runtime이며, 애플리케이션이 소유한 직렬화를 위해
사용자 정의 `CheckpointJson`을 주입할 수 있습니다.

## 작업별 API {#api-by-task}

- `BatchJob`, `BatchStep` 또는 DSL builder로 잡과 스텝을 정의합니다.
- `BatchReader`와 `BatchWriter` 경계를 구현합니다.
- 청크, retry, skip, timeout, 취소 흐름에는 `BatchStepRunner`를 사용합니다.
- 사용자 정의 영속 저장소를 위해 `BatchJobRepository`를 구현합니다.

## 권장 패턴 {#patterns}

메모리 lock 구간 밖에서 저장소 callback을 실행하고 writer 효과를 멱등하게
만드세요. 저장소 구현에서도 owner/version 필드를 유지하고 생성자와 직접적인
저장소 진입점에서 이름을 검증합니다.

## 연동 {#integrations}

`batch-core`는 Spring과 adapter에 중립적입니다. Exposed 영속 저장이 필요하면
`batch-jdbc` 또는 `batch-r2dbc`를 추가하고, 아니면 애플리케이션 저장소를
제공합니다.

## 설정 {#configuration}

runner에 청크 크기, retry 정책, skip 정책, commit timeout, lease 기간을
설정합니다. 저장소가 owner-aware CAS를 구현하지 않으면 core가 ID-only 갱신으로
조용히 우회하지 않고 명시적으로 거부해야 합니다.

## aggregator에서 마이그레이션하기 {#migration}

새 코드는 `io.bluetape4k.batch.internal.CheckpointJson` 대신
`io.bluetape4k.batch.CheckpointJson`을 import하세요. 이전 internal interface는
기존 binary를 위한 deprecated JVM bridge로 남아 있지만 신규 코드는 public core
type을 사용해야 합니다. `CheckpointJson.jackson3()`는 선택적인
`bluetape4k-jackson3` runtime이 필요하므로, 없으면 애플리케이션 소유
serializer를 주입하고 scalar가 아닌 checkpoint class를 명시적으로 등록하세요.

사용자 정의 `BatchJobRepository`는
`saveCheckpointAndReturn(execution, checkpoint)`를 구현해야 합니다. 기본 구현은
`UnsupportedOperationException`으로 fail-closed하며 runner가 legacy ID-only
갱신으로 조용히 우회하지 않습니다. 업그레이드 시 다섯 Gradle consumer fixture와
Maven fixture를 다음처럼 검증하세요.

```bash
for fixture in aggregator-runtime core-custom-json jdbc-runtime r2dbc-jackson3-runtime legacy-binary-runtime; do
  ./gradlew -p "utils/batch/consumer-fixtures/$fixture" verifyProvenance compileKotlin
done
bash scripts/batch/validate_consumer_fixtures.sh
```

## 실패 유형과 해결 방법 {#failures}

미청구, 잘못된 owner, 오래된 version, 0행 checkpoint 갱신은 fail-closed로
실패합니다. 취소는 `CancellationException`으로 유지하며 정리 실패는 primary
취소에 suppressed로 연결하고 삼키지 않습니다.
성공한 청크 뒤에 스텝이 실패하면 runner는 해당 checkpoint를 `FAILED` 보고서에
담습니다. 실패 처리에서 새 checkpoint를 얻지 못해도 저장소는 마지막 저장 값을
지우지 않고 보존해야 합니다.
checkpoint 조회 자체가 취소되면 저장된 checkpoint를 보존한 `STOPPED` 저장을
먼저 시도한 뒤 원래 실패를 함께 연결한 `CancellationException`을 전파하며 이를
`FAILED`로 변환하지 않습니다.
소유자 기반 checkpoint 커밋은 갱신된 version을 수신할 때까지 취소 불가 구간에서
완료하므로, 커밋 직후 취소되어도 오래된 owner lease가 남지 않습니다.

## 운영 {#operations}

adapter 경계에서 실행 상태, owner, lease 만료, version, 처리 건수와 checkpoint
redaction을 관측합니다. core는 데이터베이스 자원이나 애플리케이션 종료를
소유하지 않습니다.

## 테스트 {#testing}

`./gradlew :bluetape4k-exposed-batch-core:test`를 실행합니다. runner 취소,
close 순서, 이름 검증, 메모리 소유권 CAS, checkpoint 수명주기를 검증합니다.

## 학습 경로와 예제 {#workshops}

호환성 [배치 매뉴얼](bluetape4k-exposed-batch.md)에서 시작한 뒤
[JDBC adapter](bluetape4k-exposed-batch-jdbc.md) 또는
[R2DBC adapter](bluetape4k-exposed-batch-r2dbc.md)를 선택하세요.

## 제약 사항 {#limitations}

메모리 실행은 재시작 영속성이나 다중 프로세스 조정을 제공하지 않습니다.
core는 scheduler, schema migration, 데이터베이스 transaction을 제공하지 않습니다.

## 근거 자료 {#sources}

- [`BatchJobRepository.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/api/BatchJobRepository.kt)
- [`BatchStepRunner.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/core/BatchStepRunner.kt)
- [`CheckpointJson.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/CheckpointJson.kt)
