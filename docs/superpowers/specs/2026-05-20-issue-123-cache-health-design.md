# Issue 123 cache health design

## Context

GitHub issue #123 requests a consistency health check for Caffeine-backed
repositories. The immediate failure mode is WRITE_BEHIND: the cache can accept a
write while the background DB flush later fails, leaving callers without an
observable signal.

Claude advisor/review is intentionally not used. The user stated that Claude
Code review is unavailable because the subscription was lowered. External Codex
CLI review is also skipped because this Codex session owns implementation,
review, and verification.

IntelliJ diagnostics are unavailable for this worktree in the current IDE
session, so validation uses repository search plus Gradle compile/tests.

## Design

Add `CacheHealthReport` to `exposed-cache`:

```kotlin
data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val isFlushJobRunning: Boolean,
    val lastFlushError: Throwable?,
)
```

The report is a read-only snapshot:

- `mode`: configured cache write mode.
- `queueDepth`: write-behind entries accepted by the repository but not yet
  observed as flushed by the background worker. This includes entries pulled
  into the current in-memory batch.
- `isFlushJobRunning`: true only when WRITE_BEHIND mode has started its worker
  and the worker job is currently active.
- `lastFlushError`: the last non-cancellation flush failure observed by the
  background worker, or null after a successful flush.

Expose the API on Caffeine-specific repository contracts:

- `JdbcCaffeineRepository.validateConsistency(): CacheHealthReport`
- `R2dbcCaffeineRepository.validateConsistency(): CacheHealthReport`

Implement the API in:

- `AbstractJdbcCaffeineRepository`
- `AbstractR2dbcCaffeineRepository`

The issue does not require Actuator integration, and labels it optional. This
increment leaves Actuator auto-configuration for a follow-up so the core runtime
contract can land with focused tests first.

## Risks

- `Channel` does not expose a stable queue size, so queue depth must be tracked
  explicitly on successful sends and after flush attempts complete.
- Calling a lazy write-behind job from health reporting would accidentally start
  the worker. Health reporting must not initialize the job.
- Flush failures are currently logged and suppressed. Health reporting must
  preserve that existing behavior while surfacing the last failure.

## Verification

- Compile `exposed-cache`, `exposed-jdbc-caffeine`, and `exposed-r2dbc-caffeine`.
- Test JDBC health snapshots for idle WRITE_BEHIND, in-flight queue depth, and
  recorded flush failure.
- Test R2DBC health snapshots for idle WRITE_BEHIND, in-flight queue depth, and
  recorded flush failure.
- Run final diff review in this session.
