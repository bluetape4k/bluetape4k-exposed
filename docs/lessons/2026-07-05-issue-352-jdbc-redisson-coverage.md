# Lessons & Learns: Issue #352 jdbc-redisson Coverage

## Context

`jdbc-redisson` was just below the current repository module-average instruction
coverage. The issue only needed a small lift, so the lowest-risk target was a
production contract with deterministic branch coverage rather than another
Testcontainers-heavy scenario.

## What Worked

- Kover XML parsing showed that `JdbcRedissonRepository.kt` had low instruction
  coverage while exposing simple default-method branches.
- A `MockK` `RMap` probe covered Redisson delegation, validation, empty-input
  short circuits, and pattern invalidation without starting Redis.
- Keeping the test at the interface contract level avoided production changes
  and kept the diff to one new test file.
- Re-running the full module after an initial Redisson closed-channel failure
  verified that the failure was transient and not introduced by the new test.

## Evidence

- Baseline XML instruction coverage: `80.49%`.
- Final XML instruction coverage: `82.61%`.
- Final module test/Kover command:
  - `./gradlew --no-parallel :bluetape4k-exposed-jdbc-redisson:test :bluetape4k-exposed-jdbc-redisson:koverXmlReport :bluetape4k-exposed-jdbc-redisson:koverLog`
  - Result: `446 passing`, `BUILD SUCCESSFUL`.

## Future Guard

For small coverage gaps in Redis-backed modules, check whether uncovered
interface default methods can be covered with a mock-backed contract test before
adding new container-backed scenarios.
