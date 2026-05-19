# Issue #118 — Batch Reader Close Resets Cursor State

**Date**: 2026-05-19  
**Issue**: #118  
**Module**: `utils/batch`

## Context

`ExposedJdbcBatchReader.close()` and `ExposedR2dbcBatchReader.close()` cleared the in-memory buffer but did not reset
the reader state through the same path as `open()`. The previous close path also set `lastFetchedKey` to `null`, which
could break partitioned readers using `minKey` if the same reader instance was reused by retry code.

## Decision

Move reader state reset into a shared `resetState()` helper and use it from both `open()` and `close()`. The helper
restores `lastFetchedKey` to `minKey`, clears read/commit cursors, clears the buffer, and resets `exhausted`.

## Outcome

JDBC and R2DBC batch readers now preserve the partition lower bound after `close()`. Reusing a reader instance no
longer silently restarts from the beginning of the table when `minKey` was configured.

## Verification

- `git diff --check`
- `./gradlew :bluetape4k-exposed-batch:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --tests "io.bluetape4k.batch.jdbc.ExposedJdbcBatchReaderTest.close 후*" --tests "io.bluetape4k.batch.r2dbc.ExposedR2dbcBatchReaderTest.close 후*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-batch:test --console=plain --no-daemon`
- Claude CLI review and rereview: P0/P1=0
- Codex current-session review: P0/P1=0

## Future Guard

When reader lifecycle methods reset cursor state, use the same helper from `open()` and `close()`. For partitioned
readers, never reset `lastFetchedKey` to `null`; restore the configured partition lower bound.
