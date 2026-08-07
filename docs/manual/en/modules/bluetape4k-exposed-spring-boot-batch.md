---
manualId: "bluetape4k-exposed-spring-boot-batch"
id: "bluetape4k-exposed-spring-boot-batch"
title: "Exposed Spring Boot Batch Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-spring-boot-batch"
sourceDir: "spring-boot/batch-exposed"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch
---

# Exposed Spring Boot Batch Integration

> Exposed range partitioning, keyset restart, and chunk writers inside Spring Boot's Spring Batch runtime.

## Problem {#problem}

This module adds Exposed-aware `Partitioner`, `ItemStreamReader`, and `ItemWriter` implementations plus an overridable partition executor to a Spring Batch application. It relies on Spring Boot's `BatchAutoConfiguration` for job infrastructure. Do **not** add `@EnableBatchProcessing`: in the supported Boot configuration it disables the Boot Batch auto-configuration this integration expects.

The adjacent lightweight runtime has a different ownership model:

![Lightweight BatchStepRunner flow for comparison](../../assets/batch/runtime.png)

## When to use it {#when-to-use}

Use this module when the application already uses Spring Batch jobs, steps, chunk commits, `ExecutionContext`, restart metadata, and partition execution, but wants Exposed to read and write application tables. Use [Exposed Batch Utilities](bluetape4k-exposed-batch.md) instead when a small coroutine runner with its own execution repository is sufficient.

## Coordinates {#coordinates}

Import the ecosystem BOM and omit the module version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-batch")
}
```

## Core concepts {#concepts}

`ExposedRangePartitioner` reads the minimum and maximum of a monotonic `Long` key and writes `minId` and `maxId` into each partition's `ExecutionContext`. `ExposedKeysetItemReader` restores those bounds plus `lastKey`, then reads `column > lastKey AND column <= maxId ORDER BY column ASC LIMIT pageSize`. Its `update` method saves `lastKey`, so Spring Batch persists progress at a committed chunk boundary.

The Exposed writers execute directly in the Spring Batch chunk transaction managed through `SpringTransactionManager`. They intentionally do not open a nested Exposed `transaction {}` block.

## Quick start {#quick-start}

Keep Boot Batch auto-configuration enabled, define the job and chunk-oriented worker step, and attach an `ExposedRangePartitioner` to the manager step. Create a step-scoped `ExposedKeysetItemReader` for each partition and choose `ExposedItemWriter`, `ExposedUpdateItemWriter`, or `ExposedUpsertItemWriter` for the worker step.

```yaml
spring:
  batch:
    job:
      enabled: false # launch jobs explicitly when that matches the service lifecycle
```

Do not add `@EnableBatchProcessing` merely to make the job run; diagnose Boot Batch auto-configuration instead.

## API by task {#api-by-task}

- Partition a `Column<Long>` with `ExposedRangePartitioner`; use `forEntityId` for a `LongIdTable`.
- Read a partition with `ExposedKeysetItemReader`; use its `forEntityId` factory for DAO ID tables.
- Insert chunks with `ExposedItemWriter`, update each key with `ExposedUpdateItemWriter`, or use database-supported batch upsert through `ExposedUpsertItemWriter`.
- Inject the bean named `batchPartitionTaskExecutor` into a partition handler, or provide a bean with that name to replace the default.
- Configure jobs with the helpers in `BatchJobExtensions.kt` while leaving transaction ownership with Spring Batch.

## Recommended patterns {#patterns}

Partition only on a unique, monotonic key whose range remains reasonably stable during a run. Keep `gridSize`, executor concurrency, database pool size, and downstream write capacity aligned. Use keyset ordering without gaps in the comparison semantics. Prefer upsert or another idempotent write when an operator may restart a job after uncertain external effects.

## Integrations {#integrations}

`ExposedBatchAutoConfiguration` runs after `BatchAutoConfiguration` and is conditional on Spring Batch `Job`. It contributes only `ExposedBatchProperties` and, when enabled and missing, `batchPartitionTaskExecutor`. The default is `SimpleAsyncTaskExecutor` with virtual threads enabled; an application-defined bean of the same name takes precedence.

## Configuration {#configuration}

`bluetape4k.batch.executor.enabled=false` disables the default executor. `virtual-threads` defaults to `true`; `concurrency-limit` defaults to twice the available processors; `await-termination-seconds` defaults to 30. These are executor limits, not database capacity guarantees. Set the reader `pageSize`, partition `gridSize`, Spring Batch chunk size, and datasource pool together from measurements.

## Failure modes {#failures}

- Boot Batch infrastructure is missing after adding `@EnableBatchProcessing`: remove the annotation and let Boot auto-configure it.
- A partition repeats or misses rows: verify a unique monotonic key, stable `minId`/`maxId`, and `lastKey` restoration.
- Restart begins before expected data: only a committed chunk advances durable Spring Batch `ExecutionContext`; inspect the last committed step state.
- Writer reports no Exposed transaction: verify the step uses the `SpringTransactionManager` connected to the same datasource and do not open an unrelated nested transaction.
- Virtual-thread concurrency overwhelms the pool: reduce `concurrency-limit`, partition count, or chunk pressure; virtual threads do not create database connections.

## Operations {#operations}

Observe job and step status, partition name, `minId`, `maxId`, `lastKey`, read/write/skip counts, commit count, executor concurrency, pool acquisition, and chunk latency. On restart, compare the restored `lastKey` with the last committed business rows. An in-memory reader buffer is not durable; `ExecutionContext` is the restart authority.

## Testing {#testing}

Run the exact module tests:

```bash
./gradlew :bluetape4k-exposed-spring-boot-batch:test
```

Verify Boot auto-configuration without `@EnableBatchProcessing`, default and overridden executor beans, empty and uneven ranges, stable partition bounds, keyset restart from `lastKey`, chunk rollback, restart after a committed chunk, and insert/update/upsert writers participating in the existing chunk transaction without a nested transaction.

## Workshops and learning path {#workshops}

Compare [Exposed Batch Utilities](bluetape4k-exposed-batch.md) before choosing a runtime, then read [transaction boundaries](../guides/transaction-boundaries.md). Use the end-to-end and restart tests in this module as the executable Spring Batch contract.

## Limitations {#limitations}

Range partitioning assumes a `Long`-compatible, unique, monotonic key and is best when large inserts or deletes do not reshape the range during execution. `lastKey` records read progress at Spring Batch checkpoints; it does not make external side effects atomic. The default virtual-thread executor is optional and replaceable, and must still respect database pool limits.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Spring Batch Exposed integration map

[![Spring Batch Exposed integration map](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-diagram-01.svg)

_Release README: [`spring-boot/batch-exposed/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/batch-exposed/README.md)_

### Partitioned keyset restart flow

[![Partitioned keyset restart flow](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-batch-exposed-sequence-01.svg)

_Release README: [`spring-boot/batch-exposed/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/spring-boot/batch-exposed/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [`ExposedBatchAutoConfiguration.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/config/ExposedBatchAutoConfiguration.kt)
- [`ExposedBatchProperties.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/config/ExposedBatchProperties.kt)
- [`ExposedRangePartitioner.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/partition/ExposedRangePartitioner.kt)
- [`ExposedKeysetItemReader.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/reader/ExposedKeysetItemReader.kt)
- [`ExposedItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedItemWriter.kt)
- [`ExposedUpdateItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedUpdateItemWriter.kt)
- [`ExposedUpsertItemWriter.kt`](../../../../spring-boot/batch-exposed/src/main/kotlin/io/bluetape4k/spring/batch/exposed/writer/ExposedUpsertItemWriter.kt)
