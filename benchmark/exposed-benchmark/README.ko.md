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

2026-08-19 Oracle GraalVM `25.0.4` (Java 25), H2에서 같은 profile을 세 번 순차 실행했습니다. 아래 값은 세 실행의 중앙값이며, 단일 실행의 최고값이 아닙니다.

| workload | configuration | 중앙값 | 해석 |
|---|---|---:|---|
| Cache | near-cache hit, cache size 10,000 | 206,595,487 ops/s | 같은 profile에서 local Caffeine hit보다 약 3.79배, read-through miss보다 약 4.38배 높음 |
| Cache | local Caffeine hit, cache size 10,000 | 54,531,040 ops/s | 같은 cache profile 내부 비교 |
| Cache | near-cache read-through miss, cache size 10,000 | 47,137,612 ops/s | 같은 cache profile 내부 비교 |
| JDBC/R2DBC | platform-thread select by ID, 10,000 rows | 34,688 ops/s | H2 단건 조회 기준선 |
| JDBC/R2DBC | virtual-thread select by ID, 10,000 rows | 24,376 ops/s | 이 profile에서 platform-thread 기준의 약 70.3% |
| JDBC/R2DBC | R2DBC suspend transaction select by ID, 10,000 rows | 19,197 ops/s | 이 profile에서 platform-thread 기준의 약 55.3% |
| Custom IDs | 선택된 반복에서 가장 빠른 `selectByName`, 10,000 rows | 216,715 ops/s | UUID table |
| Custom IDs | 선택된 반복에서 가장 느린 `selectByName`, 10,000 rows | 196,483 ops/s | Time-based UUID table |

![Exposed benchmark 비교](../../docs/images/readme-charts/exposed-benchmark-suite.png)

### 분석 및 한계

- 세 패널은 각 비교 그룹 안에서 선형 bar 폭을 사용합니다. 단위와 workload가 다르므로 세 패널을 하나의 전역 순위로 읽으면 안 됩니다.
- H2 결과만으로 #690의 opt-in parallel key enumeration 기본값을 정하지 않습니다. 이 결과는 단건 조회이며 contention 또는 producer/consumer benchmark가 아닙니다.
- 선택된 최고/최저 custom-ID 중앙값 차이는 약 10.3%이며, 특정 ID 전략의 보편적 우위를 뜻하지 않습니다.
- Redis는 endpoint를 제공하지 않아 `N/A`입니다. 비-H2 driver, connection pool, cache hit ratio, mutation contention은 별도 환경 검증이 필요합니다.

## 근거와 재현

- 원시 JSON과 정확한 세 실행 선택은 [`docs/benchmarks/exposed-benchmark-2026-08-19`](../../docs/benchmarks/exposed-benchmark-2026-08-19/README.md)에 기록했습니다.
- 세 benchmark task를 `--rerun-tasks --no-build-cache --no-configuration-cache --no-parallel --max-workers=1`로 순차 실행한 뒤 `scripts/benchmark/render_exposed_benchmark_chart.py`로 SVG를 만들고 CairoSVG로 PNG pair를 생성합니다.
- `generateBenchmarkDocs` task는 한 번의 로컬 report를 만드는 보조 도구입니다. 저장소에 고정한 세 실행 중앙값의 source가 아니므로, 이 비교를 갱신할 때는 위 evidence 디렉터리와 renderer를 사용합니다.
