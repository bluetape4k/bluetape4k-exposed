# Issue #165 — JDBC Batch Retry Empty Re-query

**Date**: 2026-05-19  
**Issue**: #165  
**Module**: `utils/batch`

## Context

`ExposedJdbcBatchJobRepository.findOrCreateJobExecution()` handled unique-constraint races by re-querying the
competing job execution, but the JDBC retry path used `.first()`. If the winner row disappeared or no longer matched
the restartable status filter before the retry query, callers saw a generic `NoSuchElementException`.

The R2DBC counterpart already used `firstOrNull() ?: IllegalStateException(...)` with job context.

## Decision

Align JDBC with R2DBC by moving the retry re-query into an internal helper that returns the winner row with
`firstOrNull()` or throws a contextual `IllegalStateException` containing `jobName` and `params`. While touching the
retry catch, keep coroutine cancellation explicit by rethrowing `CancellationException` before broad exception handling.

## Outcome

The JDBC retry path no longer leaks `NoSuchElementException` for a missing winner row. The retry re-query returns an
existing winner row when present, and otherwise describes the unique-violation retry state with enough job context for
diagnosis.

## Verification

- `./gradlew :bluetape4k-exposed-batch:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --tests "io.bluetape4k.batch.jdbc.ExposedJdbcBatchJobRepositoryTest*unique violation retry*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --console=plain --no-daemon`
- `git diff --check`

## Future Guard

When catch-and-retry re-selects a winner row after a unique violation, never use `.first()` on the retry query. Use
`firstOrNull()` and throw a domain-relevant exception with the identifying keys used for the retry.
