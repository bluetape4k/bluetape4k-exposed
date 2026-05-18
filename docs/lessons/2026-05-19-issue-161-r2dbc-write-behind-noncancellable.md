# R2DBC Caffeine Write-Behind Final Flush Cancellation

## Context

Issue #161 found that `AbstractR2dbcCaffeineRepository` called the suspend
`flushBatch()` from the write-behind job `finally` block while the job could
already be cancelled. That makes the final in-memory batch vulnerable to being
dropped during cancellation.

## Decision

Run the final `flushBatch(batch)` inside `withContext(NonCancellable)` when the
write-behind loop exits with a non-empty batch. Keep normal in-loop flush
behavior unchanged so ordinary cancellation still propagates through the job.

## Outcome

The write-behind job now retries the already collected final batch in a
non-cancellable cleanup context. The fix is intentionally scoped to #161; the
separate `close()` ordering issue remains #163.

## Verification

- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest*CancellationSafeFinalFlush*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --rerun-tasks --console=plain --no-daemon`
- Claude Review: no blocking findings and no missing-test gap for #161 scope.
- Codex CLI review: no actionable defects.

## Future Guidance

- Suspend cleanup in a cancelled coroutine must use `withContext(NonCancellable)`
  only around the cleanup operation.
- Keep #163 separate: closing the queue and then cancelling the scope still needs
  a lifecycle-ordering fix so shutdown waits for natural write-behind completion.
