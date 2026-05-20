# Issue 123 cache health lesson

## Context

Milestone 1.8.1 issue #123 requested a consistency health report for
Caffeine-backed repositories, focused on WRITE_BEHIND flush failures that were
previously only logged.

## Decision

Add a shared `CacheHealthReport` model and expose `validateConsistency()` on
the sync JDBC and R2DBC Caffeine repository contracts. Track queue depth
explicitly with an atomic counter because Kotlin `Channel` does not expose a
stable queue size.

Health reporting must not initialize the lazy write-behind worker. A separate
started flag preserves lazy startup while allowing `isFlushJobRunning` to be
reported.

## Outcome

JDBC and R2DBC Caffeine repositories now report write mode, accepted
write-behind depth, worker liveness, and the last non-cancellation flush error.

The R2DBC cancellation-safe final flush test caught an important regression:
queue-depth cleanup must happen only after a flush returns successfully. If
cleanup runs in a `finally` around `flushBatch`, a cancellation can clear the
batch before the existing NonCancellable final flush retry runs.

## Verification

- `./gradlew :bluetape4k-exposed-cache:compileKotlin :bluetape4k-exposed-jdbc-caffeine:compileKotlin :bluetape4k-exposed-r2dbc-caffeine:compileKotlin :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest"`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest"`
- `git diff --check`

## Follow-up Guidance

If adding health reporting to `SuspendedJdbcCaffeineRepository`, copy the
successful-flush-only depth cleanup rule. Do not put batch cleanup in a broad
`finally` around `flushBatch`.

Claude advisor/review and external Codex CLI review were skipped by user
instruction; this session performed local implementation, review, and
verification.
