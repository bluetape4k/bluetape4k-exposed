# Issue #162 — JDBC Caffeine `findAll()` Cache Warming Failures

**Date**: 2026-05-19  
**Issue**: #162  
**Module**: `exposed-jdbc-caffeine`

## Context

`AbstractJdbcCaffeineRepository.findAll()` warmed Caffeine entries with bare `runCatching {}`. That swallowed every
`Throwable`, including serious cache-key or memory failures, and left no signal when cache warming failed.

The same pattern existed in `AbstractSuspendedJdbcCaffeineRepository.findAll()`, so fixing only the blocking path
would have left the suspend repository with the same invisible failure mode.

## Decision

Replace `runCatching {}` with explicit `try/catch` in both blocking and suspend `findAll()` cache-warming loops.
Rethrow `CancellationException` before broad exception handling, keep ordinary cache warming failures non-fatal to
preserve query-result behavior, and do not catch `Error` or other non-`Exception` `Throwable`s.

## Outcome

Both repository variants now emit a warning when cache warming fails for a row and skip only that cache write.
Cancellation and fatal failures are no longer silently consumed.

## Verification

- `./gradlew :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest*CacheWarmingFailureTest*" --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.SuspendedJdbcCaffeineRepositoryExtraTest*SuspendedCacheWarmingFailureTest*" --console=plain --no-daemon`
- Targeted cache-warming regression tests cover ordinary `Exception` skip behavior with captured warning logs,
  `serializeKey()` failures with WARN-level logging, `CancellationException` propagation, and fatal `Error`
  propagation across H2, PostgreSQL, and MySQL 8.
- IntelliJ MCP diagnostics were unavailable for this worktree (`project_not_found`), so Gradle compile/test was used as fallback.

## Future Guard

When replacing silent `runCatching {}` blocks, check sibling blocking/suspend implementations before scoping the fix to
one file. Add tests for non-fatal `Exception` handling plus the emitted warning, `CancellationException` propagation,
and fatal `Throwable` propagation when the old behavior used `runCatching`.
