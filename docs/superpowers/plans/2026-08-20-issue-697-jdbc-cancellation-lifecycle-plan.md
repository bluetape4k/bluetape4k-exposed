# Issue #697 JDBC 병렬 key enumeration 취소·transaction lifecycle 구현 계획

> 이 계획은 승인된 Type-C bug-fix scope를 순서대로 실행한다. 각 단계의 RED/GREEN,
> connection lease, 문서와 PR 증거를 fresh output으로 남긴다.

## 목표와 architecture

`parallelJdbcKeyEnumeration`이 실패 또는 caller interrupt를 반환하기 전에 시작된 모든
child의 Exposed transaction과 JDBC connection이 종료되도록 한다. public API와 caller-owned
executor 소유권은 유지한다. range별 `VirtualFuture` 옆에 transaction wrapper 바깥의
completion latch를 두고, permit 반환과 실패 cleanup join을 실제 transaction 종료 시점에
연결한다.

## 선행 조건과 허용 범위

- base: `develop` `5b2f0a1a4f4580d2f293d42b622080ad9e3bd8c2`
- branch/worktree: `fix/issue-697-cancellation-lifecycle` /
  `.worktrees/fix-issue-697-cancellation-lifecycle`
- live issue: #697, assignee `debop`, milestone `2.0.0`, labels `bug`, `performance`,
  `stacked-pr`
- 선행 slot: #698 merged as PR #705
- 허용 파일: `JdbcParallelKeyEnumeration.kt`, `JdbcParallelKeyEnumerationTest.kt`,
  PostgreSQL/MySQL lifecycle test additions when required, this spec/plan/review/lesson
- 금지 범위: public/API/ABI signature, `VirtualFuture` public API, R2DBC, #690 benchmark,
  dependency/catalog/workflow, stable `docs/manual/**`(1.12.1)
- real DB tests: PostgreSQL와 MySQL Testcontainers를 동시에 실행하지 않고 순차 실행한다.

## Task 0 — preflight와 root-cause evidence

1. `git status --short --branch`, `git rev-parse HEAD`, base diff union, `git diff --check`를
   기록하고 worktree가 clean인지 확인한다.
2. `gh issue view 697`, `gh issue view 659`, `gh issue view 698`, `gh issue view 690`을
   다시 읽어 live metadata와 stacked order를 고정한다. GNO 검색 결과는 direct GitHub와
   대조하며 stale milestone 문구를 계획에 복사하지 않는다.
3. 현재 source에서 `cancelAndAwait`, `VirtualFuture.await`, `asCompletableFuture`,
   `virtualThreadJdbcTransactionAsync`의 control/resource flow를 다시 확인한다.
4. systematic-debugging Phase 1/2 기록: 취소된 `Future.get()`이 worker completion과
   다르고, existing cooperative-interrupt test가 이 경계를 검증하지 않는다는 단일
   hypothesis를 고정한다.

**Expected DoD:** issue/source/branch evidence와 root-cause hypothesis가 문서·report에
남고, unrelated dirty path가 없으며, 첫 mutation 대상이 허용 목록과 일치한다.

## Task 1 — RED regression: H2 lifecycle oracle

**File:** `exposed/jdbc/src/test/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumerationTest.kt`

1. `3+ ranges`, `maxConcurrency=2`, 실패 range와 sibling을 deterministic하게 시작한다.
2. sibling은 `InterruptedException`을 기록하지만 bounded delay 동안 계속 실행하고,
   `activeChildren`를 `finally`에서 감소시킨다.
3. 실패가 반환된 직후 `activeChildren == 0`, interrupt 관찰, 원래 exception identity,
   executor `isShutdown == false`를 확인한다. cleanup `finally`는 RED에서도 worker가
   종료되도록 bounded join을 수행한다.
4. caller interrupt variant는 별도 test로 parent call을 test-owned thread에서 실행하고,
   caller interrupt 후 child 종료와 interrupt flag 복원을 확인한다.

**Command:**

```bash
EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-jdbc:test \
  --tests 'io.bluetape4k.exposed.jdbc.JdbcParallelKeyEnumerationTest' \
  --tests '*interrupt*' --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-parallel --max-workers=1 --console=plain
```

**Expected DoD:** compilation succeeds and the new assertion fails because current
`cancelAndAwait` returns while an interrupt-ignoring sibling remains active. Compilation or
fixture error is not a valid RED; repair the test first.

## Task 2 — GREEN: transaction-bound completion and uninterruptible join

**File:** `exposed/jdbc/src/main/kotlin/io/bluetape4k/exposed/jdbc/JdbcParallelKeyEnumeration.kt`

1. Add a private child handle containing `VirtualFuture<List<ID>>` and a completion latch.
2. Submit the child with existing executor/database/isolation/readOnly semantics, but place
   `permit.release()` and latch completion in a `finally` outside the Exposed `transaction {}`
   call. Do not add a public overload or modify `VirtualFuture`.
3. Replace future-only `cancelAndAwait` with two-phase cancellation request plus uninterruptible
   lifecycle join. Restore caller interrupt status only after all child latches complete.
4. Keep `checkCompletedFailures` and `unwrapExecutionFailure` behavior unchanged except for
   the child-handle type.

**Expected DoD:** the Task 1 tests pass, permit reuse occurs only after transaction cleanup, and
the diff has no public signature or executor ownership change.

## Task 3 — GREEN backend lease proof

**Files:** existing PostgreSQL/MySQL parallel enumeration test sources as needed.

1. Extend the existing test-only Hikari fixtures with a bounded interrupt-ignoring sibling
   failure/cancel case using `maxConcurrency=2` and at least 3 ranges.
2. Assert the failure cause, transaction completion barrier, `tracker.active == 0`, and
   caller executor liveness after the helper returns.
3. Keep PostgreSQL and MySQL commands sequential. A missing Docker/selector is N/A/PENDING;
   container startup, driver, schema, or assertion failure is FAIL and cannot be relabeled skip.

**Expected DoD:** fresh PostgreSQL and MySQL targeted XML show failure/error 0 and active lease
0 after failure/cancel. Environment unavailability remains explicitly PENDING.

## Task 4 — affected regression and static proof

1. Rerun H2 targeted lifecycle tests, then full `:bluetape4k-exposed-jdbc:test` H2.
2. Run `./gradlew :bluetape4k-exposed-jdbc:detekt` (or repository-equivalent affected
   detekt task), `git diff --check`, and base/untracked scope union.
3. Run ABI/API inspection only as a no-production-signature guard; do not claim an ABI execution
   PASS when production signature diff is zero and the configured ABI task is N/A.
4. Re-read KDoc touched by the fix and verify it still says caller owns executor and actual
   completion is joined on failure.

**Expected DoD:** fresh targeted/full/static outputs pass; no forbidden path appears; P0/P1=0.

## Task 5 — lesson and independent review

1. Review the final source/test diff through six 7-Tier lenses: correctness/lifecycle,
   performance/permit pressure, reliability/driver behavior, security/side effects,
   developer/API compatibility, and operator/diagnostics. Record P0/P1/P2 with file evidence
   in `docs/review/2026-08-20-issue-697-jdbc-cancellation-lifecycle-review.md`.
2. Create `docs/lessons/2026-08-20-jdbc-cancellation-lifecycle.md` because this is a reusable
   rule: cancelled `Future` completion is not transaction/connection completion. Include RED,
   GREEN, backend evidence, N/A/PENDING and future guard.
3. Run the Korean terminology audit on all new/changed Korean artifacts and read back every
   Markdown file. Repair findings before commit.

**Expected DoD:** review P0=0/P1=0, lesson committed, SPW-01~05 complete for each new artifact,
and `git diff --check` passes.

## Task 6 — commit and PR readiness

1. Reconcile `CG-01` through `CG-10`, create a Lore-protocol Korean commit, and record exact
   local head. Push only the semantic branch after PR authority is established.
2. Re-read current AGENTS, bugfix/workflow/common-gates, PR template and #697 metadata before
   PR creation. Create Korean PR linked with `Fixes #697`, assignee `debop`, milestone/labels
   mirrored, and `## DoD Status` as the final H2.
3. Inspect exact-head PR checks/reviews. Path-filtered/skipped jobs are N/A only with concrete
   trigger evidence; required pending/failed jobs keep the result PENDING/FAIL.
4. Stop at merge-ready (`CG-15`, fresh user approval still required). Do not merge or delete
   the feature branch in this plan without a later exact-head approval.

**Expected DoD:** live PR metadata/body/head and CI/review evidence are fresh, counts reconcile,
and final report states exact remaining approval or CI gaps.

## Rollback and rerun points

- If RED does not fail for active-child leakage, stop and return to root-cause tracing; do not
  implement against a passing or fixture-broken test.
- If the latch join causes an actual driver hang, preserve the bounded reproduction and report
  PENDING rather than adding a timeout that silently returns with a live lease.
- If PostgreSQL passes only after retry, investigate and record WATCH with the first failure log.
- Any production/API/ABI, R2DBC, benchmark, manual, workflow, or dependency drift is out of
  scope and must be reverted or split into a new issue before PR progression.

## Plan DoD

- [x] Task 0 root-cause evidence fresh
- [x] Task 1 H2 RED proves intended lifecycle defect (`10 tests, 2 failures` before fix)
- [x] Task 2 production fix GREEN (`10/0/0`, H2)
- [x] Task 3 PostgreSQL/MySQL lease proof (`7/0/0`, `11/0/0`)
- [x] Task 4 affected tests/static/scope guard (H2 full `211/0/0/23`, shared `72/0/0/5`,
  detekt and diff check pass)
- [x] Task 5 7-Tier review, lesson, writer audit
- [ ] Task 6 exact-head PR/CI DoD; merge remains a separate approval gate

### 검증 ledger

| 범위 | tests | failures | errors | skipped | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| JDBC H2 targeted lifecycle | 10 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| JDBC H2 full module | 211 | 0 | 0 | 23 | `BUILD SUCCESSFUL` |
| 공유 JDBC test module | 72 | 0 | 0 | 5 | `BUILD SUCCESSFUL` |
| PostgreSQL Testcontainers targeted | 7 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |
| MySQL Testcontainers targeted | 11 | 0 | 0 | 0 | `BUILD SUCCESSFUL` |

`detekt`와 `git diff --check`도 성공했다. PostgreSQL/MySQL은 Docker가 있는 로컬에서
순차 실행했으며, hosted exact-head CI와 nightly 전체 행렬은 PR 이후 delivery gate다.

## SPW-01~05 계획 gate

- [x] **SPW-01** — Type-C audience, issue/base/head, source paths, commands, external Docker
  boundary와 금지 범위를 고정했다.
- [x] **SPW-02** — dependency order, exact files, RED/GREEN, rollback/rerun, PR stop gate를
  포함했다.
- [x] **SPW-03** — 한국어 technical register와 API/command/error token 보존을 확인했다.
- [x] **SPW-04** — 설계 acceptance와 plan task/command가 일대일로 대응하며 unsupported
  backend/driver claims를 만들지 않는다.
- [x] **SPW-05** — Markdown read-back으로 headings, code fences, tables, checkboxes와
  final DoD 문장을 확인했다.
