# Issue #626 Suspended JDBC Caffeine Mutex 수명주기 구현 계획

> **준수 표면:** `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
> `$bluetape-writer`, `test-driven-development`.
> 구현은 RED를 확인한 뒤 최소 production 변경으로 진행한다.

## 목표와 범위

- 대상: `:bluetape4k-exposed-jdbc-caffeine`의 suspend read-through miss 조정 상태
- 기준: [설계 문서](../specs/2026-08-18-issue-626-caffeine-mutex-lifecycle-design.md),
  Issue #626, Epic #659 Slot 1
- 기준 head: `f81e01bf7da2a92354a4c9f4083e75eeb71db1d1`
- 제외: `docs/manual/**`(현재 `1.12.1`), public ABI, 새 dependency, Caffeine
  cache policy, `getAll`/write-behind 재설계

## 파일 책임

1. `exposed/jdbc-caffeine/src/test/kotlin/.../SuspendedJdbcCaffeineRepositoryExtraTest.kt`
   - private registry reflection helper와 T1~T7 lifecycle/concurrency 회귀를
     기존 `SuspendedJobTester`, `runSuspendIO`, bluetape4k assertions로 추가한다.
2. `exposed/jdbc-caffeine/src/main/kotlin/.../AbstractSuspendedJdbcCaffeineRepository.kt`
   - private `LoadMutexEntry`, per-key `compute` acquire/release, `get`의
     `try/finally` lifecycle만 최소 변경한다.
3. `exposed/jdbc-caffeine/README.md`, `README.ko.md`
   - suspend read-through의 성공 coalescing과 failure/cancel/null sequential
     retry, private cleanup을 같은 의미로 설명한다.
4. `docs/lessons/2026-08-18-issue-626-caffeine-mutex-lifecycle.md`
   - race 경계, 결과별 계약, 검증 수치와 재발 방지 guard를 기록한다.
5. `docs/superpowers/specs/...`, `docs/superpowers/plans/...`
   - 설계·실행 의사결정과 fresh evidence를 유지한다.

## 순서와 검증

### Task 0 — preflight와 격리

- 현재 feature worktree가 `develop` 기준 clean인지 확인하고 기존 canonical
  `TEST_APPLY_PATCH_TMP.txt`와 다른 worktree는 건드리지 않는다.
- live Issue #626/Epic #659 metadata, Slot 1 base/head, #646 dependency를
  재확인한다.
- baseline 명령:

  ```bash
  ./gradlew :bluetape4k-exposed-jdbc-caffeine:test \
    --tests '*SuspendedJdbcCaffeineRepositoryExtraTest' \
    --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-parallel --max-workers=1 --console=plain
  ```

- baseline 48 tests 통과를 fresh XML과 `BUILD SUCCESSFUL`로 기록했다.

### Task 1 — lifecycle RED 테스트

- 기존 same-key success 테스트는 유지한다.
- unique-key success churn, exception→retry, null→retry 테스트를 먼저 추가한다.
- gate된 loader와 real `Job.cancelAndJoin`으로 cancellation cleanup/propagation을
  추가한다.
- holder + queued waiter에서 waiter 취소, reflected entry users, holder release,
  단일 release/new-acquire boundary를 검증한다. 두 번째 loader를 gate한
  동안 세 번째 caller를 시작해 old waiter와 new entry loader가 동시에 실행되지
  않는지 확인한다.
- 새 테스트는 production code를 바꾸기 전에 실행했고, 52개 targeted 실행에서
  private map entry가 회수되지 않는 실패를 확인했다. 기대 RED는 private map
  entry가 제거되지 않아 size assertion이 실패하거나 lifecycle helper가 현재
  `Mutex` 타입과 맞지 않는 정확한 test failure다. 기존 48개 regression은
  계속 통과했다.

### Task 2 — 최소 production fix

- `loadMutexes` value를 private `LoadMutexEntry`로 바꾸고 `Mutex`와 users count를
  둔다.
- acquire는 한 번의 `compute(key)`에서 entry 생성/참조 증가를 수행한다.
- release는 한 번의 `computeIfPresent(key)`에서 identity 확인/참조 감소/0 제거를
  수행한다. map 연산 밖의 `AtomicInteger`, `synchronized`, blocking call은
  추가하지 않는다.
- `get`은 `try/finally`로 release를 보장하며 `CancellationException`을 잡아
  삼키지 않는다. 기존 cache double-check와 DB transaction은 그대로 둔다.

### Task 3 — 문서 계약 parity

- private KDoc에 entry가 waiter를 포함해 수명을 유지하고, success만 cache
  coalesce하며 failure/cancel/null은 순차 retry 가능하다는 의미를 기록한다.
- EN/KO README에 동일한 suspend read-through 계약을 추가하고 public metrics나
  exact-one-failure promise를 암시하지 않는다.
- `docs/manual/**`는 변경하지 않는다.

### Task 4 — 회귀·정적·ABI 검증

- targeted H2 class test를 fresh 실행한 뒤 affected module 전체 test를 실행한다.
- 모듈이 제공하는 representative JDBC backend는 H2 완료 후 순차 실행한다. 현재
  affected module 전체 실행은 H2/MySQL/PostgreSQL 경로를 포함해 `405 tests`,
  `22 skipped`로 통과했다.
  Docker/driver가 unavailable이면 0 test를 성공으로 세지 않고 구체적인 N/A
  증거를 남긴다.
- `detekt`와 module compile/test fallback, Kotlin final checklist,
  `git diff --check`를 실행했다.
- `develop` 임시 worktree를 별도 컴파일한 baseline과 candidate public class의
  `javap -public -s` diff가 비어 ABI 불변임을 확인했다. 기존 subclass 경로는
  module compile로 함께 검증했다.
- EN/KO README contract token parity는 8/8 bounded token 검증으로 확인했다.
  기존 `scripts/validate_module_readme_parity.rb`는 이 모듈이 소유하지 않는
  JDBC FluentQuery marker를 요구하는 validator라 실행 결과를 N/A로 기록하고,
  README에 unrelated marker를 추가하지 않았다.

### Task 5 — 독립 review와 후속 gate

- 7-Tier review에서 P0/P1 `0`으로 수렴하는지 확인하고 P2는 이슈 범위 안에서
  적용하거나 후속 이슈로 기록한다.
- lesson에 fresh 수치, no external driver N/A, race guard를 기록한다.
- Lore trailer를 포함한 한국어 commit과 PR은 별도 exact-head/CI gate로 진행한다.
  merge, canonical sync, worktree cleanup은 fresh approval 없이는 수행하지 않는다.

## 롤백과 재실행 지점

- RED가 test typo로 실패하면 테스트만 수정하고 다시 RED를 확인한다.
- GREEN에서 concurrently active duplicate loader, leaked entry, swallowed
  cancellation, public ABI drift가 보이면 production 변경을 중단하고 설계를
  재검토한다.
- backend/container 오류는 코드 실패와 분리해 raw log와 실제 test count를 기록한다.
- PR 이전에는 branch commit을 되돌릴 수 있지만 canonical `develop`이나 기존
  worktree를 destructive command로 변경하지 않는다.

## DoD

- [x] RALPLAN Architect/Critic consensus와 worktree/base metadata가 기록됐다.
- [x] T1~T7 RED가 production 변경 전에 의도한 lifecycle failure를 재현했다
  (targeted 52 tests; leaked entry assertions).
- [x] compute-linearized private entry lifecycle과 `get` finally cleanup이 GREEN이다
  (targeted 54/54).
- [x] KDoc 및 EN/KO README parity가 실제 source 계약과 일치한다.
- [x] H2 targeted/full, representative backend, detekt/compile, ABI, diff check가
  fresh evidence로 기록됐다 (full `405 passed`, `22 skipped`).
- [x] lesson gate와 독립 7-Tier review가 완료됐다 (최종 `CLEAR`, P0/P1/P2/P3 `0`).
- [ ] PR/CI/merge/sync/cleanup은 후속 authority gate다.

상태: `READY_FOR_PR_GATE` — 설계 합의, RED/GREEN, module/static/ABI 검증,
lesson과 독립 7-Tier review를 완료했다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — 대상 독자, Issue/Epic, source path, commands, stable manual
  boundary와 acceptance를 고정했다.
- [x] SPW-02 — 파일 책임, dependency order, RED/GREEN, rollback, backend/ABI
  verification과 PR gate를 포함했다.
- [x] SPW-03 — 한국어 technical register와 동일 용어를 유지하고 code token을
  보존했다.
- [x] SPW-04 — 승인 설계, 현재 implementation/test paths, issue acceptance와
  RALPLAN artifacts를 대조했다.
- [x] SPW-05 — Markdown 전체를 read-back하고 unchecked DoD를 실제 후속 작업으로
  남겼다.
