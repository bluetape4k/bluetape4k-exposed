# Issue #685: R2DBC `saveAll` 입력 재수집 방지

## Context

Exposed 1.4.0의 top-level R2DBC transaction은 `R2dbcException`이 발생하면
transaction block을 다시 실행할 수 있습니다. 기존 `saveAll(Flow/Iterable)`은
입력 수집을 transaction 안에서 수행했기 때문에 commit fault 후 입력을 다시
수집하거나 순회할 수 있었습니다. #684는 commit fault adapter와 attempt-local
결과 목록을 추가했지만, repository가 소유한 transaction의 재시도 정책은 기본값에
남아 있었습니다.

이번 변경은 top-level `saveAll`에만 `maxAttempts = 1`을 적용해 입력을 한 번만
소비하고, active outer transaction에서는 caller가 설정한 retry 정책을 보존하는
것을 목표로 했습니다.

## Decision

- 두 `saveAll` overload가 private `inTransactionWithoutRetry` helper를 사용합니다.
- helper는 진입 전에 `TransactionManager.currentOrNull()`로 active R2DBC
  transaction을 확인합니다. outer transaction이 없을 때만 `maxAttempts = 1`을
  설정합니다.
- Exposed 기본 `useNestedTransactions=false`에서는 nested `suspendTransaction`이
  outer 객체를 재사용할 수 있으므로, helper 안에서 무조건 `maxAttempts = 1`을
  설정하면 caller 설정을 덮어쓴다는 사실을 테스트로 확인했습니다.
- outer transaction은 caller의 retry 횟수·최종 commit/rollback ownership을
  유지합니다. outer block이 재시도될 수 있는 경우 입력은 replayable하고
  side-effect-free여야 합니다.
- transaction 반환값으로 materialize한 attempt-local 결과와 commit-before-emission
  계약은 #650/#684와 동일하게 유지합니다. public signature와 ABI snapshot은
  변경하지 않았습니다.

## Outcome

- top-level Flow의 one-shot 입력은 두 번째 collect 없이 fault를 한 번 전파합니다.
- top-level Flow의 side-effect counter는 1회이고, Iterable iterator도 1회만
  호출됩니다.
- top-level 실패 attempt의 row, generated ID, 반환 결과는 남지 않습니다.
- outer `maxAttempts = 2`에서는 fault 뒤 outer block과 replayable Flow가 2회 실행되고
  성공 attempt의 결과와 row 2건만 남습니다. outer block 안의 결과 방출은 최종
  commit보다 앞설 수 있어 emission 시점 commit count는 `[0, 0, 1, 1]`이며,
  최종 반환 ID와 저장 row ID 집합도 일치하는지 확인합니다.
- top-level hot `SharedFlow`는 유한 `take(2)` 수집으로 두 번째 collect가 발생하지
  않고 commit fault가 한 번만 전파되는지 확인합니다.
- KDoc와 EN/KO module README는 top-level no-retry와 outer caller-owned retry를
  별도 계약으로 설명합니다.

## Verification

| 단계 | 결과 |
| --- | --- |
| 기존 baseline H2 retry class | 2/2 passing (변경 전 기본 retry 동작) |
| RED H2 targeted | 4개 중 3개 실패, 1개 통과; top-level no-retry 기대가 기존 구현에서 재현됨 |
| GREEN H2 targeted | 5/5 passing |
| GREEN H2 targeted (hot SharedFlow 포함) | 6/6 passing |
| PostgreSQL targeted (hot SharedFlow 포함) | H2+PostgreSQL 12/12 passing, Testcontainers 순차 실행 |
| H2 affected module (hot SharedFlow 포함) | 129/129 passing |
| detekt | `:bluetape4k-exposed-spring-boot-r2dbc:detekt` 성공 |
| ABI | `ExposedR2dbcRepositoryAbiCompatibilityTest` 2/2 passing |
| README parity | `R2DBC coroutine FluentQuery README parity is aligned` |
| saveAll README bounded parity | EN/KO 두 saveAll section의 9개 contract token 일치 |
| whitespace | `git diff --check` 성공 |

Gradle 로그의 기존 `R2dbcProjectionMapper` cast 및 test snapshotter unchecked cast
warning은 이번 변경과 무관한 기존 warning이며 build/test 실패가 아닙니다.

현재 안정 release manual은 1.12.1이므로 `docs/manual/**`는 변경하지 않았습니다.
1.13.0 manual 승격은 release gate인 #651에서 수행해야 합니다.

## Miss or surprise

첫 구현은 nested helper 안에서 항상 `maxAttempts = 1`을 설정했습니다. H2에서
top-level 검증은 통과했지만 outer retry 테스트가 첫 commit fault를 그대로 전파해
실패했습니다. Exposed가 기본 설정에서 같은 transaction 객체를 재사용한다는
경계를 확인한 뒤 현재 transaction 감지 조건을 추가했습니다. 이후 H2와 PostgreSQL
모두에서 outer retry가 2회 실행되는 것을 확인했습니다.

## Future guard

- `saveAll` 내부 transaction helper를 변경할 때는 top-level no-retry와 outer
  caller-retry를 같은 fault-injection class에서 함께 검증합니다.
- `useNestedTransactions` 설정을 바꾸거나 Exposed transaction API를 업그레이드할
  때는 `TransactionManager.currentOrNull()` 감지와 `maxAttempts` 소유권을 다시
  확인합니다.
- driver별 네트워크/timeout fault 의미론은 #674 범위이며, 이 commit fault 증거를
  모든 driver 장애의 보장으로 확대하지 않습니다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — 대상 reader, Issue/Epic, source path, Exposed 1.4.0 retry 근거와
  `docs/manual/**` N/A 범위를 고정했습니다.
- [x] SPW-02 — lesson의 context, decision, outcome, verification, miss, future
  guard 구조가 acceptance와 연결됩니다.
- [x] SPW-03 — 한국어 technical register와 `top-level`, `outer transaction`,
  `replayable`, `side-effect-free` 용어를 일관되게 적용했습니다.
- [x] SPW-04 — source 구현, #684 fault factory, 테스트 XML/Gradle 결과, EN/KO
  README를 대조해 수치와 계약을 확인했습니다.
- [x] SPW-05 — Markdown 전체를 read-back하고 표, code token, 명령, 링크, N/A
  범위를 확인했습니다.
