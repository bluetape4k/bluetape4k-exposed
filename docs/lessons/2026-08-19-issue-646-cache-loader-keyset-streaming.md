# Issue #646: Exposed cache loader keyset·streaming

## Context

Exposed map loader의 전체 키 열거가 `LIMIT/OFFSET` page를 반복하면서 결과를 한 번에
materialize하거나, page 사이의 PK 변경에 offset 보정이 필요한 경로를 사용하고 있었다.
이번 slot은 [#646](https://github.com/bluetape4k/bluetape4k-exposed/issues/646)의
명시 범위인 JDBC Lettuce, R2DBC Lettuce, JDBC Redisson suspended loader만 다룬다.
stable `docs/manual/**`는 현재 배포선 `1.12.1`을 가리키므로 수정하지 않았다.

## Decision or Finding

- paging과 streaming은 대체 개념이 아니다. 이번 구현은 작은 keyset page를 transport로
  사용하고, JDBC Lettuce는 lazy `Iterable`, R2DBC Lettuce는 additive `Flow`, JDBC
  Redisson suspended는 기존 rendezvous-channel `AsyncIterator`로 순차 소비하는
  하이브리드다.
- 지원되는 표준 scalar raw PK에는 `id > lastId` keyset 경계를 사용한다. `ID: Any` 공개
  bound를 넓히지 않고 그 밖의 custom ID는 기존 offset fallback으로 남겨 source/ABI를
  보존한다. 단순한 `Comparable` 여부만으로 keyset을 선택하지 않으며, fallback은
  weakly consistent한 legacy 의미를 유지해 page 사이 삭제로 아직 관찰하지 않은 row를
  건너뛸 수 있다.
- JDBC Lettuce는 ambient caller-owned transaction이 없을 때 page마다 `transaction`을
  열어 Exposed Query와 JDBC connection이 iterator 밖으로 탈출하지 않게 한다. 활성
  transaction에서는 Exposed의 ambient 재사용 규칙을 따른다. R2DBC Flow도 같은 경계를
  `suspendTransaction`으로 적용하고 downstream cancellation을 재전파한다.
- JDBC Redisson suspended는 기존 caller-owned scope와 outer producer transaction을
  유지한다. rendezvous channel에는 `trySend` 실패를 정상 종료로 바꾸지 않도록
  `send`를 사용하고, producer timeout 원인도 `AsyncIterator`에 전달한다. channel
  방출을 재생할 수 없으므로 producer transaction은 `maxAttempts = 1`로 고정한다.
  일반 producer 오류는 channel cause로 전달하고 child 밖으로 재전파하지 않아
  caller-owned 일반 `Job`을 오염시키지 않으며, producer/receiver child는 terminal 상태에서
  종료된다.
- 공통 `SuspendedEntityMapLoader`는 `ChannelResult.exceptionOrNull()`을
  `CompletionException`으로 감싸 `AsyncIterator.hasNext`/`next`에 전달한다. DB 오류가
  빈 iterator로 마스킹되지 않도록 문서화된 오류 계약과 구현을 일치시켰다.
- Virtual Thread 기반 병렬 key enumeration은 range partition, 독립 connection,
  ordering, snapshot, cancellation, pool 상한과 benchmark가 필요하므로 기본값으로
  채택하지 않았다. 후속 [#690](https://github.com/bluetape4k/bluetape4k-exposed/issues/690)
  에서 명시적 opt-in으로 검증한다. 나머지 세 loader parity는
  [#689](https://github.com/bluetape4k/bluetape4k-exposed/issues/689)로 분리했다.

## RED evidence

production 변경 전에 다음 test-only 실패를 확인했다.

- JDBC Lettuce keyset/lazy 회귀는 기존 materializing `loadAllKeys()`에서
  `loadAllKeys() is List<*>` assertion이 실패했다.
- R2DBC Lettuce는 additive `loadAllKeysFlow()`가 없어 test compile이
  `Unresolved reference`로 실패했다.
- JDBC Redisson은 새 AsyncIterator 경계 test에서 rendezvous `trySend` 경로가 첫
  page만 전달하고 `IllegalStateException: 채널 전송 실패`를 발생시켰다.
- producer DB 오류 test에서는 공통 base가 channel cause를 정상 종료로 마스킹하는
  결함을 먼저 재현했고, 그 뒤 `CompletionException` 재전파를 고정했다.
- 추가 교차검증에서 JDBC Iterable이 마지막 partial page를 먼저 `exhausted`로
  표시해 첫 ID만 방출하는 결함을 재현했다. `6 rows + batchSize=4` 회귀가 RED가 된
  뒤, page의 모든 ID를 방출한 다음 종료하도록 수정했다.
- 독립 review에서 `withTimeoutOrNull`이 producer timeout을 정상 exhaustion으로
  마스킹할 수 있음을 확인했다. `withTimeout`과 timeout cause 전달, 전용 회귀 테스트로
  partial enumeration이 정상 완료로 바뀌지 않도록 고정했다.

## Outcome

현재 구현과 회귀 테스트는 다음 계약을 고정한다.

- keyset 경로의 PK ASC 순서, sparse ID, page 사이의 큰 PK 추가/기존 row 삭제에서
  duplicate 없음. custom-ID offset fallback은 page mutation에서 unseen row를 건너뛸 수
  있는 legacy weak semantics를 유지한다.
- JDBC Lettuce의 첫 소비 전 lazy page 생성과 page 경계별 transaction.
- R2DBC Lettuce의 기존 `List` 결과와 `Flow` 순서/ID parity, `take(1)` cancellation
  뒤 다음 page transaction을 열지 않는 경계.
- JDBC Redisson suspended의 page 경계·caller scope 취소·producer DB 오류 전파와
  terminal child 종료.
- 지원 목록 밖 custom ID의 legacy offset fallback과 `batchSize` page 상한.
- 세 helper의 표준 scalar/custom `Comparable` 판정과 101-row fixture의 page/query
  cardinality를 회귀 테스트로 고정한다.

완전한 snapshot은 보장하지 않는다. page 사이 insert/delete는 weakly-consistent
enumeration 의미로 문서화했으며, 독립 connection을 사용하는 driver별 동시 변경 증거는
#689에서 보강한다.

표준 scalar whitelist 밖의 custom `IdTable`을 H2 fixture로 재현하는 driver test는 이번
slot에서 추가하지 않았다. 구현은 `Comparable` 여부만으로 keyset을 선택하지 않고 명시된
표준 타입만 keyset으로 분기하므로, custom ID는 legacy offset fallback으로 남는다. 해당
fallback의 driver별 parity와 동시 변경 증거는 후속 #689의 acceptance로 남긴다.

## Verification

| 범위 | 결과 |
| --- | --- |
| JDBC Lettuce targeted `ExposedEntityMapLoaderTest` | 10 tests, failures 0, errors 0 |
| R2DBC Lettuce targeted `R2dbcExposedEntityMapLoaderTest` | 13 tests, failures 0, errors 0 |
| JDBC Redisson targeted `SuspendedExposedEntityMapLoaderTest` | 9 tests, failures 0, errors 0 |
| JDBC Lettuce full module XML | 884 tests, failures 0, errors 0, skipped 73 |
| R2DBC Lettuce full module XML | 152 tests, failures 0, errors 0, skipped 4 |
| JDBC Redisson full module XML | 622 tests, failures 0, errors 0, skipped 1 |
| affected compile + detekt | BUILD SUCCESSFUL, compiler warnings 0 |
| `git diff --check` | PASS |
| public ABI | clean `develop` jar와 candidate `javap -public -s` 비교; 의도한 JDBC `loadAllKeys()`와 R2DBC `loadAllKeysFlow()` additive surface만 확인하고 `@JvmSynthetic` capability helper와 compiler-generated `access$` synthetic method 변동은 지원 ABI에서 제외 |
| README EN/KO parity | 세 adapter의 변경 contract token 수와 의미가 일치; 표준 FluentQuery 전용 parity validator는 대상 marker가 없어 적용하지 않음 |
| stable manual | `docs/manual/**` 변경 0; manual `1.12.1` 경계 유지 |

JDBC Redisson full task log의 `Executed 621 tests (1 skipped)`와 XML의
`tests=622, skipped=1` 차이는 skipped test를 task 실행 수에서 제외하는 logger 집계
차이다. XML failure/error는 모두 0이다. 테스트는 affected module별로
`--rerun-tasks --no-build-cache --no-configuration-cache --no-parallel --max-workers=1`로
순차 실행했다.

Redisson full run 종료 시 기존 write-behind shutdown timer가 connection-pool
`CancellationException` 경고를 남겼지만, 해당 task의 XML은 `failures=0`, `errors=0`이고
loader 회귀 실패와 무관했다.

## Future Guidance

- `loadAllKeysFlow()`를 추가했지만 기존 `loadAllKeys(): List<ID>`는 호환성 때문에
  유지한다. 큰 read enumeration에는 Flow/Iterable을 사용하고, 기존 List 호출자는
  결과 전체 보관 비용을 수용해야 한다.
- Redisson `AsyncIterator`에는 `close()`가 없으므로 조기 중단은 caller-owned
  `CoroutineScope` 취소로 표현한다. 새 close API는 이 slot에서 추가하지 않는다.
- 성능 향상 수치를 benchmark 없이 주장하지 않는다. Virtual Thread 병렬화와 남은
  loader parity는 각각 #690/#689의 acceptance와 driver별 evidence를 먼저 만든다.
- page transaction 증거는 ambient caller-owned transaction이 없는 top-level 호출에서의
  경계를 의미한다. 활성 Exposed transaction 안에서는 Exposed의 ambient 재사용 규칙을
  따르며, 독립 driver connection/mutation 증거는 #689에서 보강한다.
- PR, CI, merge, canonical sync, worktree cleanup은 구현 GREEN과 별개의 fresh
  authority gate다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, base, 대상 module, stable manual 경계를 고정했다.
- [x] SPW-02 — keyset/paging/streaming 선택, transaction·cancellation·error 계약,
  fallback과 후속 범위를 기록했다.
- [x] SPW-03 — 한국어 technical register와 code/API token을 보존했다.
- [x] SPW-04 — RED/GREEN, full XML, compile/detekt, ABI와 README parity evidence를
  현재 snapshot과 대조했다.
- [x] SPW-05 — Markdown read-back, link, table, unchecked gate를 확인했다.

상태: `REVIEW_READY` — 구현·검증 완료. 독립 7-Tier review와 PR/CI는 다음 gate다.
