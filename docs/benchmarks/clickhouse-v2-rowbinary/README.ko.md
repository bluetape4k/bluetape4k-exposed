# 이슈 #867 ClickHouse JDBC V2 RowBinary benchmark

이 디렉터리는 JDBC V2 RowBinary 배치 writer의 machine-readable 근거입니다.
raw JSON은 `ClickHouseRowBinaryBenchmarkTest`가 생성하고, chart는
그 파일에서 deterministic SVG로 렌더링합니다.

## 범위와 조건

- Driver artifact: `com.clickhouse:clickhouse-jdbc:0.9.9`.
- 각 path마다 fresh Gradle test process 세 개(`run=1..3`)를 기록했습니다.
- Matrix: 논리 행 수 3종(10,000 / 100,000 / 1,000,000) × flush 크기
  3종(256 / 1,024 / 4,096) × row shape 2종(narrow / wide) × path 2종.
- 각 JSON에는 18개 scenario, warmup 2회, 측정 5회, raw elapsed sample,
  heap 관찰값, 환경 provenance가 포함됩니다.
- in-memory provider fixture는 최대 2,048행만 측정합니다. 논리 행 수는
  시나리오 라벨이며 production ClickHouse wire 처리량이나 driver private
  buffer/heap 상한을 입증하지 않습니다.
- `firstByteNs`는 fixture의 timing marker(시나리오 median elapsed
  time)이며 driver의 packet-level first byte 측정값이 아닙니다.

## 세 process 중앙값

아래 표는 세 process JSON의 `medianRowsPerSecond` 중앙값입니다.
이 fixture에서는 rows/s가 높을수록 좋으며, 비율은 RowBinary를 JDBC
fallback으로 나눈 값입니다.

| Row shape | 논리 행 수 | Flush | RowBinary rows/s | JDBC fallback rows/s | 비율 |
| --- | ---: | ---: | ---: | ---: | ---: |
| narrow | 10,000 | 256 | 4.28M | 14.30M | 0.30x |
| narrow | 10,000 | 1,024 | 9.91M | 15.40M | 0.64x |
| narrow | 10,000 | 4,096 | 14.60M | 21.74M | 0.67x |
| narrow | 100,000 | 256 | 18.12M | 21.34M | 0.85x |
| narrow | 100,000 | 1,024 | 17.91M | 22.12M | 0.81x |
| narrow | 100,000 | 4,096 | 20.51M | 21.14M | 0.97x |
| narrow | 1,000,000 | 256 | 18.94M | 19.99M | 0.95x |
| narrow | 1,000,000 | 1,024 | 19.83M | 22.71M | 0.87x |
| narrow | 1,000,000 | 4,096 | 20.39M | 22.94M | 0.89x |
| wide | 10,000 | 256 | 1.85M | 3.46M | 0.54x |
| wide | 10,000 | 1,024 | 4.53M | 3.80M | 1.19x |
| wide | 10,000 | 4,096 | 7.42M | 10.18M | 0.73x |
| wide | 100,000 | 256 | 11.00M | 10.66M | 1.03x |
| wide | 100,000 | 1,024 | 12.04M | 11.63M | 1.04x |
| wide | 100,000 | 4,096 | 12.34M | 10.93M | 1.13x |
| wide | 1,000,000 | 256 | 12.24M | 16.91M | 0.72x |
| wide | 1,000,000 | 1,024 | 12.42M | 14.90M | 0.83x |
| wide | 1,000,000 | 4,096 | 13.46M | 14.43M | 0.93x |

![이슈 #867 ClickHouse JDBC V2 RowBinary benchmark](../../../docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png)

## 분석

- 결과는 보편적인 최적화가 아니라 path, row shape, flush 크기의
  상호작용입니다.
- narrow 모든 cell에서는 JDBC fallback이 더 빠르며, 100,000행·4,096
  flush가 0.97x로 가장 근접합니다.
- wide shape은 flush 크기에 따라 갈립니다. RowBinary는 10,000행·1,024
  flush(1.19x)와 100,000행의 세 cell(1.03x–1.13x)에서 앞서고, 나머지
  wide 비율은 1.0x보다 작습니다.
- 수치는 bounded fixture의 로컬 근거일 뿐입니다. SLO나 capacity 판단
  전에 대상 driver/server에서 profile을 명시하고 다시 측정해야 합니다.

## 재현

repository root에서 실행합니다.

```bash
for run in 1 2 3; do
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseRowBinaryBenchmarkTest' \
    -PclickhouseV2Benchmark=true -PclickhouseV2BenchmarkRun="$run" \
    --no-parallel --max-workers=1 --no-daemon --console=plain
done
python3 docs/benchmarks/clickhouse-v2-rowbinary/render_rowbinary_chart.py \
  --input-dir docs/benchmarks/clickhouse-v2-rowbinary --locale ko \
  --output docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg \
  --semantic-ledger docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.semantic.json
/Users/debop/.local/bin/cairosvg \
  docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.svg \
  -o docs/images/readme-charts/exposed-clickhouse-rowbinary-issue-867.ko.png -s 2
```

실제 ClickHouse profile assertion은 benchmark와 별도로 실행합니다.

```bash
./gradlew :bluetape4k-exposed-clickhouse:test \
  --tests '*ClickHouseRowBinaryIntegrationTest' \
  -PclickhouseV2Integration=true \
  --no-parallel --max-workers=1 --no-daemon --console=plain
```

`SHA256SUMS`에는 6개 raw JSON, semantic ledger, EN/KO SVG/PNG pair가
포함됩니다. source와 renderer의 validation gate를 통과한 뒤에만 다시
생성합니다.

## 한계

benchmark는 fake connection provider와 `container=not-run`을 사용하므로
ClickHouse 네트워크 I/O, server backpressure, remote cancellation, driver
private RowBinary buffer를 실행하지 않습니다. 일반 stream writer 지원,
`async_insert`, remote `KILL QUERY` 완료 보장은 이슈 #867 범위 밖이며,
remote cancellation은 #863에서 추적합니다.
