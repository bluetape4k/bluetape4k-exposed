# exposed-spring-boot-batch

English | [한국어](./README.ko.md)

**Spring Batch + Exposed Integration for Spring Boot**

A high-performance batch processing module that integrates Spring Batch with JetBrains Exposed.
Provides keyset-based pagination readers, efficient Exposed-backed writers, range partitioners
for VirtualThread parallel execution, and Spring Boot Auto-Configuration.

## Integration Map

![Spring Batch Exposed integration map](../../docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.png)

## Partitioned Restart Flow

![Partitioned keyset restart flow](../../docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.png)

## Features

- **`ExposedKeysetItemReader<T>`** — Keyset pagination reader
  - `WHERE column > lastKey AND column <= maxId ORDER BY column LIMIT pageSize`
  - Persists `lastKey` in `ExecutionContext` for restart support
  - Thread-safe with `reentrantLock().withLock { ... }` in `read()` (Virtual Thread-friendly)
  - Factory: `forEntityId(table, pageSize, rowMapper, database)`

- **`ExposedItemWriter<T>`** — Batch INSERT via Exposed `batchInsert`

- **`ExposedUpdateItemWriter<T>`** — Batch UPDATE via Exposed DSL

- **`ExposedUpsertItemWriter<T>`** — Batch UPSERT via Exposed `batchUpsert`

- **`ExposedRangePartitioner`** — Divides `[minId, maxId]` range into N partitions
  - Reads `MIN(id)` and `MAX(id)` from the table
  - Stores `minId` / `maxId` per partition in `ExecutionContext`

- **`ExposedBatchAutoConfiguration`** — Spring Boot Auto-Configuration
  - Registers `batchPartitionTaskExecutor` (configurable `TaskExecutor`)

- **`virtualThreadPartitionTaskExecutor(concurrencyLimit)`** — Helper to create a VirtualThread `TaskExecutor` with concurrency limit

- **`partitionedBatchJob` DSL** — Kotlin DSL for building partitioned `Job`

## Usage

### build.gradle.kts

```kotlin
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch")
```

### Auto-Configuration

`ExposedBatchAutoConfiguration` registers a default `batchPartitionTaskExecutor`
unless the application provides a bean with the same name or disables it.

```yaml
bluetape4k:
  batch:
    executor:
      enabled: true
      virtual-threads: true
      concurrency-limit: 8
      await-termination-seconds: 30
```

### Partitioned Migration Job

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

### Opting into multi-row VALUES

The existing `ExposedItemWriter(table) { ... }` constructor retains driver-level batching.
Select the Exposed 1.5.0 multi-row VALUES path explicitly:

```kotlin
val writer = ExposedItemWriter<TargetRecord>(
    table = TargetTable,
    useMultiRowValues = true,
) { record ->
    this[TargetTable.sourceName] = record.sourceName
    this[TargetTable.transformedValue] = record.transformedValue
}
```

- The default is `false`; existing positional/trailing-lambda calls and the JVM constructor remain compatible.
- Before invoking the binder or executing SQL, `true` validates `chunk rows × all table columns`.
  The estimated limit is 65,535 parameters (32,766 for SQLite). Oversized chunks throw
  `IllegalArgumentException` without automatic splitting. This estimate does not guarantee every driver's actual bind limit.
- Empty chunks are no-ops even without a transaction. Binding preserves input order and supports nullable values.
  Query ordering still requires an explicit `ORDER BY`.
- Generated keys are neither requested nor returned (`shouldReturnGeneratedValues = false`). There is no ignore-duplicates option.
- The writer neither opens nor commits a transaction. In a chunk step using `SpringTransactionManager`,
  a propagated failure rolls back the current chunk while previously committed chunks remain intact.
  Swallowing exceptions or changing skip/retry policies changes behavior according to the application's policy.
- New integration coverage targets H2/PostgreSQL JDBC. The new MySQL/Oracle/SQLite paths are unverified;
  check Exposed and driver dialect support and limits before using them.
- Test SQL counts refer to Exposed `StatementContext` entries. They distinguish multi-row VALUES SQL from
  row-wise batch SQL, but do not establish fewer network round trips or higher throughput.

### Restart Support

When the same job parameters are launched again after a failure, Spring Batch
restores each worker `ExecutionContext`. `ExposedKeysetItemReader` then resumes
from the saved `lastKey` inside that partition range.

```kotlin
// First run: fails after some chunks
val firstExecution = jobLauncher.run(job, params)  // BatchStatus.FAILED

// Second run with the same params: resumes from lastKey
val restartExecution = jobLauncher.run(job, params)  // BatchStatus.COMPLETED
```

## Module Dependencies

```
exposed-spring-boot-batch
  ├── spring-batch-core
  ├── spring-batch-test
  ├── exposed-jdbc
  └── bluetape4k-virtualthread-api
```
