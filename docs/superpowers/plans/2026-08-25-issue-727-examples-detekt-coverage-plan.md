# 이슈 #727 examples Detekt 범위 확대 실행 계획

> 기준: `origin/develop` `1242e5eb990a1f362233dba9542aa6e4d7192730`, branch `build/examples-detekt-coverage`

## 추적 대상

- Issue: #727
- Goal: examples가 실제 Kotlin/7-Tier 정적검사·CI report 범위에 포함되도록 한다.
- Type: E — production behavior 없는 build/CI/harness maintenance
- Design: `docs/superpowers/specs/2026-08-25-issue-727-examples-detekt-coverage-design.md`
- Baseline: six example `detekt` tasks all `NO-SOURCE` despite `BUILD SUCCESSFUL`.

## Task 1: RED와 영향 지도를 고정한다

- [x] root Detekt 적용/aggregate와 nightly report upload의 현재 계약을 read-back한다.
- [x] examples 모듈·source set·existing baseline·raw assertion/`!!`/UUID/logging 잔여를 목록화한다.
- [x] 현재 example detekt invocation의 `NO-SOURCE`와 report 부재를 raw output으로 기록한다.

## Task 2: Detekt execution boundary를 복원한다

- [x] `/examples/`의 `exclude("**")`를 제거하고 generated source만 공통 제외한다.
- [x] root `exampleDetekt` aggregate를 추가해 examples task와 non-empty XML report를 fail-closed 검증한다.
- [x] fixture/benchmark 예외가 실제로 필요한 경우에만 좁은 baseline/allowlist와 한국어 근거를 추가한다. (추가 예외 없음; DDD UUID는 #726/#741 ownership으로 분리)

## Task 3: 발견된 Kotlin pattern findings를 정리한다

- [x] raw JUnit/kotlin.test assertion은 bluetape4k-assertions로 교체하거나, 검출 규칙의 의도적 fixture 예외를 기록한다.
- [x] production `!!`, `println`, `System.out`, `System.err`, 직접 UUID helper 잔여를 source evidence와 기존 ecosystem helper/logger에 맞춰 정리한다.
- [x] compile/test 계약과 examples의 API/DDD/Exposed boundary를 보존한다.

## Task 4: CI와 7-Tier 검증을 연결한다

- [x] nightly static-analysis가 `detekt exampleDetekt`와 examples XML 목록/비어 있지 않음 검사를 보고하도록 한다.
- [x] examples `detekt`/`exampleDetekt`, root `detekt`, targeted compile/test, workflow validation/actionlint를 순차 실행한다.
- [x] T1 Performance, T2 Stability, T3 Security, T4 Operator/Ops, T5 Developer/API, T6 User/Caller, T7 Integration을 대조하고 P0/P1=0으로 수렴한다.
- [x] English/Korean README parity는 docs behavior 변경이 없다는 concrete N/A 또는 실제 parity diff로 기록한다. (동작 문서 변경 없음)

## Task 5: delivery artifact와 PR

- [x] `docs/review/...-review.md`에 Type E checklist, Kotlin checklist, T1~T7, findings와 command evidence를 기록한다.
- [x] `docs/lessons/...-lesson.md`에 NO-SOURCE false-green 원인, rule/CI guard, future guard를 기록한다.
- [x] Korean terminology/read-back, `git diff --check`, exact head를 검증한다.
- [x] Lore 한국어 커밋 `5b8b08a36c34b956a8903d380afcc4eb1d2d9e2c`을 만들고 `build/examples-detekt-coverage`를 push했다.
- [x] Issue #727 metadata를 재확인하고 Korean PR [#742](https://github.com/bluetape4k/bluetape4k-exposed/pull/742)를 만들었다. PR body 마지막 heading은 `## DoD Status`다.
- [x] `gh pr view`/`gh pr checks`로 metadata·head·CI를 read-back했고 merge/auto-merge는 실행하지 않았다. hosted checks와 사람 리뷰는 pending이다.

## 롤백

Detekt source scope 또는 CI report guard가 기존 모듈 build/test를 깨뜨리면 마지막 source 변경만 되돌리고 baseline/로그를 보존한다. 새 helper/dependency/version pin은 추가하지 않는다.
