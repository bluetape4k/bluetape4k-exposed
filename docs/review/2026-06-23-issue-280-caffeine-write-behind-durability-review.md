# Issue 280 Caffeine Write-Behind Durability Review

Date: 2026-06-23
Scope: `exposed/jdbc-caffeine`, `exposed/r2dbc-caffeine`
Issue: #280

## Verdict

P0 findings: 0
P1 findings: 0

Caffeine write-behind workers no longer mark a batch flushed until the database flush reports success. Suspended
write-behind `put` paths now enqueue first and publish to cache only after the queue send succeeds.

## Review Notes

- JDBC and R2DBC write-behind loops keep failed batches in memory and retain queue depth until a later successful
  flush clears the batch.
- Ordinary flush failures still update `lastFlushError`; successful retry clears the error and decrements depth.
- R2DBC and suspended JDBC `put` paths now avoid dirty cache entries when queue send fails, is cancelled, or targets a
  closed channel.
- Existing queue overflow coverage remains on the synchronous JDBC path, and new suspended enqueue tests cover
  full-queue cancellation plus closed-queue behavior.
- Regression tests cover permanent flush failure reporting, transient flush retry, cancelled send, closed queue, and
  full queue backpressure without cache publication.

## Validation

- `./gradlew :bluetape4k-exposed-jdbc-caffeine:testClasses :bluetape4k-exposed-r2dbc-caffeine:testClasses --rerun-tasks`
  - Result: success.
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test --continue --rerun-tasks`
  - Result: success, JDBC 329 tests with 22 skipped, R2DBC 66 tests with 1 skipped.
- `git diff --check`
  - Result: success.

## Residual Risk

- Write-behind remains an asynchronous durability tradeoff. A process crash can still lose accepted in-memory writes
  that have not reached the database; this fix prevents silent drain after observed flush/enqueue failures.
