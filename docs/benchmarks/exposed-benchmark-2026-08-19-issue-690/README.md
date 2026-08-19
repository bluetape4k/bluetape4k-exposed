# Issue #690 JDBC key enumeration benchmark

이 기록은 기존 `loadAllKeys()` lazy keyset paging과 새 opt-in
`loadAllKeysInParallel()` Virtual Thread range enumeration을 동일한 H2 fixture에서
비교한 결과입니다. 두 경로 모두 같은 `BenchmarkUsers` table을 읽고, 병렬 경로는
서로 겹치지 않는 네 개의 `[lowerInclusive, upperExclusive)` range를
`maxConcurrency=4`로 실행합니다.

## 재현 조건

- JDK 25, H2 in-memory, Hikari pool size 10
- `rowCount=1000,10000`, `rangeCount=4`
- JMH: warmup 1회, 측정 3회, 각 1초, `thrpt`, `ops/s`
- 세 실행을 순차적으로 수행:

```bash
./gradlew :benchmark-exposed-benchmark:jdbcKeyEnumerationBenchmark \
  --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

## 세 실행 중앙값

| 행 수 | 기존 lazy `sequentialKeysetPaging` | opt-in `parallelKeyEnumeration` | 병렬/순차 비율 |
| ---: | ---: | ---: | ---: |
| 1,000 | 12,607 ops/s | 14,191 ops/s | 1.13x |
| 10,000 | 1,250 ops/s | 2,960 ops/s | 2.37x |

![JDBC key enumeration throughput](../../../docs/images/readme-charts/exposed-jdbc-key-enumeration-issue-690.png)

## 해석

- 이 fixture에서는 10,000행에서 range 병렬화가 더 높은 처리량을 보였고, 1,000행에서는
  차이가 작았습니다. 작은 입력에서 transaction·future·merge 비용이 이득을 상쇄할 수
  있으므로 parallel API를 기본값으로 바꾸지 않습니다.
- JMH 측정 오차가 크므로 위 수치는 방향성 evidence입니다. H2 in-memory 결과를
  PostgreSQL/MySQL driver나 production pool의 보편적인 향상으로 해석하지 않습니다.
- parallel 경로는 전체 ID를 `List`로 materialize하고 range별 독립 transaction을
  사용합니다. 메모리 상한과 단일 읽기 일관성 기준이 필요하면 기존 lazy 경로 또는 caller가
  선택한 isolation/pool 정책을 사용해야 합니다.

## 원시 evidence

- [run 1 JSON](./run-1.json)
- [run 2 JSON](./run-2.json)
- [run 3 JSON](./run-3.json)
- benchmark source:
  [`JdbcKeyEnumerationBenchmark.kt`](../../../benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/jdbc/JdbcKeyEnumerationBenchmark.kt)

차트는 세 JSON의 `primaryMetric.score`를 행 수·benchmark 이름별로 정렬해 중앙값을
계산한 뒤, 행 수별 축을 분리하여 생성했습니다. 각 패널의 단위는 `ops/s`이며 축은
서로 독립적입니다.
