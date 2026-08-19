# Issue #689 cache loader parity lesson

## 결과

Issue #689의 세 후속 경로를 Issue #646과 같은 keyset/paging 계약으로 정렬했다.

- JDBC Lettuce suspended loader는 기존 `List<ID>` API를 유지하면서 표준 scalar PK에
  `id > lastId` 경계를 사용하고, custom ID에는 offset fallback을 사용한다.
- JDBC Redisson synchronous loader는 기존 `Iterable`/`List` materialization surface를
  유지하되 page마다 `batchSize`만 읽고 keyset을 우선한다. 이 API는 Redisson의 기존
  `List` 반환 계약 때문에 최종 ID collection은 여전히 caller가 소유한다.
- R2DBC Redisson loader는 기존 rendezvous `AsyncIterator`와 back-pressure를 유지하고,
  producer가 한 page만 `toList()`로 materialize한 뒤 channel에 순차 전송한다.
- 세 loader 모두 public constructor, cache serialization, repository transaction API를
  변경하지 않았다. Virtual Thread 병렬 enumeration은 후속 #690의 범위다.

지원 목록 밖의 `Comparable` custom ID는 자동으로 keyset으로 취급하지 않는다. 이는
raw column과 Kotlin 비교 semantics가 일치한다고 추측하지 않기 위한 보수적 경계이며,
해당 경로에서는 기존 weakly-consistent offset 의미를 유지한다.

## 검증 결과

### Loader evidence

- 표준 `Long` PK의 5행 fixture에서 `batchSize=2`는 세 SELECT를 실행했고, 첫 page 이후
  SQL에 `>` predicate가 있으며 `OFFSET`은 없었다.
- 101행 fixture에서 `batchSize=16`은 7 SELECT를 실행했고, 모든 query가 `LIMIT`을
  사용했으며 page buffer가 batch 크기를 넘지 않았다.
- JDBC Redisson synchronous와 R2DBC Redisson에 sparse-ID 중복 방지 회귀를 추가했다.
  JDBC Lettuce suspended와 JDBC Redisson suspended의 기존 sparse/keyset 검증도 함께
  유지된다.
- 각 module의 keyset capability test는 `Long`을 지원하고
  `ComparableCustomId`를 fallback으로 분류한다.
- R2DBC `AsyncIterator`는 기존 rendezvous producer/consumer 경계를 그대로 사용한다.
  이번 slot은 정상·sparse·producer error를 검증하며, scope cancellation의 full
  cross-driver 증거는 기존 #646 base test와 후속 driver 환경 검증에서 이어간다.

### Benchmark evidence와 chart

JDK 25.0.4/H2에서 동일 profile을 세 번 순차 실행하고 중앙값을 보존했다.
원시 JSON과 재현 명령은
[`docs/benchmarks/exposed-benchmark-2026-08-19`](../benchmarks/exposed-benchmark-2026-08-19/README.md)에
있으며, grouped SVG/PNG chart와 semantic ledger는
`docs/images/readme-charts/exposed-benchmark-suite.{svg,png,semantic.json}`이다.

- near-cache hit: `206,595,487 ops/s`
- local Caffeine hit: `54,531,040 ops/s`
- near-cache read-through miss: `47,137,612 ops/s`
- JDBC platform / virtual thread / R2DBC suspend 단건 조회: 각각
  `34,688` / `24,376` / `19,197 ops/s`
- custom ID `selectByName` 범위: `196,483`–`216,715 ops/s`

Cache panel은 처리량 자릿수가 달라 log-width로 표시하고 JDBC/R2DBC와 custom-ID
panel은 선형 폭으로 표시했다. 따라서 chart는 전역 순위가 아니라 panel 내부 비교다.
H2 단건 조회 결과만으로 #690의 parallel enumeration 기본 동작을 결정하지 않으며,
Redis는 endpoint가 없어 `N/A`다.

## 문제와 해결

### 마지막 partial page의 불필요한 빈 query

초기 구현은 page가 `batchSize`와 같을 때 다음 loop에서 빈 page를 한 번 더 조회했다.
이 때문에 5행/2행 배치가 4 SELECT, 101행/16행 배치가 8 SELECT로 관찰됐다.
partial page(`chunk.size < batchSize`)를 성공적으로 방출한 즉시 종료하도록 세 loader의
loop를 정렬했고, query 수 assertion을 회귀 guard로 고정했다.

### chart 하단 label clipping

7개 custom-ID row를 기존 390px panel에 넣으면서 마지막 `ULID` row와 footer 간격이
부족했다. chart renderer가 row 수에 따라 custom panel을 확장하고 전체 canvas를
`1800x1510`으로 조정했다. `xmllint`, semantic audit, visual occupancy audit,
asset-pair audit와 full-size PNG inspection을 다시 실행해 clipping과 누락 pair가
없음을 확인했다.

### 재현 wrapper 오류

첫 benchmark wrapper는 zsh 예약 변수명 `status`를 사용해 Gradle 이전에 실패했다.
예약 변수를 `rc`로 바꾼 동일 명령은 세 번 모두 성공했다. 이 실패는 benchmark 측정
실패가 아니라 shell wrapper 오류로 evidence README에 기록했다.

## 남은 경계와 후속 작업

- 비-H2 driver별 keyset SQL, page 사이 insert/delete, network fault/retry replay는
  별도 driver 환경에서 확인해야 한다. 현재 구현은 public transaction 계약을 바꾸지
  않고 정상 H2 경계를 검증했다.
- R2DBC Redisson의 producer channel과 transaction retry interaction은 기존 base
  contract를 보존했으므로, retry를 바꾸는 것은 별도 API/transaction decision으로
  남긴다.
- #690은 이 branch에서 구현하지 않는다. Virtual Thread range partition, pool 상한,
  ordering merge, opt-in API와 benchmark 설계를 stacked 다음 slot에서 다룬다.
- `docs/manual/**`는 안정 릴리스 `1.12.1` 경계를 유지해 변경하지 않았다.

## 재발 방지

1. 새 map loader는 표준 scalar capability와 custom fallback을 같은 helper test로
   고정한다.
2. `batchSize` 경계가 정확히 맞는 fixture와 partial page fixture를 함께 유지해 빈
   query가 다시 생기지 않게 한다.
3. benchmark 비교는 세 번 중앙값, raw JSON provenance, grouped panel, Redis `N/A`
   사유를 함께 보존한다. 단일 최고값이나 서로 다른 단위의 전역 순위를 사용하지 않는다.
4. chart 변경 뒤에는 SVG/PNG pair, semantic ledger, XML, visual geometry, full-size
   PNG 검사를 순서대로 실행한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue #689, Epic #659, 세 loader와 #690 경계를 고정했다.
- [x] SPW-02 — keyset/fallback, transaction/back-pressure, page evidence와 known gap을
  실제 구현에 맞춰 기록했다.
- [x] SPW-03 — 한국어 technical register와 `keyset`, `paging`, `streaming`,
  `fallback`, `N/A` token을 일관되게 유지했다.
- [x] SPW-04 — #646 설계/계획, current base loader, targeted test XML과 chart audit를
  대조했다.
- [x] SPW-05 — Markdown read-back으로 링크, code token, 수치, 안정 manual 경계를
  확인했다.
