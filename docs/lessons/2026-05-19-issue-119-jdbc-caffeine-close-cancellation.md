# Issue #119 — JDBC Caffeine Close Avoids runBlocking Join

**Date**: 2026-05-19  
**Issue**: #119  
**Module**: `exposed-jdbc-caffeine`

## Context

`AbstractJdbcCaffeineRepository.close()` and `AbstractSuspendedJdbcCaffeineRepository.close()` waited for the
write-behind job with `runBlocking { writeBehindJob.join() }`. This kept the close path out of line with the R2DBC
Caffeine repository, where close uses a bounded synchronous wait to avoid blocking shutdown indefinitely.

## Decision

Align the JDBC Caffeine close path with the R2DBC Caffeine pattern: close the write-behind channel, wait for job
completion through `invokeOnCompletion` plus `CountDownLatch.await(timeout)`, then invalidate cache and cancel scope.
Log timeout warnings with explicit data-loss risk wording.

## Outcome

JDBC Caffeine close no longer uses `runBlocking` to join the write-behind job. Blocking and suspended JDBC repositories
now share the same bounded shutdown behavior as R2DBC, including safe handling when close is called before any
write-behind put or called repeatedly after the job has started.

## Verification

- `git diff --check`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest*close - write-behind*" --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.SuspendedJdbcCaffeineRepositoryExtraTest*close - write-behind*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --console=plain --no-daemon`
- Claude CLI review and rereview: P0/P1=0
- Codex current-session review: P0/P1=0

## Future Guard

Avoid `runBlocking` in repository shutdown paths. If a synchronous `close()` must wait for coroutine work, use a
bounded wait, make timeout data-loss risk explicit, and add tests for close-before-put, repeated close, and final
database persistence.
