# Issue #690 Virtual Thread JDBC key enumeration lesson

## 결정

JDBC Lettuce/Redisson synchronous loader에 호출자가 PK 구간을 명시할 때만 선택하는
`loadAllKeysInParallel()`을 추가했다. 공용 `parallelJdbcKeyEnumeration()`은 각 range를
독립 `VirtualFuture` transaction으로 실행하고, `maxConcurrency` semaphore로 동시에
열린 transaction 수를 제한한 뒤 입력 range 순서로 `List`를 병합한다.

기존 `loadAllKeys()` lazy keyset paging, suspended/R2DBC loader, custom-ID offset
fallback, manual `docs/manual/**`는 변경하지 않았다. parallel 경로는 전체 결과를
materialize하므로 메모리와 단일 읽기 일관성 기준을 보장하지 않으며, caller가 JDBC pool과
isolation 정책을 선택한다.

## 검증

| 경계 | 증거 |
| --- | --- |
| range 계약 | H2에서 disjoint/open-bound/sparse ID를 중복 없이 병합하고 overlap/reverse를 transaction 전에 거부 |
| 동시성 | 내부 test-only range reader가 6개 range에서 `maxConcurrency=2`를 초과하지 않음을 기록 |
| 실패/취소 | 한 sibling의 원래 예외를 보존하고 다른 sibling의 `InterruptedException`을 관찰한 뒤 모든 future를 정리 |
| adapter parity | JDBC Lettuce와 Redisson의 기존 sequential 결과가 parallel 결과와 일치하고 empty/default path가 유지 |
| executor lifecycle | 종료된 executor를 transaction을 열기 전에 거부하고 caller-owned executor를 helper가 닫지 않음 |
| static/targeted | helper 8/8, 각 loader 2/2, JDBC/Lettuce/Redisson 전체 테스트, benchmark classes compile 성공 |

## benchmark

JDK 25 + H2 in-memory + Hikari pool 10에서 warmup 1회, 1초 측정 3회의 throughput을
세 번 실행했다. `rowCount=1000`에서는 sequential 12,607 ops/s, parallel 14,191
ops/s의 중앙값(1.13x)이었고, `rowCount=10000`에서는 sequential 1,250 ops/s, parallel
2,960 ops/s(2.37x)였다. 측정 오차가 크므로 이는 H2 단일 환경의 방향성 evidence이며
production driver의 보편적인 향상이나 기본값 전환 근거가 아니다.

원시 JSON과 차트는
[`docs/benchmarks/exposed-benchmark-2026-08-19-issue-690/`](../benchmarks/exposed-benchmark-2026-08-19-issue-690/)
및
[`exposed-jdbc-key-enumeration-issue-690.png`](../images/readme-charts/exposed-jdbc-key-enumeration-issue-690.png)에
보존했다.

## 후속 검증

- PostgreSQL/MySQL driver와 실제 connection pool에서 throughput, lock, isolation,
  읽기 일관성 기준을 별도 환경에서 측정한다.
- 1,000행처럼 작은 입력과 메모리 제한이 있는 caller는 기존 lazy 경로와 비교해
  materialization 비용을 확인한다.
- Issue #692의 suspended/R2DBC 또는 custom-ID fallback 설계와 합치지 않는다.
