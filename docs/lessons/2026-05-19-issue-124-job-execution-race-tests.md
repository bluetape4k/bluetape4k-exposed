# Issue #124 - Batch Job Execution Race Test

**Date**: 2026-05-19
**Issue**: #124
**Module**: `utils/batch`

## 배경

`findOrCreateJobExecution()`은 unique-constraint violation 뒤 retry로 concurrent
caller를 처리하지만 PostgreSQL race path는 JDBC와 R2DBC repository 모두의 integration
coverage가 필요했습니다. R2DBC recovery path도 JDBC helper가 이미 제공한 contextual
disappearing-row failure behavior가 필요했습니다.

## 결정

active job execution에 partial unique index를 적용한 PostgreSQL-only race test를
추가합니다. synchronous JDBC path는 `MultithreadingTester`와
`StructuredTaskScopeTester`로, suspend R2DBC path는 `SuspendedJobTester`로
검증합니다. winner-row와 missing-row recovery를 직접 test할 수 있게 R2DBC
unique-violation re-query path를 internal helper로 추출합니다.

## 결과

concurrent caller는 같은 job name과 parameter hash의 race 뒤 동일한 active
`JobExecution` id를 받는다는 사실이 증명됩니다. JDBC와 R2DBC test는
unique-violation re-query가 winner row를 반환하거나 row가 사라졌을 때 contextual
`IllegalStateException`을 던짐도 검증합니다.

code review 뒤 race test는 raced `(job_name, params_hash)` pair에 정확히 하나의
active row가 있다고 assert하도록 강화되었습니다. 기존 JDBC self-comparison assertion은
실제 inequality check로 교체되었고 touched file의 nullable test database handle은
`!!` 대신 `requireNotNull("...")`를 사용합니다.

## 검증

- `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-batch:test --tests 'io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest' --tests 'io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepositoryTest' --no-daemon --console=plain --rerun-tasks`
- 결과: 46 tests 실행, 3 skipped, build successful.
- `./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
- 결과: 347 tests 실행, 7 skipped, build successful.
- Claude Code CLI advisor review: `claude -p --model claude-opus-4-7 --effort high`;
  recommendation은 confirmed blocker 없는 `COMMENT`였습니다. local artifact:
  `.omx/artifacts/ask-claude-code-review-issue-124-job-execution-race-20260519-182541.md`.
- post-review verification:
  `EXPOSED_TEST_DB=POSTGRESQL ./gradlew :bluetape4k-exposed-batch:test --tests 'io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest' --tests 'io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchJobRepositoryTest' --no-daemon --console=plain --rerun-tasks`
- 결과: 46 tests 실행, 3 skipped, build successful.
- post-review full module verification:
  `./gradlew :bluetape4k-exposed-batch:test --no-daemon --console=plain`
- 결과: 347 tests 실행, 7 skipped, build successful.
- 열린 IntelliJ project가 `bluetape4k-exposed`가 아닌 `bluetape4k-workshop`이어서 이
  worktree에서는 IDE reference/diagnostic tooling을 사용할 수 없었습니다.

## 향후 guard

PostgreSQL partial index에 의존하는 concurrent insert를 test할 때는 worker thread 또는
coroutine을 시작하기 전에 setup DDL을 commit합니다. 그렇지 않으면 concurrent worker가
열어 둔 repository transaction이 setup transaction의 DDL lock 뒤에서 block될 수 있습니다.

Kotlin test의 review follow-up은 편집 전 `bluetape4k-patterns`를 다시 엽니다. touched
code에서는 force unwrap을 `db.requireNotNull("db")` 같은 bluetape4k validation helper로
교체하고, 의도한 behavior를 증명하지 못한 채 통과할 수 있는 assertion 대신 direct state
assertion(`count == 1`, 실제 inequality check)을 우선합니다.
