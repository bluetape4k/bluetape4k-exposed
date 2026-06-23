# Issue #287 - Batch Atomic Ownership Claim

## Context

`BatchJob.run()` reused an active `JobExecution` from `findOrCreateJobExecution`
and immediately executed steps. When two schedulers triggered the same job and
params concurrently, both runners could observe the same `RUNNING` execution and
execute the same step pipeline.

## Decision

- Split execution lookup from execution ownership.
- Keep `findOrCreateJobExecution` and `findOrCreateStepExecution` as lookup or
  insert operations that do not claim work by themselves.
- Add `claimJobExecution` and `claimStepExecution` as owner/lease/version CAS
  gates.
- Keep active rows in `RUNNING` so the existing active-row uniqueness strategy
  still identifies one restart candidate.
- Return `BatchExecutionAlreadyClaimedException` when a second runner loses the
  claim race, without completing the shared execution as `FAILED`.

## Implementation Notes

Job and step execution rows now carry:

- `owner_id`
- `lease_until`
- `version`

JDBC and R2DBC repositories atomically claim rows only when the version matches
and the row is `FAILED`, `STOPPED`, or `RUNNING` without a valid owner lease.
Completion clears owner and lease metadata. Checkpoint writes use the claim-aware
`saveCheckpoint(StepExecution, Any)` overload from the runner path.

`InMemoryBatchJobRepository` follows the same contract for tests and simple
local usage. It also accepts directly constructed job executions in runner unit
tests by registering them during claim.

## Outcome

Concurrent JDBC and R2DBC `BatchJob.run()` calls now allow only one runner to
open the writer and process chunks. The second runner returns a failure report
with `BatchExecutionAlreadyClaimedException`.

## Verification

- RED: concurrent JDBC/R2DBC integration tests failed before the fix because two
  runners opened the writer (`Expected <2> to equal to <1>`).
- `./gradlew :bluetape4k-exposed-batch:test --tests '*ExposedJdbcBatchIntegrationTest.동시 실행*' --tests '*ExposedR2dbcBatchIntegrationTest.동시 실행*' --no-build-cache`
  - Result: H2/PostgreSQL/MySQL_V8 JDBC and R2DBC cases passed.
- `./gradlew :bluetape4k-exposed-batch:test --no-build-cache`
  - Result: 346 passing, 7 pending.
- `./gradlew :bluetape4k-exposed-batch:build detekt --no-build-cache`
  - Result: 346 passing, 7 pending; `:detekt NO-SOURCE`; build successful.
- `git diff --check`
  - Result: pass.

## Future Notes

Keep future batch restart changes on the same split contract: lookup does not
own work; claim owns work. New checkpoint or lease-renewal features should use
the claim owner field rather than id-only updates.
