# Lessons & Learns: Issue #351 r2dbc-lettuce Coverage

## Context

`r2dbc-lettuce` was below the current repository module-average instruction coverage.
The largest uncovered surface was `ExposedR2dbcLettuceSuspendedLoadedMap.kt`,
which mirrors the direct suspended map contract already exercised in the JDBC
Lettuce module.

## What Worked

- Kover XML parsing identified the best coverage target before writing tests.
- Porting the sibling `jdbc-lettuce` direct map contract tests kept the change
  narrow and avoided production refactoring.
- New Redis-backed suspend tests should use `runSuspendIO` because they perform
  real Testcontainers IO rather than virtual-time coroutine scheduling.
- Running Testcontainers-backed module tests in one `--no-parallel` Gradle
  invocation preserved the repository's Redis test stability rule.

## Evidence

- Baseline XML instruction coverage: `73.71%`.
- Final XML instruction coverage: `88.33%`.
- Final module test/Kover command:
  - `./gradlew --no-parallel :bluetape4k-exposed-r2dbc-lettuce:test :bluetape4k-exposed-r2dbc-lettuce:koverXmlReport :bluetape4k-exposed-r2dbc-lettuce:koverLog`
  - Result: `138 passing`, `4 pending`, `BUILD SUCCESSFUL`.

## Future Guard

For coverage issues in sibling cache modules, start from Kover XML sourcefile
counters, then prefer direct contract tests on the largest uncovered production
surface before broadening repository scenarios.
