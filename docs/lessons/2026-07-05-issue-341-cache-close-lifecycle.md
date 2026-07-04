# Lessons Learned - Cache Close Lifecycle (2026-07-05)

**Related issue**: #341
**Affected modules**: JDBC/R2DBC Lettuce and Caffeine cache repositories

## L1: Cleanup steps must be independently isolated

### Problem

A close path that calls resource A and then resource B directly can skip B when A fails.
That is especially risky for repository shutdown because cache invalidation, backing cache
close, and coroutine scope cancellation are separate responsibilities.

### Lesson

Close paths should isolate independent cleanup steps and preserve cancellation semantics.
For suspend lifecycle bridges, catch `CancellationException` explicitly and rethrow it before
handling ordinary cleanup failures.

## L2: Write-behind shutdown order is part of the contract

### Problem

Write-behind repositories must stop accepting new items and wait for the bounded final flush
before post-flush cleanup. Hardening later cleanup must not move or bypass that wait.

### Lesson

Keep the write-behind close sequence explicit: close queue, wait for bounded final flush,
then run cache invalidation and scope cancellation as independent cleanup steps.

