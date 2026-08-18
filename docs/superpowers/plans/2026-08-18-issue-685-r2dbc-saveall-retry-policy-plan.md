# Issue #685 R2DBC `saveAll` 재시도 정책 구현 계획

> **준수 표면:** `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
> `$bluetape-bugfix`, `$bluetape-writer`, `test-driven-development`.
> 구현은 RED 증거를 확인한 뒤 최소 production 변경으로 진행한다.

## 목표와 범위

- 대상: `:bluetape4k-exposed-spring-boot-r2dbc`의 `saveAll(Flow/Iterable)`
  top-level retry 정책
- 기준: [설계 문서](../specs/2026-08-18-issue-685-r2dbc-saveall-retry-policy-design.md),
  Issue #685, Epic #658 Slot 5
- 선행: #684 / PR #686 merge (`91012e75b8d6c80dac7d8264e651dc5ece98db68`)
- 제외: `docs/manual/**`(현재 1.12.1), public ABI, 새 dependency, ToxiProxy,
  #644 bounded write, #674 driver timeout

## 파일 책임

1. `spring-boot/r2dbc/src/test/kotlin/.../SimpleExposedR2dbcRepositoryRetryTest.kt`
   - #684 fault factory를 재사용해 top-level no-retry와 outer caller-retry를
     RED/GREEN으로 고정한다.
2. `spring-boot/r2dbc/src/main/kotlin/.../SimpleExposedR2dbcRepository.kt`
   - 두 `saveAll` overload에만 private `maxAttempts = 1` transaction helper를
     적용한다. 다른 CRUD transaction 정책은 건드리지 않는다.
3. `spring-boot/r2dbc/src/main/kotlin/.../ExposedR2dbcRepository.kt`
   - top-level no-retry와 outer caller-owned replayability 조건을 overload KDoc에
     명시한다.
4. `spring-boot/r2dbc/README.md`, `spring-boot/r2dbc/README.ko.md`
   - 동일한 retry/재수집/commit-emission 계약을 EN/KO parity로 갱신한다.
5. `docs/superpowers/specs/...`, `docs/superpowers/plans/...`,
   `docs/lessons/...`
   - 설계 결정, 실행 계획, fresh evidence와 후속 경계를 한국어로 기록한다.

## 순서와 검증

### Task 0 — 격리·workflow receipt·live metadata

- `fix/r2dbc-saveall-retry-policy` worktree를 `develop` HEAD
  `91012e75...`에서 생성했다.
- Issue #685와 Epic #658을 live read-back하고 Slot 5, predecessor #684,
  base `develop`, milestone `1.13.0`, assignee `debop`를 확인했다.
- `bluetape-flow.py` receipt를 Type-C `running`으로 전환하고 mutation scope를
  code, test, README, 설계/계획/lesson 8개 path로 고정했다.
- baseline H2 retry class는 기존 정책(Flow/Iterable 각각 1회 fault 후 retry)
  으로 2 passing을 확인한다.

### Task 1 — RED 회귀 테스트

- top-level Flow에 commit fault를 주입하고 collection/side-effect 횟수가 1인지,
  `R2dbcTransientResourceException`이 전파되는지, row/result가 비어 있는지
  기대하도록 기존 retry assertions를 변경한다.
- one-shot/hot Flow는 두 번째 collect가 발생하면 명시적으로 실패하도록 만들고,
  side-effectful 입력의 counter가 1인지 확인한다.
- Iterable iterator도 한 번만 호출되고 provisional row/ID가 남지 않는지
  기대한다.
- outer `suspendTransaction`에서 caller `maxAttempts = 2`를 유지해 outer block과
  replayable Flow가 두 번 실행되고 두 번째 성공 row만 남는다는 테스트를 추가한다.
- production code는 이 단계에서 변경하지 않는다.
- 명령:

  ```bash
  EXPOSED_TEST_DB=H2 ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests 'io.bluetape4k.spring.data.exposed.r2dbc.SimpleExposedR2dbcRepositoryRetryTest' \
    --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-parallel --max-workers=1 --console=plain
  ```

- 기대 RED: 현재 top-level 구현은 Exposed 기본 retry로 성공하므로 no-retry
  assertions가 실패한다. 실패 원인과 test selector를 기록한 뒤 구현으로 이동한다.

### Task 2 — 최소 production fix

- `inTransactionWithoutRetry` private helper를 추가하고 두 `saveAll` overload만
  이 helper를 사용한다.
- transaction block의 attempt-local `buildList`와 commit 이후 `emitAll`은 유지한다.
- public signature/ABI resource를 변경하지 않고, active outer transaction의
  caller-owned retry 설정이 보존되는지 Task 1 테스트로 확인한다.

### Task 3 — 계약 문서 parity

- `ExposedR2dbcRepository` overload KDoc에서 top-level `maxAttempts=1`, outer
  caller retry, replayable·side-effect-free 입력 조건을 분리해 설명한다.
- EN/KO README의 saveAll와 Flow consumption 예시를 같은 의미로 갱신한다.
- 현재 1.12.1 `docs/manual/**`는 변경하지 않고 lesson에 그 N/A 근거를 남긴다.
- `$bluetape-writer` SPW-01~05로 사실성, 용어, EN/KO parity, diff scope,
  renderability를 점검한다.

### Task 4 — 순차 검증과 7-Tier 수렴

- H2 targeted retry class와 affected full module test를 순차 실행한다.
- PostgreSQL targeted retry class를 H2 완료 후 순차 실행한다. Container/driver
  unavailable이면 0 test를 성공으로 세지 않고 원인과 상태를 기록한다.
- `detekt`, ABI compatibility test, README parity validator, `git diff --check`
  를 실행한다.
- 기존 FluentQuery marker validator와 별도로 변경한 `saveAll` EN/KO 두 section의
  contract token(`saveAll`, `Flow`, `Iterable`, `maxAttempts`, `replayable`,
  `side effect`, `outer`, `commit`, `chunked`)을 bounded parity command로 대조한다.
- Kotlin checklist KT-01~KT-07 및 testing KT-TEST-01~05를 대조하고, Spring
  auto-config/ABI 변경이 없으므로 관련 검사는 N/A 근거를 적는다.
- P0/P1 independent review가 0으로 수렴하는지 확인하고, hot SharedFlow와
  outer emission 경계 assertion까지 포함해 P2 공백을 닫는다.

### Task 5 — PR gate

- Lore trailer를 포함한 한국어 commit을 만든다.
- PR body는 issue metadata와 mirror하고 마지막 섹션을 정확히 `## DoD Status`로
  두며 required check 합계, N/A, Blocked, 최종 상태, unchecked item을 포함한다.
- PR 생성 후 exact head CI와 review thread를 fresh read-back한다. merge/sync/cleanup은
  별도 fresh approval 이후에만 수행한다.

## DoD

- [x] 설계·계획 승인, worktree/receipt, Epic Slot 5 metadata가 기록됐다.
- [x] RED 테스트가 현재 top-level retry 재수집을 재현했다.
- [x] private no-retry helper와 outer retry 보존 테스트가 구현됐다.
- [x] KDoc 및 EN/KO README parity가 검증됐다.
- [x] H2/PostgreSQL, detekt, ABI, parity, diff check evidence가 fresh하다.
- [x] P0/P1=0 independent review가 완료됐고, hot SharedFlow 및 outer emission
      경계 assertion을 보강했다.
- [ ] PR/CI/merge/sync/cleanup은 후속 gate다.

상태: `LOCAL_VERIFIED_PENDING_PR` — RED(H2 4개 중 3개 실패)와 최소 구현을
완료했고 H2 targeted 6/6, PostgreSQL targeted 12/12, affected module
129/129, detekt, ABI 2/2, parity, diff check를 fresh evidence로 확인했다.
hot SharedFlow 재수집 방지와 outer emission 시점 `[0, 0, 1, 1]`, 최종 ID–row
대응 assertion도 보강했다. PR/CI와 merge/sync/cleanup은 별도 authority gate다.

## Writer DoD (SPW-01~05)

- [x] SPW-01 — 대상 독자, Issue/Epic, source path, command, N/A 범위를 고정했다.
- [x] SPW-02 — 파일 책임, 의존 순서, RED/GREEN, backend 검증, PR gate와 rollback
  지점을 계획에 포함했다.
- [x] SPW-03 — 한국어 technical register와 동일 용어를 모든 artifact에서 유지했다.
- [x] SPW-04 — 승인 설계, 현재 구현, 테스트·detekt·ABI 결과, README parity를
  대조해 계획 상태를 갱신했다.
- [x] SPW-05 — 계획 전체를 read-back하고 unchecked 항목(PR 이후 gate)을 명시했다.
