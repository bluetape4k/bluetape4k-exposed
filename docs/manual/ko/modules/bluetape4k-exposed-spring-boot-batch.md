---
manualId: "bluetape4k-exposed-spring-boot-batch"
id: "bluetape4k-exposed-spring-boot-batch"
title: "Exposed Spring Boot Batch 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-batch"
sourceDir: "spring-boot/batch-exposed"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch
---

# Exposed Spring Boot Batch 연동

> Spring Boot의 Spring Batch 런타임 안에서 Exposed 범위 파티션, keyset 재시작, 청크 writer를 사용합니다.

## 제공하는 기능 {#problem}

이 모듈은 Spring Batch 애플리케이션에 Exposed 기반 `Partitioner`, `ItemStreamReader`, `ItemWriter`와 교체 가능한 파티션 executor를 추가합니다. 잡 기반 객체는 Spring Boot의 `BatchAutoConfiguration`에 의존합니다. `@EnableBatchProcessing`을 추가하면 지원하는 Boot 구성에서 필요한 Batch 자동 설정이 꺼지므로 사용하면 안 됩니다.

옆의 경량 런타임은 소유권 모델이 다릅니다.

![비교용 경량 BatchStepRunner 흐름](../../assets/batch/runtime.png)

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 이미 Spring Batch의 잡, 스텝, 청크 커밋, `ExecutionContext`, 재시작 메타데이터, 파티션 실행을 사용하면서 업무 테이블 읽기·쓰기에 Exposed를 적용하려 할 때 적합합니다. 자체 실행 저장소를 갖춘 작은 코루틴 runner면 충분하다면 [Exposed 배치 유틸리티](bluetape4k-exposed-batch.md)를 사용하세요.

## 의존성 좌표 {#coordinates}

생태계 BOM을 가져오면 모듈 버전을 따로 적지 않아도 됩니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch")
}
```

## 핵심 개념 {#concepts}

`ExposedRangePartitioner`는 단조 증가하는 `Long` 키의 최솟값과 최댓값을 읽고 각 파티션의 `ExecutionContext`에 `minId`와 `maxId`를 저장합니다. `ExposedKeysetItemReader`는 이 범위와 `lastKey`를 복원한 뒤 `column > lastKey AND column <= maxId ORDER BY column ASC LIMIT pageSize`로 읽습니다. `update`가 `lastKey`를 저장하므로 Spring Batch는 커밋된 청크 경계에서 진행 위치를 영속화합니다.

Exposed writer는 `SpringTransactionManager`를 통해 관리되는 Spring Batch 청크 트랜잭션 안에서 바로 실행합니다. 별도의 Exposed `transaction {}` 블록을 열지 않습니다.

## 빠르게 시작하기 {#quick-start}

Boot Batch 자동 설정을 유지하고 잡과 청크 기반 worker step을 정의한 뒤 manager step에 `ExposedRangePartitioner`를 연결합니다. 파티션마다 step scope `ExposedKeysetItemReader`를 만들고 worker step에는 `ExposedItemWriter`, `ExposedUpdateItemWriter`, `ExposedUpsertItemWriter` 중 하나를 선택합니다.

```yaml
spring:
  batch:
    job:
      enabled: false # 서비스 생명주기에 맞으면 잡을 명시적으로 실행
```

잡을 실행하려고 `@EnableBatchProcessing`을 추가하지 말고 Boot Batch 자동 설정 상태를 확인하세요.

## 작업별 API {#api-by-task}

- `Column<Long>`은 `ExposedRangePartitioner`, `LongIdTable`은 `forEntityId`로 나눕니다.
- 파티션 읽기는 `ExposedKeysetItemReader`, DAO ID 테이블은 해당 `forEntityId` 팩토리를 사용합니다.
- 청크 삽입은 `ExposedItemWriter`, 키별 갱신은 `ExposedUpdateItemWriter`, 데이터베이스가 지원하는 일괄 upsert는 `ExposedUpsertItemWriter`를 사용합니다.
- 파티션 handler에 `batchPartitionTaskExecutor`를 주입하거나 같은 이름의 빈을 제공해 기본 executor를 교체합니다.
- `BatchJobExtensions.kt`의 보조 기능으로 잡을 구성하되 트랜잭션 소유권은 Spring Batch에 둡니다.

## 권장 패턴 {#patterns}

실행 중 범위가 비교적 안정적인 고유 단조 증가 키로만 파티션을 나누세요. `gridSize`, executor 동시 수, 데이터베이스 풀 크기, downstream 쓰기 용량을 함께 맞춥니다. keyset 비교 의미에 빈틈이 생기지 않도록 정렬 기준을 유지하세요. 외부 부수 효과가 불확실한 상태에서 잡을 재시작할 수 있다면 upsert 같은 멱등 쓰기를 사용합니다.

## 연동 {#integrations}

`ExposedBatchAutoConfiguration`은 `BatchAutoConfiguration` 뒤에 실행되고 Spring Batch `Job` 클래스가 있을 때만 활성화됩니다. `ExposedBatchProperties`와, 설정이 켜져 있고 같은 이름의 빈이 없을 때 `batchPartitionTaskExecutor`만 제공합니다. 기본 구현은 virtual thread가 활성화된 `SimpleAsyncTaskExecutor`이며 애플리케이션이 같은 이름의 빈을 정의하면 교체됩니다.

## 설정 {#configuration}

`bluetape4k.batch.executor.enabled=false`로 기본 executor를 끌 수 있습니다. `virtual-threads` 기본값은 `true`, `concurrency-limit`는 사용 가능한 프로세서 수의 두 배, `await-termination-seconds`는 30입니다. 이는 executor 제한이지 데이터베이스 처리 용량 보장이 아닙니다. 측정 결과에 따라 reader `pageSize`, 파티션 `gridSize`, Spring Batch 청크 크기, 데이터소스 풀을 함께 정하세요.

## 실패 유형과 해결 방법 {#failures}

- `@EnableBatchProcessing`을 추가한 뒤 Boot Batch 기반 객체가 없음: 어노테이션을 제거하고 Boot 자동 설정을 사용합니다.
- 파티션에서 행이 반복되거나 빠짐: 고유 단조 증가 키, 안정된 `minId`·`maxId`, `lastKey` 복원을 확인합니다.
- 예상보다 앞에서 재시작함: 커밋된 청크만 Spring Batch `ExecutionContext`의 영속 진행 위치를 갱신하므로 마지막 커밋 스텝 상태를 봅니다.
- writer에서 Exposed 트랜잭션이 없다고 나옴: 스텝이 같은 데이터소스에 연결된 `SpringTransactionManager`를 사용하는지 확인하고 별도 중첩 트랜잭션을 열지 않습니다.
- virtual thread 동시 수가 풀을 압도함: `concurrency-limit`, 파티션 수, 청크 부하를 줄입니다. virtual thread가 데이터베이스 연결을 늘려 주지는 않습니다.

## 운영 {#operations}

잡·스텝 상태, 파티션 이름, `minId`, `maxId`, `lastKey`, 읽기·쓰기·skip 건수, 커밋 횟수, executor 동시 수, 풀 연결 획득, 청크 지연을 관찰하세요. 재시작할 때 복원된 `lastKey`와 마지막 커밋 업무 행을 비교합니다. 메모리 reader 버퍼는 영속 상태가 아니며 재시작 기준은 `ExecutionContext`입니다.

## 테스트 {#testing}

정확한 모듈 테스트 명령은 다음과 같습니다.

```bash
./gradlew :bluetape4k-exposed-spring-boot-batch:test
```

`@EnableBatchProcessing` 없이 Boot 자동 설정이 동작하는지, 기본·교체 executor 빈, 빈 범위와 고르지 않은 범위, 안정된 파티션 경계, `lastKey` keyset 재시작, 청크 롤백, 커밋된 청크 이후 재시작, 삽입·갱신·upsert writer가 중첩 트랜잭션 없이 기존 청크 트랜잭션에 참여하는지 검증하세요.

## 학습 경로와 예제 {#workshops}

런타임을 선택하기 전에 [Exposed 배치 유틸리티](bluetape4k-exposed-batch.md)와 비교하고 [트랜잭션 경계](../guides/transaction-boundaries.md)를 읽으세요. 이 모듈의 end-to-end 및 restart 테스트를 Spring Batch 실행 계약으로 삼습니다.

## 제약 사항 {#limitations}

범위 파티션은 `Long`으로 비교할 수 있는 고유 단조 증가 키를 전제로 하며 실행 중 대량 삽입·삭제로 범위가 크게 바뀌지 않을 때 적합합니다. `lastKey`는 Spring Batch checkpoint의 읽기 진행 상태이며 외부 부수 효과까지 원자적으로 만들지 않습니다. 기본 virtual-thread executor는 선택적이고 교체할 수 있으며 데이터베이스 풀 한도를 지켜야 합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `1.12.1` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Spring Batch Exposed integration 지도

[![Spring Batch Exposed integration 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.svg)

_배포본 README: [`spring-boot/batch-exposed/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/batch-exposed/README.ko.md)_

### Partitioned keyset restart 흐름

[![Partitioned keyset restart 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.svg)

_배포본 README: [`spring-boot/batch-exposed/README.ko.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/batch-exposed/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [`ExposedBatchAutoConfiguration.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/config/ExposedBatchAutoConfiguration.kt)
- [`ExposedBatchProperties.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/config/ExposedBatchProperties.kt)
- [`ExposedRangePartitioner.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/partition/ExposedRangePartitioner.kt)
- [`ExposedKeysetItemReader.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/reader/ExposedKeysetItemReader.kt)
- [`ExposedItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedItemWriter.kt)
- [`ExposedUpdateItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedUpdateItemWriter.kt)
- [`ExposedUpsertItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedUpsertItemWriter.kt)
