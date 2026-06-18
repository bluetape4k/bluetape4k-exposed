# exposed-batch Benchmark Hub

[한국어](./README.ko.md) | English

This directory contains DB-specific benchmark notes for the new `kotlinx-benchmark` setup.

## Scope

- Databases: H2, PostgreSQL, MySQL
- Drivers: JDBC with Virtual Threads, R2DBC
- Scenarios: `seedBenchmark`, `endToEndBatchJobBenchmark`
- Parameters: `dataSize = 1000/10000/100000`, `poolSize = 10/30/60`, `parallelism = 1/4/8`

## Benchmark Profiles

| DB | JDBC | R2DBC | Details |
|----|------|-------|---------|
| H2 | `h2JdbcBenchmark` | `h2R2dbcBenchmark` | [H2](./h2.md) |
| PostgreSQL | `postgresJdbcBenchmark` | `postgresR2dbcBenchmark` | [PostgreSQL](./postgresql.md) |
| MySQL | `mysqlJdbcBenchmark` | `mysqlR2dbcBenchmark` | [MySQL](./mysql.md) |

## Comparison Focus

The primary comparison is **JDBC vs R2DBC** for each database, split into:

1. `seedBenchmark` — source row insert cost
2. `endToEndBatchJobBenchmark` — full batch job execution cost

## Comparison Map

![Batch benchmark comparison map](../../../docs/images/readme-diagrams/utils-batch-benchmark-map-01.png)

## Notes

- Detailed numeric rows are generated per DB document.
- `generateBenchmarkDocs` writes the benchmark hub and DB detail documents, then fills tables and charts when JSON reports exist.
- Report directory: `utils/batch/build/reports/benchmarks`.
- Full PostgreSQL/MySQL runs can be generated later without changing the README link structure.
