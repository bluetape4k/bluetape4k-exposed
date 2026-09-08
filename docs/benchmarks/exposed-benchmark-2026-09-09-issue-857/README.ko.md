# #857 ClickHouse benchmark 근거

이 기록은 같은 `system.numbers` 조회와 detached `Long` 매핑을 사용해 새
`queryFlow`와 materialized `queryList`를 비교합니다. 행 수가 증가할 때
애플리케이션 retained memory와 첫 detached 항목 수신 시간이 어떻게 달라지는지
확인하는 것이 목적입니다.

## 실행 조건

- JDK 25, ClickHouse Testcontainers, 저장소의 `ClickHouseServer.Launcher` fixture.
- JMH: 메서드별 warmup 1회와 1초 throughput iteration 3회.
- Profile: API/행 수/run마다 fresh JVM, 1,000행 bounded warmup 2회,
  bounded GC settle 5회, forced-GC checkpoint와 JFR.
- 최종 실행 순서: `run=1/3`은 list 후 flow, `run=2`는 flow 후 list.
- 추적하는 machine-readable source는 [`summary.json`](./summary.json)입니다.

## JMH 처리량

수치는 JMH error 추정치를 포함한 ops/s입니다. 작은 로컬 표본에서 오차가 크므로
SLO가 아닌 방향성 처리량 근거로 해석합니다.

| API | 행 수 | 점수 |
|---|---:|---:|
| `queryList` (`collected`) | 100,000 | 76.556 ± 42.276 |
| `queryList` (`collected`) | 1,000,000 | 5.582 ± 8.882 |
| `queryFlow` (`streamed`) | 100,000 | 2.072 ± 3.211 |
| `queryFlow` (`streamed`) | 1,000,000 | 0.147 ± 1.118 |

## fresh-JVM profile 중앙값

| API | 행 수 | live heap 증가 | raw heap peak | RSS peak | 첫 항목 | 전체 시간 |
|---|---:|---:|---:|---:|---:|---:|
| `queryFlow` | 100,000 | 2,660,696 B | 44,927,912 B | 325,856 KiB | 39.0 ms | 1.239 s |
| `queryFlow` | 1,000,000 | 2,705,872 B | 159,776,408 B | 379,472 KiB | 37.8 ms | 7.693 s |
| `queryList` | 100,000 | 3,944,736 B | 82,472,408 B | 326,896 KiB | 80.5 ms | 110 ms |
| `queryList` | 1,000,000 | 29,148,136 B | 261,918,432 B | 511,312 KiB | 457.8 ms | 517 ms |

![Issue #857 ClickHouse queryList와 queryFlow profile](../../../docs/images/readme-charts/exposed-clickhouse-query-streaming-issue-857.ko.png)

## 분석

- 행 수가 10배가 될 때 `queryFlow` live heap은 2.54 MiB에서 2.58 MiB로
  증가해 `1.02x`였고, `queryList`는 3.76 MiB에서 27.80 MiB로 증가해
  `7.39x`였습니다.
- `queryFlow`는 두 profile 모두 약 38–39 ms에 첫 detached 항목을 전달했습니다.
  `queryList`는 전체 materialization을 기다려 1,000,000행에서 457.8 ms가
  걸렸습니다.
- `queryFlow`는 rendezvous channel로 의도적인 backpressure를 적용하므로 이
  fixture에서 JMH 처리량이 낮습니다. chart는 메모리/지연 관점이고, 처리량은 위
  표의 별도 지표이므로 forced-GC profile과 하나의 latency SLO로 결합하지 않습니다.
- JFR `jdk.OldObjectSample`에서 List 실행은 `ArrayList.toArray` 경로의
  3.8 MB `Object[1000000]`가 retained sample로 남았습니다. Flow 실행에는 같은
  full-result array sample이 없었습니다. sample에 없다는 사실만으로 driver 내부
  버퍼가 없다고 단정하지 않고 live heap, RSS, JFR을 함께 해석합니다.

## 재현

```bash
./gradlew :benchmark-exposed-benchmark:clickHouseBenchmark \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --console=plain
./gradlew :benchmark-exposed-benchmark:profileClickHouseStreaming \
  -PprofileApi=flow -PprofileRows=100000 -PprofileRun=1 \
  --no-build-cache --no-configuration-cache --no-parallel --max-workers=1 --console=plain
```

12개 profile 조합을 순차 실행하고 JSON, CSV, JFR을
`benchmark/exposed-benchmark/build/reports/clickhouse-profile/` 아래에 보관합니다.
JMH report는 `benchmark/exposed-benchmark/build/reports/benchmarks/clickHouse/<timestamp>/`
아래에 있습니다.

chart는 추적하는 `summary.json`과 renderer로 다시 만든 뒤 CairoSVG로 canonical
PNG를 생성합니다.

```bash
python3 render_chart.py --summary summary.json --locale en --output /tmp/issue-857.en.svg
cairosvg /tmp/issue-857.en.svg -o /tmp/issue-857.en.png -s 2
python3 render_chart.py --summary summary.json --locale ko --output /tmp/issue-857.ko.svg
cairosvg /tmp/issue-857.ko.svg -o /tmp/issue-857.ko.png -s 2
```

## 한계

이 profile은 production driver 동작, 서버 read timeout, row limit 또는 전체 정리
실패 조합을 입증하지 않습니다. chart와 summary는 #857의 로컬 근거이며 release나
hosted CI 결과가 아닙니다.
