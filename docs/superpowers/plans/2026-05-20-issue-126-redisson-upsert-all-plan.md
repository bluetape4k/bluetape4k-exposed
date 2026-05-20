# Issue 126 Redisson `upsertAll` Plan

## Classification

Type B Fast Track: existing Redisson repository API extension with tests and
README updates.

## Steps

1. Add `DEFAULT_UPSERT_BATCH_SIZE = 100` and `upsertAll` to Redisson JDBC,
   Suspended JDBC, and R2DBC repository interfaces.
2. Override `upsertAll` in the abstract Redisson repositories that own the
   writer-backed cache map.
3. Delegate existing `putAll` methods to `upsertAll`.
4. Route `findAll` cache warming through `upsertAll`.
5. Add write-through scenario tests for bulk update and new-record behavior.
6. Update English and Korean README files for JDBC and R2DBC Redisson modules.
7. Verify with targeted compile and Redis Testcontainers-backed scenario tests.

## Review Constraints

Claude Code review is unavailable because the local subscription was lowered.
External Codex CLI review is intentionally skipped because this session is
already Codex. Use local diff review plus Gradle verification as the review
evidence.

