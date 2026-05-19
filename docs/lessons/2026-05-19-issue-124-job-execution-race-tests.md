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

## Verification

- `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-batch:test --tests 'io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest' --tests 'io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepositoryTest' --no-daemon --console=plain --rerun-tasks`
- Result: 46 tests executed, 3 skipped, build successful.
- `./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
- Result: 347 tests executed, 7 skipped, build successful.
- IDE reference/diagnostic tooling was unavailable for this worktree because the open IntelliJ project was `bluetape4k-workshop`, not `bluetape4k-exposed`.

## Future Guard

When testing concurrent inserts that depend on PostgreSQL partial indexes, commit setup DDL before launching worker
threads or coroutines. Otherwise, repository transactions opened by concurrent workers can block behind the setup
transaction's DDL locks.
