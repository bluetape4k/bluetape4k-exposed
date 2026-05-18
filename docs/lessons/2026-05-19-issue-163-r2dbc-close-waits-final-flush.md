# Issue #163 — R2DBC Caffeine `close()` Waits For Final Flush

**Date**: 2026-05-19  
**Issue**: #163  
**Module**: `exposed-r2dbc-caffeine`

## Context

`AbstractR2dbcCaffeineRepository.close()` closed the write-behind channel and then cancelled the repository scope
without waiting for the write-behind job to finish. After #161 made the final flush cancellation-safe, `close()` still
needed to wait for that final flush before returning.

## Decision

Keep `close()` synchronous and wait until the write-behind job observes the closed channel and completes its final
flush. Avoid `runBlocking` in this production lifecycle path; use a bounded completion wait and only then invalidate
the cache and cancel the scope.

## Outcome

Write-behind shutdown now provides a stronger lifecycle contract: once `close()` returns normally, pending write-behind
entries have either been flushed by the worker or handled by the existing flush error path. A bounded timeout prevents
an indefinitely hung DB/driver from blocking shutdown forever. If that timeout is reached, shutdown proceeds with a
warning and the caller should treat any still-pending write-behind entries as not guaranteed to be durable.

## Verification

- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest*CancellationSafeFinalFlush*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --console=plain --no-daemon`
- `git diff --check`

The targeted tests cover final-flush wait behavior, close before any write-behind put, idempotent close after the
write-behind job starts, and WRITE_THROUGH close not initializing the write-behind job.

## Future Guard

For synchronous lifecycle APIs that close coroutine-backed workers, do not cancel the scope until worker completion has
been observed. Add a test that blocks the final flush and proves `close()` does not return early.
