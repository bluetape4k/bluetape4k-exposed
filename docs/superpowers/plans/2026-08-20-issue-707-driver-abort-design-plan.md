# Issue #707 JDBC driver 강제 abort 설계 실행 계획

> 이번 slot은 Type-D 조사·설계 산출물을 닫는다. generic public API나 driver-specific
> abort 구현은 capability 증거와 별도 approval 뒤의 후속 slot으로 남긴다.

## 기준과 허용 범위

- base: `develop` `9fda4b0984d30d9e0f4514281e663d4bd4221e04`
- branch/worktree: `design/issue-707-driver-abort` / `.worktrees/design-issue-707-driver-abort`
- live issue: #707, Epic #659, milestone `2.0.0`, assignee `debop`
- 허용 파일: `docs/superpowers/specs/**`, `docs/superpowers/plans/**`,
  `docs/review/**`, `docs/lessons/**`, test-only capability fixture가 승인된 경우의
  `exposed/jdbc/src/test/**`
- 금지: public/API/ABI, production `JdbcParallelKeyEnumeration.kt`, timeout/abort
  fallback, dependency/catalog/workflow, `docs/manual/**` `1.12.1`

## Task 0 — root-cause와 ownership preflight

1. `JdbcParallelKeyEnumeration.kt:221-275`의 child latch/permit/cancel 경계를 읽고,
   `VirtualFuture` completion과 transaction terminal을 분리한다.
2. Hikari tracker와 #694 PostgreSQL/MySQL fixture가 active lease를 관찰하는 지점을
   확인한다.
3. `JdbcParallelKeyEnumerationOptions`의 constructor/copy/component ABI를 기록하고
   public field 추가 금지를 고정한다.

**Expected:** generic timeout/abort를 시작하지 않는 근거와 active-handle identity
요건이 review에 남는다.

## Task 1 — capability source ledger — 완료(부분 runtime)

1. PostgreSQL 42.7.13, MySQL 9.7.0, MariaDB 3.5.10, CockroachDB repository
   dependency를 exact version으로 기록한다.
2. `Statement.cancel`, `Connection.abort`, driver-specific cancel method를
   statement/connection scope와 destructive 여부로 나눈다.
3. 공식 Javadoc/source URL, local `javap` output, unsupported/N/A 근거를 한 matrix에
   묶는다. source claim과 runtime evidence를 같은 PASS로 합치지 않는다.

**실행 결과:** PostgreSQL `42.7.13`, MySQL `9.7.0`, MariaDB `3.5.10`의 local
capability를 확인하고, CockroachDB는 pgjdbc 경로와 별도 서버 검증 필요성을 기록했다.
PostgreSQL은 real fixture PASS, MySQL/MariaDB/CockroachDB는 `PENDING/N/A`로 남겼다.

## Task 2 — lifecycle/race design review — 완료(후속 구현 대기)

1. generation-bound active statement/connection handle과 atomic register/clear 시점을
   설계한다.
2. abort request/acknowledgement/transaction cleanup/lease release를 sequence로
   그리고, stale pool connection과 abort executor 소유권을 failure mode로 적는다.
3. `P0/P1/P2`를 severity와 exact source location으로 review artifact에 기록한다.

**실행 결과:**
[JdbcParallelKeyEnumeration.kt:210-275](../../../exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt#L210)
의 lifecycle latch/permit 경계를 유지하면서 generation-bound active handle, stale
lease 차단, abort executor 소유권을 후속 contract로 고정했다. synthetic race와
production handle은 이 slot의 금지 범위라 구현하지 않았다.

## Task 3 — test-only evidence choice — 완료(범위 한정)

1. 최소 한 실제 driver와 한 unsupported/N/A fixture를 선택하되, Docker unavailable은
   PASS로 승격하지 않는다.
2. synthetic race fixture가 후속 implementation에서 필요한 경우에만 추가한다. 이
   slot에서 production API를 건드리지 않는다.
3. `detekt`, affected test/compile, `git diff --check`, terminology audit와 manual
   guard를 기록한다.

**실행 결과:** PostgreSQL `cancelQuery` fixture 1개를 추가했고, 전체 PostgreSQL
`8/8`, MySQL `11/11`, H2 `10/10`을 순차 실행했다. 첫 직접 driver import compile
실패는 `testRuntimeOnly` classpath에 맞춘 reflection unwrap으로 수정했다. 최종
JDBC detekt, Korean terminology audit 4개 파일 findings `[]`, `git diff --check`,
`docs/manual/**` guard도 PASS했다. H2 selector는 실제 클래스명
`JdbcParallelKeyEnumerationTest`로 재확인했다.

## Task 4 — 후속 issue handoff — 보류

1. public driver-specific adapter, generation handle 구현, real backend matrix를
   별도 child issue로 분리한다.
2. #694의 local PG 7/7·MySQL 11/11은 lifecycle evidence로 연결하되 nightly exact-head
   부재를 `PENDING`으로 유지한다.
3. #708 공통 ABI task와는 API/CI gate가 독립임을 Epic comment에 기록한다.

**보류 사유:** 이번 승인에는 GitHub issue/comment/PR 생성 target과 base/head 권한이
명시되지 않았다. local design/evidence만 갱신하고 외부 metadata mutation은 실행하지
않는다.

## Rollback/stop

- 공통 `forceAbort`, 임의 timeout, pool-wide close가 제안되면 설계를 `BLOCK`으로
  되돌리고 public API 변경 issue로 분리한다.
- real driver semantics를 source만으로 추론하면 해당 행을 `N/A/PENDING`으로 낮춘다.
- stale handle 또는 caller-owned executor 종료가 관찰되면 implementation을 시작하지
  않고 generation/ownership 설계를 수정한다.

## Plan DoD

- [x] Task 0 ownership/root-cause evidence
- [~] Task 1 four-driver capability ledger — source 전체, runtime은 PG만 PASS
- [~] Task 2 generation/lifecycle 7-Tier review — contract 완료, synthetic/production 구현 보류
- [x] Task 3 real/unsupported fixture boundary — PG PASS와 나머지 PENDING/N/A 분리
- [~] Task 4 follow-up issue handoff and Korean lesson — lesson local artifact는 추가했지만,
  GitHub handoff는 target/base/head 권한 대기

## 구현 gate

현재 판정은 `PARTIAL / PENDING`이다. PostgreSQL test-only evidence는 확보했지만,
public API·production code 변경 전에는 별도 사용자 approval과 MySQL/MariaDB/Cockroach
fresh backend evidence, generation handle 구현이 필요하다.
