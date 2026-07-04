# Lesson: prefer bluetape4k concurrency testers for test race launchers

## Context

Issue #342 found cache/UserContext tests that used direct executors, latches, `async(Dispatchers.Default)`, or sleep-based overlap probes for race/contention setup.

## Lesson

Use `MultithreadingTester` for blocking/threaded contention tests and `SuspendedJobTester` for suspend cache contention tests. Keep raw latches only when the assertion depends on a deterministic production boundary such as write-behind flush start/release/failure checkpoints.

## Guardrail

When replacing ad hoc concurrency probes, split the decision into two buckets:

1. Race/stress launcher: replace with `MultithreadingTester`, `SuspendedJobTester`, or `StructuredTaskScopeTester`.
2. Production boundary synchronizer: keep the latch, but document why the tester would hide the boundary under test.

## Evidence

- `UserContextTest`, `JdbcCaffeineRepositoryExtraTest`, `SuspendedJdbcCaffeineRepositoryExtraTest`, and `CacheManagementTest` now use the repo-native helpers for representative contention probes.
- Final targeted verification passed: `./gradlew --no-parallel :bluetape4k-exposed-core:test :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test`.
