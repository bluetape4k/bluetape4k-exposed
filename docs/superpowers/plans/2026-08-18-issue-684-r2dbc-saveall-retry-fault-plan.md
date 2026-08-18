# Issue #684 R2DBC `saveAll` 재시도 fault-injection 구현 계획

> **준수 표면:** `$bluetape-workflow`, `$bluetape-kotlin-patterns`,
> `test-driven-development`. 구현은 RED를 확인한 뒤 최소 변경으로 진행한다.

## 목표와 범위

- 대상: `:bluetape4k-exposed-spring-boot-r2dbc`의 `saveAll(Flow/Iterable)`
  retry 회귀 검증
- 기준: [설계 문서](../specs/2026-08-18-issue-684-r2dbc-saveall-retry-fault-design.md),
  Issue #684, Epic #658 Slot 4
- 선행: #650 / PR #683 merge (`d3c60a760cffa3c960a3639578e765492cb8f1a7`)
- 제외: `docs/manual/**`, public ABI, Gradle dependency, ToxiProxy, #644/#674

## 파일 책임

1. `spring-boot/r2dbc/src/test/kotlin/.../OneShotR2dbcFaultFactory.kt`
   - H2/PostgreSQL `ConnectionFactory` delegate/proxy와 one-shot
     `R2dbcTransientResourceException` publisher를 test-only로 제공한다.
2. `spring-boot/r2dbc/src/test/kotlin/.../SimpleExposedR2dbcRepositoryRetryTest.kt`
   - retry Flow/Iterable integration test와 DB/default transaction 복구 helper를
     전용 테스트 클래스에 둔다. 기존 `SimpleExposedR2dbcRepositoryTest`의 #650
     transaction/cancellation 테스트는 변경하지 않는다.
3. `spring-boot/r2dbc/src/main/kotlin/.../SimpleExposedR2dbcRepository.kt`
   - 기본 범위에서는 변경하지 않는다. RED/GREEN에서 실제 production regression이
     발견될 때만 attempt-local 결과 구조를 최소 수정하고, 발견되지 않으면 N/A
     근거를 남긴다.
4. `docs/lessons/2026-08-18-issue-684-r2dbc-saveall-retry-fault.md`
   - retry fixture와 replayability/attempt-local 결과 계약을 재사용 가능한 lesson으로
     남긴다. 구현에서 새 운영 지침이 없으면 기존 lesson 재사용 여부를 기록한다.

## 순서와 검증

### Task 0 — preflight와 worktree

- 현재 `develop` HEAD, linked worktree, dirty `TEST_APPLY_PATCH_TMP.txt`, Issue/Epic
  metadata, GNO sparse 결과를 기록한다.
- feature worktree `test/r2dbc-saveall-retry-fault`에서만 변경한다.
- baseline: `SimpleExposedR2dbcRepositoryTest` 106 passing, 2 pending을 fresh
  `--no-build-cache`로 확인했다.

### Task 1 — calibration RED 테스트

- test-only adapter와 retry database helper를 추가한다.
- `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=1`로 H2 custom retry database의
  `defaultMaxAttempts=1` calibration 경로를 선택한다.
- 첫 commit fault가 재시도 없이 Flow/Iterable 호출자에게 전파되는지 확인한다.
- 이 단계는 fault adapter와 테스트 선택이 실제로 fault를 관찰하는지 확인하는
  calibration이며, production regression이나 양쪽 overload의 차등 동작을
  주장하지 않는다.
- production code는 아직 변경하지 않는다.
- 명령:

  ```bash
  BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=1 EXPOSED_TEST_DB=H2 ./gradlew \
    :bluetape4k-exposed-spring-boot-r2dbc:test \
    --tests 'io.bluetape4k.spring.data.exposed.r2dbc.SimpleExposedR2dbcRepository*Test' \
    --rerun-tasks --no-build-cache --no-configuration-cache \
    --no-parallel --max-workers=1 --console=plain
  ```

- 기대 RED: 환경변수로 선택한 `maxAttempts=1` 설정에서 injected commit fault가 재시도 없이
  `R2dbcTransientResourceException`으로 전파되어 해당 클래스 38건 중 retry
  테스트 2건이 실패한다. 이는 calibration evidence이며 production regression으로
  분류하지 않는다.

### Task 2 — test harness GREEN

- adapter가 연결한 `R2dbcDatabase`에만 기본 `defaultMaxAttempts=2`와 zero delay를
  명시한다. 공용 `withDb` fixture와 production transaction 설정은 변경하지 않는다.
- Flow/Iterable 모두 transaction-returning `buildList` 구조를 그대로 검증하고,
  begin/commit/rollback/close callback counter와 입력 재수집 횟수를 확인한다.
- 실제 production regression이 없다면 production diff는 N/A로 기록하고, public KDoc,
  outer transaction ownership, input replayability 문구는 변경하지 않는다.
- RED 명령과 같은 test selector를 사용하되 환경변수를 제거하거나
  `BLUETAPE_R2DBC_SAVE_ALL_MAX_ATTEMPTS=2`로 지정해 GREEN을 확인한다.

### Task 3 — 회귀·backend 검증

- H2 class test를 `--rerun-tasks --no-build-cache --no-configuration-cache`로
  재실행하고 XML count를 기록한다.
- 해당 module 전체 test를 H2에서 실행한다.
- PostgreSQL representative run은 Testcontainers를 사용해 H2와 순차 실행한다.
  driver/container unavailable이면 실패 원인과 실제 0 test/skip을 분리 기록한다.
- 기존 top-level cancellation, upstream/downstream failure, outer transaction 테스트를
  포함한 `SimpleExposedR2dbcRepository*Test`를 재실행한다.
- `git diff --check`, `detekt` 또는 module compile/test fallback, Kotlin final checklist를
  적용한다.

### Task 4 — lesson·pre-PR 수렴

- retry adapter가 확인한 Exposed 1.4.0 retry 경계, attempt-local 결과, replayable input
  조건과 non-H2 한계를 한국어 lesson에 기록한다.
- 변경 파일을 test-only + 최소 production fix로 제한하고 P0/P1=0을 독립 검토한다.
- PR 생성·CI·merge는 별도 authority와 fresh approval이 있을 때만 진행한다.

## DoD

- [x] 계획/설계 문서와 issue acceptance가 일치한다.
- [x] 환경변수로 재현한 `maxAttempts=1` RED와 기본 `maxAttempts=2` GREEN의 raw 결과가 기록됐다.
- [x] Flow/Iterable fault-injection test가 H2와 PostgreSQL에서 통과한다.
- [x] production diff가 없고 test-only 변경으로 제한된다.
- [x] module targeted/full test 및 representative backend evidence가 fresh하다.
- [x] lesson gate, diff check, Kotlin final checklist, P0/P1 review가 통과한다.
- [ ] PR/merge/cleanup은 이번 계획의 후속 gate로 남긴다.

상태: `LOCAL_DONE_PENDING_PR` — 구현·검증·독립 리뷰는 완료했으며 PR 생성과
merge/cleanup은 별도 authority gate로 남긴다.
