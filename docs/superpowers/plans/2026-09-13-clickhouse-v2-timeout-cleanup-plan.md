# #875 JDBC V2 timeout·cancellation·cleanup 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. 이 세션은 독립 lane dispatch 실패 시 inline fallback 규칙에 따라 주 세션에서 실행한다.

**Goal:** ClickHouse JDBC V2의 timeout·cancellation·cleanup 경계를 실제 서버 관찰, bounded 회귀 테스트, 문서 증적으로 고정하고, 재현 가능한 결함만 최소 수정한다.

**Architecture:** Test-only 관찰 helper와 기존 `ClickHouseQueryLifecycleTest`/`ClickHouseExtensionsTest`를 확장한다. `queryFlow`의 public API와 Exposed transaction 소유권은 유지하며, `Statement.cancel()` 요청 수락·local resource 정리·server-side query termination을 독립 필드로 기록한다. 변경된 production defect가 없으면 test/docs-only PR로 유지한다.

**Tech Stack:** Kotlin, JUnit 5, kotlinx-coroutines, JDBC, ClickHouse Testcontainers, HikariCP, Gradle, Markdown.

---

## Traceability and file map

| 설계 수용 기준 | 구현 산출물 |
|---|---|
| timeout/cancel matrix | `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt` |
| bounded server observation | `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/ClickHouseQueryObservation.kt` |
| queryFlow cleanup/pool reuse | `ClickHouseQueryLifecycleTest.kt`, `ClickHouseExtensionsTest.kt` |
| 실제 mutable state 누출 판정 | lifecycle test의 1-slot pool case와 verification receipt |
| 공개 사용법·N/A 경계 | `exposed/clickhouse/README.md`, `exposed/clickhouse/README.ko.md` |
| 실행 증거·lesson | `docs/superpowers/verification/2026-09-13-issue-875-timeout-cleanup.md`, `docs/superpowers/lessons/2026-09-13-issue-875-timeout-cleanup.md` |

기준 ref는 `origin/develop`의 `5f6f2e7a2aa03a10512b8dcd85414673ca462166`이다. 구현 중 source defect가 입증되지 않으면 production 파일과 public ABI는 변경하지 않고, 그 N/A 판정과 근거를 receipt에 남긴다.

## Task 1: bounded server query observation helper를 RED/GREEN으로 고정한다

**Files:**
- Create `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/ClickHouseQueryObservation.kt`
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`

- [x] **Step 1: RED — 관찰 outcome 계약을 먼저 작성한다**

  `QueryObservationOutcome`을 `PRESENT`, `DISAPPEARED`, `UNAVAILABLE`, `TIMEOUT`으로 제한하고, `awaitDisappearance(queryId, timeout)`가 query text·credential을 반환하지 않는 테스트를 추가한다. polling deadline은 호출자가 준 `Duration`을 넘지 않아야 하며, 빈 query id는 즉시 `UNAVAILABLE`로 판정한다.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest.queryObservation*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

  **Expected evidence:** 새 테스트가 helper 미구현으로 컴파일/실패한다(RED receipt).

- [x] **Step 2: GREEN — helper를 bounded, redacted 구현으로 추가한다**

  별도 JDBC connection으로 `system.processes` 또는 호환 가능한 system view를 읽고, query id를 parameter binding한다. privilege·server version·driver capability로 조회할 수 없으면 예외를 삼키지 말고 `UNAVAILABLE`과 원인을 안전한 분류 토큰으로 반환한다. `Thread.sleep` 무한 polling, 전체 SQL, URL, password, exception message 원문은 helper outcome에 넣지 않는다.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest.queryObservation*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  git diff --check
  ```

  **Expected evidence:** bounded timeout, redaction assertion, outcome별 테스트 결과와 helper compile 성공.

- [x] **Step 3: rollback/rerun**

  observation query가 image/권한별로 불안정하면 helper를 삭제하지 말고 `UNAVAILABLE` 경로와 verification receipt를 남긴다. 새 server query를 도입하지 않고 기존 test selector로 재실행한다.

## Task 2: timeout·cancel matrix와 request/remote 판정을 추가한다

**Files:**
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`

- [x] **Step 1: RED — 반복 가능한 matrix 행과 증적 필드를 정의한다**

  `connection_request_timeout`(pool 대기), `connection_timeout`(닫힌 loopback), `socket_timeout`(지연 row), server execution timeout(`setQueryTimeout`/`max_execution_time`), `Statement.cancel()`을 각각 분리한 테스트 행을 만든다. 각 행은 최소 3회 반복하고 다음을 기록한다: `driver`, `server`, `image`, `repeat`, `queryId`, `elapsedMs`, `rows`, `exceptionClass`, `requestAccepted`, `remoteOutcome`, `resourcesReleased`.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest.timeout*' \
    --tests '*ClickHouseQueryLifecycleTest.cancel*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

  **Expected evidence:** 새 matrix 행 또는 assertion이 없어 RED로 남는다.

- [x] **Step 2: GREEN — 기존 V2 property mapping과 selector를 사용해 실행한다**

  `ClickHouseV2Options`/`ClickHouseV2PropertyMapping`이 매핑하는 connection-scoped property를 그대로 사용하고, 기존 V2/Testcontainers selector를 명시적으로 요구한다. `Statement.cancel()` 호출 횟수와 반환/예외를 `requestAccepted`로 기록하고, Task 1의 query observation 결과만 `remoteOutcome`으로 기록한다. remote disappearance가 관찰되지 않으면 `N/A`이며 request acceptance를 termination 성공으로 승격하지 않는다.

  ```bash
  colima status
  docker context show
  docker info
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    -DclickhouseV2Integration=true \
    --tests '*ClickHouseQueryLifecycleTest.timeout*' \
    --tests '*ClickHouseQueryLifecycleTest.cancel*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

  **Expected evidence:** XML result의 `failures=0`, `errors=0`, `skipped=0` 또는 환경/권한에 따른 명시적 `N/A` receipt; server/image/driver/version/query id/repeat=3이 모두 존재.

- [x] **Step 3: 안정성 점검과 rollback**

  before-first-byte와 delayed-row 경계를 분리하고, 첫 byte 이후에는 retry/replay를 허용하지 않는다. Docker/image/driver 실패는 production workaround로 숨기지 말고 원인과 rerun selector를 기록한다. flaky timing이면 delay/deadline을 bounded 값으로 조정하되 증명되지 않은 PASS를 만들지 않는다.

## Task 3: `queryFlow` cleanup·exception precedence 회귀를 보강한다

**Files:**
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseExtensionsTest.kt`
- Only if a concrete RED failure proves a production defect: modify `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryStreaming.kt`

- [x] **Step 1: RED — consumer 종료와 실패 조합을 잠근다**

  normal completion, `take(1)`, downstream `CancellationException`, finite timeout, mapper failure, collector failure, `ResultSet.close` failure, `Statement.close`/connection cleanup failure를 기존 test doubles와 Testcontainers 경로에서 분리해 재현한다. primary exception identity, distinct suppressed cleanup, producer join 완료, active resource counter를 assertion한다.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest.*cleanup*' \
    --tests '*ClickHouseExtensionsTest.*flow*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

  **Expected evidence:** 새 경계 assertion이 구현 전 실패하거나 기존 누락 범위를 드러낸다.

- [x] **Step 2: GREEN — 기존 lifecycle contract를 최소 변경으로 고정한다**

  `channel.cancel`, producer cancellation, `NonCancellable` join, ResultSet close, Exposed transaction-owned statement/connection close의 순서를 검증한다. `CancellationException`을 broad catch로 삼키지 않으며 cleanup 실패가 timeout/cancel/mapper 원인을 덮지 않음을 확인한다. concrete production defect가 없으면 source 변경 없이 테스트만 유지한다. generic abort API나 강제 `KILL QUERY`를 추가하지 않는다.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest.*cleanup*' \
    --tests '*ClickHouseExtensionsTest.*flow*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

- [x] **Step 3: rollback/rerun**

  cleanup assertion이 timing에 의존하면 deterministic fake를 우선 사용하고, 실제 driver 경로는 bounded timeout과 명시적 N/A를 사용한다. source 수정이 필요해진 경우에만 해당 diff를 별도 review 대상으로 올리고 targeted RED를 보존한다.

## Task 4: Hikari 1-slot borrower state isolation을 판정한다

**Files:**
- Modify `exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt`
- Modify `exposed/clickhouse/src/main/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseV2PropertyMapping.kt` only if a concrete mutable-state leak is reproduced

- [x] **Step 1: RED — 두 borrower 사이 state leak probe를 추가한다**

  maximum pool size 1로 고정하고 borrower A/B를 순차 실행한다. `query_id`, `log_comment`, timezone/role 또는 실제 설정된 session state가 connection 반환 후 B에 누출되는지 관찰한다. 상태 저장/복원 코드를 가정하지 말고 source mapping과 driver behavior를 함께 기록한다.

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    -DclickhouseV2Integration=true \
    --tests '*ClickHouseQueryLifecycleTest.*state*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ```

- [x] **Step 2: GREEN 또는 N/A — 최소 격리만 적용한다**

  concrete leak가 있으면 기존 connection-scoped immutable property와 Exposed ownership을 보존하는 최소 restore/격리 수정과 RED regression을 추가한다. leak가 없거나 driver가 해당 state를 관찰할 수 없으면 source inspection·selector·권한·repeat 근거를 `N/A`로 기록하고 production diff를 만들지 않는다.

- [x] **Step 3: rollback/rerun**

  pool configuration을 전역 기본값으로 바꾸지 않는다. state probe가 unavailable이면 same image/driver selector로 재실행하고, N/A를 PASS나 deterministic guarantee로 표현하지 않는다.

## Task 5: EN/KO 문서와 실행 receipt를 갱신한다

**Files:**
- Modify `exposed/clickhouse/README.md`
- Modify `exposed/clickhouse/README.ko.md`
- Create `docs/superpowers/verification/2026-09-13-issue-875-timeout-cleanup.md`
- Create `docs/superpowers/lessons/2026-09-13-issue-875-timeout-cleanup.md`

- [x] **Step 1: receipt를 먼저 작성한다**

  실제 실행 후 driver/server/image/version, source SHA, selector, repeat=3, elapsed/rows, exception class, request acceptance, remote outcome, resource counters/pool reuse, skipped/N/A reason, rerun command와 artifact path를 표로 기록한다. query text, URL, credentials, raw exception message는 redacted category만 남긴다.

- [x] **Step 2: README를 양 locale로 동일 계약에 맞춘다**

  pool acquisition, connect, socket, server execution timeout을 분리하고 `Statement.cancel()` 요청과 remote termination을 별도 설명한다. V2 socket timeout·remote KILL QUERY가 미입증이면 `N/A`와 caller finite-timeout 책임을 명시한다. EN/KO에서 driver/server/image/version, selector, public API signature, 수치를 일치시키며 새 dependency/API 예시는 추가하지 않는다.

- [x] **Step 3: lesson과 문서 품질을 검증한다**

  lesson은 context, evidence, decision, outcome, verification, surprise, future guard 순서로 한국어로 작성한다. 두 README의 해당 섹션을 read-back하고 구조적 parity를 확인한다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs --json \
    exposed/clickhouse/README.md exposed/clickhouse/README.ko.md \
    docs/superpowers/verification/2026-09-13-issue-875-timeout-cleanup.md \
    docs/superpowers/lessons/2026-09-13-issue-875-timeout-cleanup.md
  git diff --check
  ```

## Task 6: 전체 검증, inline 7-Tier review, pre-PR evidence를 수렴한다

**Files:**
- Create `docs/superpowers/reviews/2026-09-13-clickhouse-v2-timeout-cleanup-plan-review.md`
- Create `docs/superpowers/reviews/2026-09-13-clickhouse-v2-timeout-cleanup-review.md`
- Inspect all changed files in this branch.

- [x] **Step 1: targeted compile/test and static checks**

  ```bash
  ./gradlew :bluetape4k-exposed-clickhouse:test \
    --tests '*ClickHouseQueryLifecycleTest*' \
    --tests '*ClickHouseExtensionsTest*' \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  ./gradlew :bluetape4k-exposed-clickhouse:detekt \
    :bluetape4k-exposed-clickhouse:compileKotlin \
    :bluetape4k-exposed-clickhouse:compileTestKotlin \
    --no-parallel --max-workers=1 --no-daemon --console=plain
  git diff --check
  ```

  changed source가 test/docs-only면 ABI 영향은 N/A로 기록하되 compile과 detekt는 통과시킨다. XML `failures=0`, `errors=0`, `skipped` 사유, Docker selector, source SHA를 receipt에 고정한다.

- [x] **Step 2: inline 7-Tier review**

  독립 code-review lane이 실패/비가용이므로 주 세션에서 비독립 inline fallback으로 수행하고 review artifact에 명시한다. 1인 개발자 human-review lane은 N/A다.

  | Tier | 확인 항목 |
  |---|---|
  | 1 성능 | bounded polling/Testcontainers 비용, timeout elapsed, benchmark claim 금지 |
  | 2 안정성 | cancellation/close exactly-once, primary/suppressed, pool reuse, flake |
  | 3 보안 | query id binding, SQL/credential/exception redaction |
  | 4 운영 | selector, driver/server/image/SHA receipt, rerun/N/A 절차 |
  | 5 개발/API | Kotlin null safety, existing Exposed ownership, public signature/dependency drift |
  | 6 사용자 | EN/KO timeout·cancel·remote wording과 caller responsibility |
  | 7 통합 | #860/#863 duplicate scope, `Closes #875` PR body, lesson/DoD evidence |

  P0/P1 발견 시 수정하고 동일 관점으로 재검토한다. P2는 artifact에 owner와 후속 범위를 기록한다.

- [x] **Step 3: commit**

  ```bash
  git add exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseQueryLifecycleTest.kt \
    exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/ClickHouseExtensionsTest.kt \
    exposed/clickhouse/src/test/kotlin/io/bluetape4k/exposed/clickhouse/support/ClickHouseQueryObservation.kt \
    exposed/clickhouse/README.md exposed/clickhouse/README.ko.md \
    docs/superpowers/specs/2026-09-13-clickhouse-v2-timeout-cleanup-design.md \
    docs/superpowers/plans/2026-09-13-clickhouse-v2-timeout-cleanup-plan.md \
    docs/superpowers/reviews/2026-09-13-clickhouse-v2-timeout-cleanup-spec-review.md \
    docs/superpowers/reviews/2026-09-13-clickhouse-v2-timeout-cleanup-plan-review.md \
    docs/superpowers/reviews/2026-09-13-clickhouse-v2-timeout-cleanup-review.md \
    docs/superpowers/verification/2026-09-13-issue-875-timeout-cleanup.md \
    docs/superpowers/lessons/2026-09-13-issue-875-timeout-cleanup.md
  git commit -m "V2 timeout과 cleanup의 관찰 경계를 고정한다" \
    -m "Constraint: request 수락, local cleanup, remote termination을 분리하고 현재 driver와 Exposed ownership을 보존한다.
Rejected: generic abort API와 강제 KILL QUERY | 원격 종료 증거와 public contract가 없어 보장을 만들 수 없다.
Confidence: high
Scope-risk: moderate
Directive: remote outcome이 입증되지 않으면 N/A와 caller 책임을 유지한다.
Tested: lifecycle/extensions targeted tests, V2 selector, detekt, compile, terminology audit, git diff --check
Not-tested: hosted CI와 merge 이후 검증은 PR 단계에서 수행한다."
  ```

## Rollback and rerun

- Docker/driver/image failure는 source 변경 없이 receipt에 `PENDING`/`N/A`와 정확한 원인을 기록하고 같은 selector로 재실행한다.
- production source 변경이 불필요하면 test/docs-only diff로 유지한다. concrete defect 수정 시 해당 RED test와 최소 revert 단위를 보존한다.
- remote observation 권한이 없으면 raw query/log를 커밋하지 않고 redacted outcome과 후속 보강 범위를 남긴다.
- root의 기존 dirty Ktor 변경과 다른 worktree에는 revert·reset·삭제를 적용하지 않는다.

## Plan SPW-01~05 자체 점검

- **SPW-01 PASS**: 독자·목표·기준 SHA·source/selector/driver/image evidence·미입증 보장 범위를 고정했다.
- **SPW-02 PASS**: task별 파일·RED/GREEN 명령·expected evidence·rollback/rerun·DoD를 포함했다.
- **SPW-03 PASS**: 한국어 기술 register와 `요청 수락`/`원격 종료`/`정리`/`재사용` 용어를 유지하고 API/driver token을 보존했다.
- **SPW-04 PASS**: spec acceptance를 Task 1~6과 기존 #860/#863/#864 근거에 trace했다.
- **SPW-05 PASS**: 전체 plan read-back, 표·코드블록·체크리스트 및 미완성 표식 부재를 확인한다.
