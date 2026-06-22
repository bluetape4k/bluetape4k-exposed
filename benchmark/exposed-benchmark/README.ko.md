# Exposed Benchmark Suite

Exposed JDBC, R2DBC, custom ID table, cache 전략을 독립적으로 실행하는 kotlinx-benchmark 모듈입니다.

## 시나리오

| 영역 | Benchmark task | 측정 대상 |
|---|---|---|
| JDBC vs R2DBC | `./gradlew :benchmark-exposed-benchmark:jdbcR2dbcBenchmark` | JDBC platform thread select, JDBC virtual thread dispatch, R2DBC suspend transaction select 처리량 |
| Custom ID tables | `./gradlew :benchmark-exposed-benchmark:idTablesBenchmark` | `UUIDTable`, `TimebasedUUIDTable`, `UlidTable`, Base62 UUIDv7, Snowflake, KSUID, KSUID millis 대량 insert/select 처리량 |
| Local and near cache | `./gradlew :benchmark-exposed-benchmark:cacheBenchmark` | Caffeine hit, near-cache hit, read-through miss 처리량 |
| Redis cache clients | `./gradlew :benchmark-exposed-benchmark:redisCacheBenchmark -Pbenchmark.parameters.redisUri=redis://127.0.0.1:6379` | Lettuce와 Redisson remote cache get 처리량 |
| Smoke | `./gradlew :benchmark-exposed-benchmark:smokeBenchmark` | Redis를 제외한 짧은 H2 기반 검증 실행 |

## 결과

생성일: 2026-06-23, 입력 경로: `build/reports/benchmarks`.

| Benchmark | Mode | Score | Error | Unit |
|---|---:|---:|---:|---|
| `io.bluetape4k.exposed.benchmark.cache.CacheStrategyBenchmark.nearCacheHit` | thrpt | 160467607.91 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.cache.CacheStrategyBenchmark.localCaffeineHit` | thrpt | 44643332.35 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.cache.CacheStrategyBenchmark.nearCacheReadThroughMiss` | thrpt | 22240956.17 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.snowflakeTableSelectByName` | thrpt | 51790.86 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.uuidTableSelectByName` | thrpt | 51668.71 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.base62TableSelectByName` | thrpt | 50944.82 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ulidTableSelectByName` | thrpt | 49409.24 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ksuidMillisTableSelectByName` | thrpt | 48990.12 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.timebasedUuidTableSelectByName` | thrpt | 48084.84 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ksuidTableSelectByName` | thrpt | 45473.50 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.jdbc.JdbcThreadingBenchmark.platformThreadSelectById` | thrpt | 30178.75 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.jdbc.JdbcThreadingBenchmark.virtualThreadSelectById` | thrpt | 18892.00 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.r2dbc.R2dbcCoroutineBenchmark.suspendTransactionSelectById` | thrpt | 2473.53 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.snowflakeTableBatchInsert` | thrpt | 1205.55 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.timebasedUuidTableBatchInsert` | thrpt | 983.04 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ulidTableBatchInsert` | thrpt | 829.82 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ksuidMillisTableBatchInsert` | thrpt | 821.43 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.uuidTableBatchInsert` | thrpt | 815.68 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.base62TableBatchInsert` | thrpt | 770.43 | - | ops/s |
| `io.bluetape4k.exposed.benchmark.id.CustomIdTableBenchmark.ksuidTableBatchInsert` | thrpt | 660.85 | - | ops/s |

![Exposed benchmark chart](../../docs/images/readme-charts/exposed-benchmark-suite.svg)

## 운영 메모

- Redis benchmark는 접근 가능한 Redis 서버가 필요하므로 smoke에서 분리했습니다.
- 기본 검증은 H2로 가볍게 유지하고, DB별 profile은 같은 모듈 경계 안에서 확장합니다.
- benchmark 실행 후 `./gradlew :benchmark-exposed-benchmark:generateBenchmarkDocs`로 표와 차트를 갱신합니다.
