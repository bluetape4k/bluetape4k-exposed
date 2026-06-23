# Issue #287 Batch Atomic Ownership Code Review

Date: 2026-06-23
Scope: `:bluetape4k-exposed-batch` job/step execution ownership, JDBC/R2DBC repositories, and concurrent integration coverage.
Gate: implementation diff review

## Gate Verdict

- P0=0
- P1=0
- P2=0
- Gate: PASS

## Review Findings

| Finding | Severity | Resolution |
|---|---:|---|
| `BatchJob.run()` previously executed steps immediately after `findOrCreateJobExecution`, so concurrent runners could share the same logical `RUNNING` execution. | P1 | Added explicit `claimJobExecution` owner/lease/version CAS before step execution. Claim failure returns `BatchExecutionAlreadyClaimedException` without marking the shared execution failed. |
| Step execution and checkpoint writes previously used id-only ownership. | P1 | Added `claimStepExecution` and a claim-aware `saveCheckpoint(StepExecution, Any)` path. JDBC/R2DBC completion and checkpoint updates include owner checks when a claim owner is present. |
| Repository tests encoded the old side effect where `findOrCreate*` changed `FAILED/STOPPED` rows to `RUNNING`. | P2 | Updated tests to assert the new split contract: `findOrCreate*` returns the stored row, `claim*Execution` performs the transition to `RUNNING`. |

## Risk Review

| Area | Result | Evidence |
|---|---|---|
| Concurrency | PASS | JDBC and R2DBC integration tests now run two concurrent `BatchJob.run()` calls and assert one success, one `BatchExecutionAlreadyClaimedException`, writer open count `1`, and write count `3` across H2/PostgreSQL/MySQL_V8. |
| Restart behavior | PASS | `FAILED` and `STOPPED` rows remain restart candidates; claim transitions them to `RUNNING`. Completed jobs still create a new execution. |
| Backward compatibility | PASS | Existing repository interface methods remain. New claim methods have default implementations for simple wrappers; concrete repositories override them for atomic ownership. |
| Persistence schema | PASS | Job and step tables now include nullable `owner_id`, nullable `lease_until`, and `version` with default `0L`; mappers round-trip the new fields. |
| Verification | PASS | Targeted RED test failed before implementation with writer open count `2`; final build passes. |

## Verification Evidence

| Command | Result |
|---|---|
| `./gradlew :bluetape4k-exposed-batch:test --tests '*ExposedJdbcBatchIntegrationTest.동시 실행*' --tests '*ExposedR2dbcBatchIntegrationTest.동시 실행*' --no-build-cache` | RED before fix: `Expected <2> to equal to <1>`; GREEN after fix: 6 dialect cases passed. |
| `./gradlew :bluetape4k-exposed-batch:test --no-build-cache` | PASS: 346 passing, 7 pending. |
| `./gradlew :bluetape4k-exposed-batch:build detekt --no-build-cache` | PASS: 346 passing, 7 pending; `:detekt NO-SOURCE`; build successful. |
| `git diff --check` | PASS. |

## Residual Notes

The first `build detekt` run hit a transient R2DBC PostgreSQL seed `batchInsert`
autoinc count mismatch in an existing integration test setup. The same command
passed on immediate rerun without code changes.
