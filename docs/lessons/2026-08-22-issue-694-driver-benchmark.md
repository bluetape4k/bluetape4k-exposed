# Issue #694 JDBC driver benchmark lesson

## 목적

[Issue #694](https://github.com/bluetape4k/bluetape4k-exposed/issues/694)의
범위는 published API를 바꾸지 않고 benchmark 전용 PostgreSQL/MySQL 8 JDBC
driver evidence를 남기는 것입니다. 최종 구현과 raw capture는
`f325a70fbd2047cdef28be928eeea4675b4b05b6`에 고정했습니다.

## 결정

| 항목 | 결정 | 근거 |
| --- | --- | --- |
| 비교 축 | `sequentialKeysetPaging` 대 `parallelKeyEnumeration` | 같은 seeded `BenchmarkUsers` fixture에서 기존 경로와 opt-in 경로를 직접 비교해야 했습니다. |
| matrix | driver 2 × row count 2 × pool size 3 = 12 point/driver | PostgreSQL/MySQL 8의 pool pressure와 row 규모 변화를 함께 관찰하되, benchmark 시간이 bounded하도록 고정했습니다. |
| 병렬성 | `maxConcurrency=2`, virtual-thread executor | 연결 lease 수를 pool보다 크게 만들지 않는 bounded parallel contract가 필요했습니다. |
| primary metric | JMH `Mode.Throughput`, `ops/s` | 두 경로의 완료 처리량을 같은 단위로 비교합니다. |
| secondary metric | statement executions/op, connection requests/op, peak active leases, active-at-end | throughput만으로는 연결 lifecycle과 pool pressure를 검증할 수 없기 때문입니다. |
| raw policy | driver별 3회 immutable JSON capture와 SHA-256 JSONL metadata | median 계산을 다시 실행할 수 있고, 잘못된 partial report를 최종 evidence로 섞지 않도록 했습니다. |
| chart | 10,000행 slice의 네 panel SVG→PNG | 전체 24행은 표에 보존하고, chart는 pool-size shape를 빠르게 읽게 합니다. |

PostgreSQL driver는 `org.postgresql:postgresql:42.7.13`, MySQL driver는
`com.mysql:mysql-connector-j:9.7.0`이며 catalog ref
`91f9ea9336b5ea991f5675323a1cf25ccfd6f5ed`에서 해석했습니다.

## 재현 가능한 결과

- 각 raw 파일은 12개 benchmark row를 가지며, summary는 총 24개 median row를
  생성합니다. [전체 표와 실행 명령](../benchmarks/exposed-benchmark-2026-08-22-issue-694/README.ko.md)을
  기준 문서로 사용합니다.
- PostgreSQL image는 `postgres:18.4-alpine`과
  `sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15`,
  MySQL image는 `mysql:8.4.11`과
  `sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb`로
  고정했습니다.
- JDK는 `25.0.4`이며 JMH 실행 설정은 warmup 1회, 1초 iteration 3회,
  `@Threads(1)`, `@Fork(1)`입니다.
- `/op` counter는 각 run에서 `sum(counter.rawData) /
  sum(primaryMetric.rawData)`로 계산한 뒤 세 run의 median을 취했습니다.
- Capture helper는 caller가 넘긴 버전 문자열만 신뢰하지 않고 Gradle resolved
  artifact, Docker image digest, immutable catalog ref, Git SHA/dirty flag,
  host/runtime provenance를 관찰해 metadata에 기록합니다. post-copy SHA를 다시
  확인하고 metadata append는 lock으로 직렬화하며, summary validator가 raw SHA와
  일대일 대조합니다.
- JMH `measurementIterations=3` 계약도 primary/auxiliary rawData의 실제 sample
  수가 정확히 3개인지 parser에서 확인합니다.
- Summary/chart validator는 구현 SHA, catalog ref, image digest, runtime 값을 고정하고
  run 번호와 `benchmark.json` source report의 대응도 확인합니다. EN/KO parity는
  table cell 순서, technical heading token, raw link와 실제 target 파일 존재까지 검사합니다.
- 모든 최종 raw row에서 `activeAtEnd=0`이고 peak active leases가 설정된
  pool size 이내였습니다. 이는 resource lifecycle 계약의 증거이지 특정
  환경의 production capacity 약속이 아닙니다.
- fork 1회와 짧은 iteration만 사용했으므로 `scoreError`가 point estimate보다
  클 수 있습니다. 결과는 통계적으로 확정적인 순위가 아니라 방향성 있는
  local evidence이며, tracker proxy/counter가 timed path에 포함된
  instrumented throughput입니다.

## 실패에서 얻은 교훈

초기 PostgreSQL capture 중 하나는 11개 row만 남아 immutable capture 대상에서
제외했고, tracker의 close 순서가 잘못된 실행에서는 다음 guard가 발생했습니다.

```text
benchmark invocation exceeded pool size: peak=2 pool=1
```

원인은 Hikari가 physical lease를 재사용 가능하게 만든 뒤 proxy의 active
counter가 감소하는 짧은 순서 역전이었습니다. 최종 fixture는 logical lease를
반납하는 순간 counter를 먼저 감소시키고 delegate `close()`를 호출하도록
수정했습니다. 이후 `f325a70fbd2047cdef28be928eeea4675b4b05b6` SHA가 고정된
상태에서 PostgreSQL/MySQL을 각각 3회 다시 실행했고, capture validator와
summary validator를 통과한 6개 raw만
보존했습니다. 실패한 partial/사전 commit raw는 최종 증거 디렉터리에 포함하지
않았습니다.

이 순서는 JDBC pool benchmark에서 중요한 일반 원칙을 남깁니다.

1. Proxy counter는 physical pool 구현의 내부 순서가 아니라 측정하려는
   logical lease 계약을 반영해야 하며, close 실패 시 active count를
   보수적으로 복원해야 합니다.
2. `peak <= poolSize`와 `activeAtEnd=0`을 timed method마다 검사해야 하며,
   마지막에 한 번만 검사하면 iteration 사이의 누수를 놓칠 수 있습니다.
3. raw capture는 source report를 byte-for-byte 보존하고, destination이 이미
   있거나 symlink이면 실패해야 합니다.

## 범위 밖인 것

이번 작업은 driver ranking, round-trip latency, allocation, server-side
configuration tuning, production pool recommendation을 결정하지 않습니다.
`poolSize=1`은 pressure condition이며 기본값 제안이 아닙니다. 또한
exact-head full nightly CI는 benchmark raw evidence와 별도 gate이므로, PR
head와 CI artifact를 새로 확인하기 전에는 merge-ready로 판정하지 않습니다.

## 후속 검증

- [ ] PR head가 `f325a70fbd2047cdef28be928eeea4675b4b05b6`인지 다시 읽습니다.
- [ ] exact-head full nightly에서 PostgreSQL/MySQL JDBC job이 skipped가 아닌
  상태로 완료되고 raw artifact가 보존되는지 확인합니다.
- [ ] 새 결과가 기존 raw를 덮어쓰지 않고 별도 run receipt로 capture되는지
  확인합니다.
