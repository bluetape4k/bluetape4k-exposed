# PostgreSQL Benchmark Details

[Benchmark Hub](./README.md) · [벤치마크 허브](./README.ko.md)

## Profiles

| Driver | Gradle Task | Benchmark Class |
|--------|-------------|-----------------|
| JDBC | `./gradlew :bluetape4k-exposed-batch:postgresJdbcBenchmark` | `PostgreSqlJdbcBatchBenchmark` |
| R2DBC | `./gradlew :bluetape4k-exposed-batch:postgresR2dbcBenchmark` | `PostgreSqlR2dbcBatchBenchmark` |

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

![postgresql seed dataSize chart](../../../docs/images/readme-charts/utils-batch-postgresql-seed-datasize-chart-01.png)

### Seed — poolSize comparison (dataSize=10000)

![postgresql seed poolSize chart](../../../docs/images/readme-charts/utils-batch-postgresql-seed-poolsize-chart-01.png)

### End-to-End — parallelism comparison (dataSize=10000, poolSize=30)

![postgresql end-to-end parallelism chart](../../../docs/images/readme-charts/utils-batch-postgresql-e2e-parallelism-chart-01.png)

## Notes

- PostgreSQL benchmark는 Testcontainers를 자동 기동하도록 설계되어 있습니다.
- JDBC vs R2DBC 격차를 가장 명확하게 보여주는 대표 DB입니다.

## Generated Result Rows

> JSON benchmark reports are not available in the current worktree yet, so this document records the benchmark contract, task mapping, and graph layout first. Numeric rows can be appended by rerunning the corresponding benchmark tasks and `generateBenchmarkDocs`.
