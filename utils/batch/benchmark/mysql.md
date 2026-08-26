# MySQL Benchmark Details

[Benchmark Hub](./README.md) · [벤치마크 허브](./README.ko.md)

## Profiles

| Driver | Gradle Task | Benchmark Class |
|--------|-------------|-----------------|
| JDBC | `./gradlew :bluetape4k-exposed-batch:mysqlJdbcBenchmark` | `MySqlJdbcBatchBenchmark` |
| R2DBC | `./gradlew :bluetape4k-exposed-batch:mysqlR2dbcBenchmark` | `MySqlR2dbcBatchBenchmark` |

## Comparison Dimensions

| Scenario | JDBC vs R2DBC 비교 축 | 고정/가변 파라미터 |
|----------|-----------------------|-------------------|
| Seed | source row insert throughput / time | dataSize = 1000, 10000, 100000 · poolSize = 10, 30, 60 |
| End-to-End | full batch job throughput / time | dataSize = 1000, 10000, 100000 · poolSize = 10, 30, 60 · parallelism = 1, 4, 8 |

## Result Tables

### Seed Benchmark — JDBC vs R2DBC by dataSize / poolSize

| Driver | dataSize | poolSize | ops/sec | avg ms |
|--------|----------|----------|--------:|-------:|
| JDBC | 1000 | 10 | pending | pending |
| JDBC | 10000 | 30 | pending | pending |
| JDBC | 100000 | 60 | pending | pending |
| R2DBC | 1000 | 10 | pending | pending |
| R2DBC | 10000 | 30 | pending | pending |
| R2DBC | 100000 | 60 | pending | pending |

### End-to-End Benchmark — JDBC vs R2DBC by dataSize / poolSize / parallelism

| Driver | dataSize | poolSize | parallelism | ops/sec | avg ms |
|--------|----------|----------|-------------|--------:|-------:|
| JDBC | 1000 | 10 | 1 | pending | pending |
| JDBC | 10000 | 30 | 4 | pending | pending |
| JDBC | 100000 | 60 | 8 | pending | pending |
| R2DBC | 1000 | 10 | 1 | pending | pending |
| R2DBC | 10000 | 30 | 4 | pending | pending |
| R2DBC | 100000 | 60 | 8 | pending | pending |

## Comparison Charts

> Chart image paths are reserved for generated benchmark reports. Re-run the benchmark tasks and `generateBenchmarkDocs` after JSON reports exist.

### Seed — dataSize comparison (poolSize=30)

![mysql seed dataSize chart](../../../docs/images/readme-charts/utils-batch-mysql-seed-datasize-chart-01.png)

### Seed — poolSize comparison (dataSize=10000)

![mysql seed poolSize chart](../../../docs/images/readme-charts/utils-batch-mysql-seed-poolsize-chart-01.png)

### End-to-End — parallelism comparison (dataSize=10000, poolSize=30)

![mysql end-to-end parallelism chart](../../../docs/images/readme-charts/utils-batch-mysql-e2e-parallelism-chart-01.png)

## Notes

- MySQL benchmark도 Testcontainers 자동 기동을 전제로 합니다.
- 대규모 batch에서 JDBC + Virtual Threads의 이점이 드러나는 비교 대상입니다.

## Generated Result Rows

> JSON benchmark reports are not available in the current worktree yet, so this document records the benchmark contract, task mapping, and graph layout first. Numeric rows can be appended by rerunning the corresponding benchmark tasks and `generateBenchmarkDocs`.
