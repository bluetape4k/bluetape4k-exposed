# Issue #626 — Suspended JDBC Caffeine Mutex 수명주기 교훈

## 배경

`AbstractSuspendedJdbcCaffeineRepository`의 suspend read-through miss 조정
registry가 관찰된 key마다 `Mutex`를 계속 보유해 key churn 이후에도 private
map cardinality가 줄지 않았다. 단순 `remove(key)`는 waiter가 있거나 release와
새 acquire가 교차할 때 서로 다른 `Mutex`를 만들 수 있으므로 채택하지 않았다.

## 결정

- key별 registry value를 private `LoadMutexEntry`로 두고 `Mutex`와 active user
  count를 함께 보유한다.
- entry 생성·참조 증가·감소·0 도달 시 제거를 모두 해당 key의
  `ConcurrentHashMap.compute`/`computeIfPresent` 안에서 선형화한다.
- `get`은 acquire 후 기존 cache double-check/DB loader를 수행하고 `finally`에서
  release한다. 따라서 성공·예외·`CancellationException`·`null` 모두 entry를
  회수하며 caller cancellation은 원래 예외로 재전파한다.
- 성공 값은 cache에 저장해 겹친 호출이 coalesce되지만, 예외·취소·`null`은
  deferred outcome으로 공유하지 않고 queued/next caller의 순차 retry를 허용한다.
  이 계약은 public metrics나 exact-one-failure promise를 추가하지 않는다.

## 구현 결과

production 동작은 `AbstractSuspendedJdbcCaffeineRepository.kt`의 private
registry와 `get` lifecycle만 변경했다. 회귀 테스트에는 unique-key cleanup,
exception/null retry, 실제 caller cancellation, cancelled waiter 보존,
release/new-acquire 경계에서 두 번째 loader를 gate한 뒤 세 번째 caller를
시작해 concurrently active loader 중복을 검출하는 회귀를 추가했다. 모든
비동기 caller는 `runSuspendIO`의 부모 scope에 연결했다.
EN/KO README에는 같은 suspend read-through 계약을 추가했으며 `docs/manual/**`와
public API는 변경하지 않았다.

## 검증 증거

| 단계 | 결과 |
| --- | --- |
| TDD RED | production 변경 전 targeted 52 tests에서 private entry leak assertion 실패 |
| targeted GREEN | `SuspendedJdbcCaffeineRepositoryExtraTest` 54/54, `BUILD SUCCESSFUL` |
| affected module | H2/MySQL/PostgreSQL 경로 포함 405 tests 통과, 22 skipped |
| 정적 검증 | `:bluetape4k-exposed-jdbc-caffeine:detekt` 성공 |
| ABI | `develop` 임시 worktree baseline과 candidate `javap -public -s` diff 없음; module compile 성공 |
| 문서 parity | EN/KO bounded contract token 8/8 일치 |
| diff hygiene | `git diff --check` 성공 |

`scripts/validate_module_readme_parity.rb`는 이 모듈의 계약이 아닌 JDBC
FluentQuery marker pair를 요구해 N/A로 분류했다. 검증 목적을 바꾸기 위해
unrelated marker를 README에 추가하지 않았다.

## 재발 방지

1. private coordination map은 `compute` 계열 연산 밖에서 count를 변경하거나
   `remove(key)`로 조기 제거하지 않는다.
2. 결과 계약을 바꿀 때는 성공 coalescing과 실패·취소·`null` 순차 retry를
   분리한 T1~T7 matrix와 실제 `Job.cancelAndJoin` 테스트를 함께 갱신한다.
3. public API/KDoc 또는 README에 exact-once failure, metrics, external driver
   semantics를 암시하는 문구를 추가하지 않는다.
4. 후속 PR에서는 baseline/candidate `javap -public -s`, full module test,
   `detekt`, EN/KO parity, `git diff --check` 증거를 같은 DoD에 남긴다.

## 남은 상태

lesson은 로컬 검증 수치와 독립 7-Tier `CLEAR`(P0/P1/P2/P3 `0`)를 반영했다.
Lore commit/PR/CI는 별도 gate이며, merge·canonical sync·worktree cleanup은
fresh exact-head 승인 이후에만 수행한다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — Issue/Epic, 대상 모듈, stable manual boundary와 검증 명령을 기록했다.
- [x] SPW-02 — race 경계, 결과별 계약, rollback/재발 방지 guard를 기록했다.
- [x] SPW-03 — 한국어 technical register와 `Mutex`, `entry`, `waiter`, `retry` 용어를 유지했다.
- [x] SPW-04 — 실제 diff, 테스트 수치, ABI/parity 결과와 계획을 대조했다.
- [x] SPW-05 — 문서 전체를 read-back하고 review/PR gate를 unchecked 상태로 남겼다.
