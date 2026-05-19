# Issue #124 - Batch Job Execution Race Tests

**Date**: 2026-05-19
**Issue**: #124
**Module**: `utils/batch`

## Context

`findOrCreateJobExecution()` handles concurrent callers by retrying after a unique-constraint violation, but the
PostgreSQL race path needed integration coverage for both JDBC and R2DBC repositories. The R2DBC recovery path also
needed the same contextual disappearing-row failure behavior already exposed by the JDBC helper.

## Decision

Add PostgreSQL-only race tests with a partial unique index over active job executions. Cover the synchronous JDBC path
with both `MultithreadingTester` and `StructuredTaskScopeTester`, and cover the suspend R2DBC path with
`SuspendedJobTester`. Extract the R2DBC unique-violation re-query path into an internal helper so winner-row and
missing-row recovery can be tested directly.

## Outcome

Concurrent callers now prove that they receive the same active `JobExecution` id after a race on the same job name and
parameter hash. JDBC and R2DBC tests also verify that the unique-violation re-query returns the winner row or throws a
contextual `IllegalStateException` if the row disappears.

After code review, the race tests were strengthened to assert that exactly one active row exists for the raced
`(job_name, params_hash)` pair. The pre-existing JDBC self-comparison assertion was replaced with an actual inequality
check, and nullable test database handles in the touched files now use `requireNotNull("...")` instead of `!!`.

## Verification

- `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-batch:test --tests 'io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest' --tests 'io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepositoryTest' --no-daemon --console=plain --rerun-tasks`
- Result: 46 tests executed, 3 skipped, build successful.
- `./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
- Result: 347 tests executed, 7 skipped, build successful.
- Claude Code CLI advisor review: `claude -p --model claude-opus-4-7 --effort high`; recommendation was `COMMENT` with
  no confirmed blockers. Local artifact:
  `.omx/artifacts/ask-claude-code-review-issue-124-job-execution-race-20260519-182541.md`.
- Post-review verification:
  `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-batch:test --tests 'io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest' --tests 'io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepositoryTest' --no-daemon --console=plain --rerun-tasks`
- Result: 46 tests executed, 3 skipped, build successful.
- Post-review full module verification: `./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
- Result: 347 tests executed, 7 skipped, build successful.
- IDE reference/diagnostic tooling was unavailable for this worktree because the open IntelliJ project was `bluetape4k-workshop`, not `bluetape4k-exposed`.

## Future Guard

When testing concurrent inserts that depend on PostgreSQL partial indexes, commit setup DDL before launching worker
threads or coroutines. Otherwise, repository transactions opened by concurrent workers can block behind the setup
transaction's DDL locks.

For review follow-ups in Kotlin tests, re-open `bluetape4k-patterns` before editing. In touched code, replace force
unwraps with bluetape4k validation helpers such as `db.requireNotNull("db")`, and prefer direct state assertions
(`count == 1`, real inequality checks) over assertions that can pass without proving the intended behavior.
