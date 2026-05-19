# Testcontainers Serial Verification

## Context

Issue #118 and #119 were verified in separate worktrees. The code changes were
correct, but running Testcontainers-backed Gradle tests from multiple worktrees
at the same time caused PostgreSQL/MySQL startup noise and left orphan
`org.testcontainers=true` Docker networks.

## Decision

Keep Ryuk disabled and reusable Testcontainers enabled for local bluetape4k
work, but do not run Testcontainers-backed Gradle commands in parallel across
modules, worktrees, delegated agents, or separate Gradle JVMs.

The Gradle `BuildService` test mutex in `build.gradle.kts` serializes `Test`
tasks only inside one Gradle invocation. It does not coordinate separate
`./gradlew` processes launched from different worktrees.

## Outcome

After removing only labeled Testcontainers residue and rerunning tests
sequentially:

- `:bluetape4k-exposed-batch:cleanTest :bluetape4k-exposed-batch:test --no-build-cache`
  passed with 332 tests and 1 skipped.
- `:bluetape4k-exposed-jdbc-caffeine:cleanTest :bluetape4k-exposed-jdbc-caffeine:test --no-build-cache`
  passed with 309 tests and 22 skipped.
- Final Docker check showed no `org.testcontainers=true` containers or networks.

## Future Guard

Use one combined Gradle command or explicit sequential module commands for
Testcontainers verification. If a run is interrupted or accidentally
concurrent, inspect `docker ps -a --filter label=org.testcontainers=true` and
`docker network ls --filter label=org.testcontainers=true`; clean only labeled
residue before rerunning.
