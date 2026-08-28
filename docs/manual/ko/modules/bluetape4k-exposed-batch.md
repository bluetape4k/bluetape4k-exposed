---
manualId: "bluetape4k-exposed-batch"
id: "bluetape4k-exposed-batch"
title: "Exposed 배치 유틸리티"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-batch"
sourceDir: "utils/batch"
releaseRef: "1.12.1"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-batch
---

# Exposed 배치 유틸리티

> lease, checkpoint, retry, skip과 JDBC/R2DBC 실행 저장소를 명시적으로 다루는 경량 코루틴 배치 런타임입니다.

## 제공하는 기능 {#problem}

`BatchStepRunner`는 하나의 `BatchStep`을 재시작 가능한 청크 반복문으로 실행합니다. 잡과 스텝 실행 lease를 획득하고, 이미 `COMPLETED` 또는 `COMPLETED_WITH_SKIPS`인 스텝은 건너뜁니다. 실행 대상이면 reader와 writer를 열고 checkpoint를 복원한 뒤 읽기·처리·쓰기·checkpoint 저장을 반복합니다. 이 모듈은 자체 런타임이며 Spring Batch를 감싼 구현이 아닙니다.

![BatchStepRunner 실행 흐름](../../assets/batch/runtime.png)

## 사용하기 좋은 경우 {#when-to-use}

작업을 `BatchReader` → 선택적 `BatchProcessor` → `BatchWriter`로 표현할 수 있고 작은 코루틴 배치 런타임이 필요할 때 사용하세요. 프로세스가 종료되어도 재개해야 한다면 영속 JDBC 또는 R2DBC `BatchJobRepository`를 선택합니다. Spring Batch의 잡 저장소, 스텝 생태계, 파티셔닝, 스케줄링 연동, 운영 도구가 필요하다면 Spring Batch를 사용하세요.

## 의존성 좌표 {#coordinates}

생태계 BOM을 가져오면 모듈 버전을 따로 적지 않아도 됩니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-batch")
}
```

## 핵심 개념 {#concepts}

잡과 각 스텝에는 상태, 처리 건수, 소유자, lease 만료 시각, 버전, checkpoint가 저장됩니다. 저장소는 소유권을 원자적으로 획득해야 합니다. 획득에 실패하면 스텝 자원을 열지 않고 `FAILED` 보고서를 반환합니다. 완료된 스텝도 다시 열지 않고 기존 결과를 즉시 반환합니다. 미완료 스텝은 저장된 checkpoint가 있을 때만 첫 읽기 전에 복원합니다.

각 청크에서 processor 예외는 `SkipPolicy`로 판단합니다. 통과한 출력은 writer에 전달합니다. `writer.write()`가 성공하면 먼저 write count에 반영하고, 그 뒤 writer retry·skip 범위 밖에서 `reader.onChunkCommitted()`를 호출하고 `reader.checkpoint()`를 저장합니다. writer 재시도가 소진되어 skip한 청크는 커밋된 checkpoint를 전진시키지 않습니다. 따라서 checkpoint 저장이 실패해도 이미 완료된 write를 보고하며 같은 실행에서 청크를 다시 writer에 전달하지 않습니다. 이 순서가 재시작 의미를 결정합니다.

## 빠르게 시작하기 {#quick-start}

배치 DSL로 잡과 스텝을 정의하고 reader, writer, 청크 크기, retry 정책, skip 정책, 커밋 제한 시간, 저장소를 제공합니다. 처음에는 `SkipPolicy.NONE`과 replay에 안전한 writer를 사용하세요. `InMemoryBatchJobRepository`는 테스트 또는 폐기 가능한 단일 프로세스 실행에만 적합합니다.

```bash
./gradlew \
  :bluetape4k-exposed-batch-core:test \
  :bluetape4k-exposed-batch-jdbc:test \
  :bluetape4k-exposed-batch-r2dbc:test \
  :bluetape4k-exposed-batch:test
```

하위 모듈 테스트에서 완료 스텝 단축 실행, checkpoint 복원, retry·skip,
취소, JDBC/R2DBC 저장소 영속화를 실행 가능한 계약으로 확인할 수 있습니다.
호환성 aggregator의 `test` task는 schema parity와 패키징 소유권만 검사하며
하위 모듈 테스트를 대신하지 않습니다.

## 작업별 API {#api-by-task}

- `BatchJob`, `BatchStep` 또는 DSL builder로 작업 정의를 만듭니다.
- `BatchReader.open/read/checkpoint/restoreFrom/onChunkCommitted/close`를 구현합니다.
- 입력과 쓰기 출력이 다르거나 항목을 걸러야 할 때 `BatchProcessor`를 추가합니다.
- `BatchWriter.open/write/close`를 구현합니다. `write`에는 통과한 청크 하나가 전달됩니다.
- `SkipPolicy.NONE`, `ALL`, `maxSkips` 또는 업무 전용 정책을 선택합니다.
- 재시작 상태를 영속화하려면 `ExposedJdbcBatchJobRepository` 또는 `ExposedR2dbcBatchJobRepository`를 사용합니다.

## 권장 패턴 {#patterns}

자연 키, upsert, compare-and-set, 처리 원장으로 writer를 멱등하게 만드세요. reader 정렬을 안정적으로 유지하고 모호하지 않게 재개할 수 있는 상태를 checkpoint에 담습니다. lease 기간은 정상 청크 지연보다 길게 잡고 retry 횟수에는 상한을 둡니다. processor의 항목 skip과 writer의 청크 skip은 업무 의미가 다릅니다. writer retry가 소진되면 청크 전체가 skip 대상입니다.

## 연동 {#integrations}

`ExposedJdbcBatchJobRepository`는 잡, 스텝, lease, 처리 건수, 상태, checkpoint를 Exposed JDBC로 저장합니다. `ExposedR2dbcBatchJobRepository`는 같은 역할을 suspend 가능한 R2DBC 방식으로 제공합니다. 방식에 맞는 JDBC/R2DBC reader와 writer도 있습니다. 이 저장소는 경량 런타임 전용이며 Spring Batch 메타데이터 스키마나 트랜잭션 규칙과 별개입니다.

## 설정 {#configuration}

데이터베이스 문장 제한, 행 크기, 측정한 트랜잭션 지연을 바탕으로 청크 크기를 정합니다. retry 횟수, 최초 대기 시간, backoff 배수, 최대 대기 시간, skip 정책, 커밋 제한 시간, lease 만료를 의도적으로 설정하세요. 영속 저장소를 사용하기 전에 배치 테이블을 만들고, JDBC `Database` 또는 R2DBC `R2dbcDatabase`와 커넥션 풀은 애플리케이션이 제공하고 소유합니다.

## 실패 유형과 해결 방법 {#failures}

- 다른 소유자가 유효한 lease를 가지고 있음: 소유권 획득에 실패하며 runner는 스텝을 처리하면 안 됩니다.
- 쓰기는 성공했지만 checkpoint 저장 전에 프로세스가 종료됨: 재시작할 때 같은 청크가 다시 실행될 수 있습니다.
- 성공적으로 커밋한 뒤 다음 청크가 실패하면 runner는 마지막 성공 checkpoint를 보고하고 보존합니다. 실패 처리에 새 값이 없어도 완료 갱신이 저장된 checkpoint를 지우지 않으므로 재실행은 마지막으로 커밋한 key 다음부터 시작합니다.
- 시간 초과된 쓰기가 외부에 일부 반영됨: 시간 초과만으로 롤백을 단정하지 말고 retry 전에 정합성을 확인합니다.
- writer retry가 소진되고 skip 정책이 허용함: 청크 전체가 `skipCount`에 더해지므로 이 범위를 고려해 정책을 정합니다.
- checkpoint가 없음: `restoreFrom`을 호출하지 않고 reader의 최초 위치에서 시작합니다.

## 운영 {#operations}

잡·스텝 ID, 소유자와 lease 만료, 상태, checkpoint, 읽기·쓰기·skip 건수, retry 횟수, 청크 지연, 시간 초과 오류를 기록하세요. 만료된 `RUNNING` lease와 반복되는 `FAILED`·`STOPPED` 복구에 경고를 겁니다. replay와 일부 부수 효과를 진단할 수 있을 만큼 실행 이력을 보존하세요.

## 테스트 {#testing}

네 모듈 테스트 task를 함께 실행합니다.

```bash
./gradlew \
  :bluetape4k-exposed-batch-core:test \
  :bluetape4k-exposed-batch-jdbc:test \
  :bluetape4k-exposed-batch-r2dbc:test \
  :bluetape4k-exposed-batch:test
```

core task는 자원을 열지 않는 완료 스텝 단축 실행, checkpoint 동작,
processor skip, writer retry·backoff, 취소, 인메모리 저장소를 검증합니다.
JDBC와 R2DBC task는 lease 경쟁, 쓰기 시간 초과, 쓰기 뒤 checkpoint 전 장애,
영속 저장소 재시작을 각각 검증합니다. aggregator task의 범위는 schema parity와
패키징 검사입니다.

취소는 별도로 단언해야 합니다. `CancellationException`은 일반 실패로 바꾸지 않습니다. runner는 `NonCancellable` 정리 구간에서 `STOPPED` 보고서 저장을 시도하고 reader와 writer를 각각 닫은 뒤 취소를 다시 던집니다.

## 학습 경로와 예제 {#workshops}

[트랜잭션 경계](../guides/transaction-boundaries.md)를 읽은 뒤 [Spring Boot Batch 연동](bluetape4k-exposed-spring-boot-batch.md)과 실행 모델을 비교하세요. 사용자 정의 reader나 writer를 만들기 전에 영속 저장소 테스트를 재시작 계약으로 삼는 것이 좋습니다.

## 제약 사항 {#limitations}

쓰기와 checkpoint 사이의 경계는 at-least-once이며 exactly-once가 아닙니다. `InMemoryBatchJobRepository`는 재시작 상태를 영속화하지 않고 여러 프로세스를 조정할 수도 없습니다. 이 런타임은 스케줄러, 큐, Spring Batch 대체재가 아닙니다. 올바른 복구에는 원자적 lease 구현, 영속 checkpoint, 안정된 reader 정렬, 멱등 writer가 필요합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Batch benchmark comparison 지도

[![Batch benchmark comparison 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-benchmark-map-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-benchmark-map-01.svg)

_배포본 README: [`utils/batch/benchmark/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/utils/batch/benchmark/README.ko.md)_

### Batch 런타임 role 지도

[![Batch 런타임 role 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-diagram-01.svg)

_배포본 README: [`utils/batch/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/utils/batch/README.ko.md)_

### Batch chunk checkpoint 흐름

[![Batch chunk checkpoint 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/utils-batch-sequence-01.svg)

_배포본 README: [`utils/batch/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/utils/batch/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [`BatchStepRunner.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/core/BatchStepRunner.kt)
- [`BatchStep.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/core/BatchStep.kt)
- [`BatchJobRepository.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/api/BatchJobRepository.kt)
- [`BatchReader.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/api/BatchReader.kt)
- [`BatchWriter.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/api/BatchWriter.kt)
- [`SkipPolicy.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/api/SkipPolicy.kt)
- [`InMemoryBatchJobRepository.kt`](../../../../utils/batch/core/src/main/kotlin/io/bluetape4k/batch/core/InMemoryBatchJobRepository.kt)
- [`ExposedJdbcBatchJobRepository.kt`](../../../../utils/batch/jdbc/src/main/kotlin/io/bluetape4k/batch/jdbc/ExposedJdbcBatchJobRepository.kt)
- [`ExposedR2dbcBatchJobRepository.kt`](../../../../utils/batch/r2dbc/src/main/kotlin/io/bluetape4k/batch/r2dbc/ExposedR2dbcBatchJobRepository.kt)
