# exposed-spring-boot-batch

[English](./README.md) | 한국어

**Spring Boot + Spring Batch + Exposed 통합 모듈**

Spring Batch와 JetBrains Exposed를 통합하는 고성능 배치 처리 모듈입니다.
Keyset 기반 페이지 읽기 Reader, Exposed 기반 Writer, VirtualThread 병렬 실행을 위한
Range Partitioner, Spring Boot Auto-Configuration을 제공합니다.

## 통합 맵

![Spring Batch Exposed integration map](../../docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.png)

## 파티션 재시작 흐름

![Partitioned keyset restart flow](../../docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.png)

## 주요 기능

- **`ExposedKeysetItemReader<T>`** — Keyset 페이지 읽기 Reader
  - `WHERE column > lastKey AND column <= maxId ORDER BY column LIMIT pageSize`
  - 재시작 시 `lastKey`를 `ExecutionContext`에 저장하여 마지막 위치부터 재개
  - `read()`에서 `reentrantLock().withLock { ... }`로 스레드 안전 보장 (Virtual Thread 친화적)
  - 팩토리: `forEntityId(table, pageSize, rowMapper, database)`

- **`ExposedItemWriter<T>`** — Exposed `batchInsert` 기반 대량 INSERT

- **`ExposedUpdateItemWriter<T>`** — Exposed DSL 기반 대량 UPDATE

- **`ExposedUpsertItemWriter<T>`** — Exposed `batchUpsert` 기반 대량 UPSERT

- **`ExposedRangePartitioner`** — `[minId, maxId]` 범위를 N개 파티션으로 분할
  - 테이블에서 `MIN(id)` / `MAX(id)` 자동 조회
  - 파티션별 `minId` / `maxId`를 `ExecutionContext`에 저장

- **`ExposedBatchAutoConfiguration`** — Spring Boot Auto-Configuration
  - `batchPartitionTaskExecutor` (설정 가능한 `TaskExecutor`) 자동 등록

- **`virtualThreadPartitionTaskExecutor(concurrencyLimit)`** — 동시성 제한 VirtualThread `TaskExecutor` 생성 헬퍼

- **`partitionedBatchJob` DSL** — 파티션된 `Job` 빌드를 위한 Kotlin DSL

## 사용 예시

### build.gradle.kts

```kotlin
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch")
```

### Auto-Configuration

`ExposedBatchAutoConfiguration`은 애플리케이션이 같은 이름의 빈을 제공하지
않고 설정으로 비활성화하지 않은 경우 기본 `batchPartitionTaskExecutor`를
등록합니다.

```yaml
bluetape4k:
  batch:
    executor:
      enabled: true
      virtual-threads: true
      concurrency-limit: 8
      await-termination-seconds: 30
```

### 파티션 마이그레이션 Job

```kotlin
@TestConfiguration
class MigrationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val database: Database,
) {
    @Bean
    fun migrationJob(): Job = partitionedBatchJob("my-migration-job", jobRepository) {
        start(partitionedStep())
    }

    @Bean
    fun partitionedStep(): Step = StepBuilder("migration-manager", jobRepository)
        .partitioner("migration-worker", rangePartitioner())
        .partitionHandler(partitionHandler())
        .build()

    @Bean
    fun rangePartitioner(): ExposedRangePartitioner = ExposedRangePartitioner.forEntityId(
        table = SourceTable,
        gridSize = 4,
        database = database,
    )

    @Bean
    fun partitionHandler(): TaskExecutorPartitionHandler = TaskExecutorPartitionHandler().apply {
        setStep(workerStep())
        setTaskExecutor(virtualThreadPartitionTaskExecutor(concurrencyLimit = 4))
        gridSize = 4
    }

    @Bean
    fun workerStep(): Step = StepBuilder("migration-worker", jobRepository)
        .chunk<SourceRecord, TargetRecord>(500, transactionManager)
        .reader(keysetReader())
        .processor(ItemProcessor { source ->
            TargetRecord(sourceName = source.name.uppercase(), transformedValue = source.value * 2)
        })
        .writer(itemWriter())
        .build()

    @Bean
    @StepScope
    fun keysetReader(): ExposedKeysetItemReader<SourceRecord> = ExposedKeysetItemReader.forEntityId(
        table = SourceTable,
        pageSize = 500,
        rowMapper = { row ->
            SourceRecord(id = row[SourceTable.id].value, name = row[SourceTable.name], value = row[SourceTable.value])
        },
        database = database,
    )

    @Bean
    fun itemWriter(): ExposedItemWriter<TargetRecord> = ExposedItemWriter(table = TargetTable) {
        this[TargetTable.sourceName] = it.sourceName
        this[TargetTable.transformedValue] = it.transformedValue
    }
}
```

### multi-row VALUES 선택

기존 `ExposedItemWriter(table) { ... }`는 driver-level batch 경로를 유지합니다.
Exposed 1.5.0의 multi-row VALUES는 생성자에서 명시적으로 선택합니다.

```kotlin
val writer = ExposedItemWriter<TargetRecord>(
    table = TargetTable,
    useMultiRowValues = true,
) { record ->
    this[TargetTable.sourceName] = record.sourceName
    this[TargetTable.transformedValue] = record.transformedValue
}
```

- 기본값은 `false`이며 기존 positional·trailing-lambda 호출과 JVM 생성자를 유지합니다.
- `true`는 SQL 실행과 바인더 호출 전에 `청크 행 수 × 전체 테이블 컬럼 수`를 검사합니다.
  추정 한도는 65,535개(SQLite 32,766개)입니다. 초과 청크는 `IllegalArgumentException`으로
  거부하며 자동 분할하지 않습니다. 이 추정치는 모든 driver의 실제 bind 한도를 보장하지 않습니다.
- 빈 청크는 트랜잭션 없이도 no-op입니다. 입력 순서대로 바인딩하며 nullable 값을 지원합니다.
  조회 순서가 필요하면 별도 `ORDER BY`를 지정해야 합니다.
- 생성 키를 요청하거나 반환하지 않습니다(`shouldReturnGeneratedValues = false`). 중복 무시 옵션은 없습니다.
- writer는 트랜잭션을 만들거나 commit하지 않습니다. `SpringTransactionManager`를 사용하는 청크 step에서
  실패가 전파되면 현재 청크가 rollback되고, 앞서 commit한 청크는 유지됩니다.
  애플리케이션이 예외를 삼키거나 skip/retry 정책을 바꾸면 그 정책에 따라 동작합니다.
- H2/PostgreSQL JDBC가 신규 검증 대상입니다. MySQL/Oracle/SQLite의 신규 경로는 미검증이며,
  사용 전 Exposed와 해당 driver의 방언 지원·한도를 확인해야 합니다.
- 테스트의 SQL 수는 Exposed `StatementContext` 기준입니다. 여러 행의 VALUES SQL과 기존 행별
  batch SQL을 구분하는 근거이며, JDBC 네트워크 왕복 감소나 처리량 향상을 의미하지 않습니다.

### 재시작 지원

동일한 Job 파라미터로 재실행하면 Spring Batch가 각 worker의 `ExecutionContext`를
복원하고, `ExposedKeysetItemReader`가 해당 partition 범위 안에서 저장된
`lastKey` 이후부터 다시 읽습니다.

```kotlin
// 1차 실행: 중간에 실패
val firstExecution = jobLauncher.run(job, params)  // BatchStatus.FAILED

// 2차 실행: 동일 params — lastKey부터 재개
val restartExecution = jobLauncher.run(job, params)  // BatchStatus.COMPLETED
```

## 모듈 의존성

```
exposed-spring-boot-batch
  ├── spring-batch-core
  ├── spring-batch-test
  ├── exposed-jdbc
  └── bluetape4k-virtualthread-api
```
