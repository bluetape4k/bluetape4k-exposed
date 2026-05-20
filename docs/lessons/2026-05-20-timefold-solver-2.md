# Timefold Solver 2

## Context

Timefold Solver 2.1 flattened score packages, changed integer score APIs to
long-backed records, and removed several 1.x artifacts and score classes.

## Decision

Align the Exposed persistence module with the actual 2.1 public API: remove
unsupported long score columns, update score imports, and keep documentation in
sync with the reduced supported score set.

## Outcome

- Score imports now use `ai.timefold.solver.core.api.score.*`.
- Removed unsupported `*LongScore` Exposed column helpers and tests.
- Removed `timefold-solver-persistence-common` and `timefold-solver-test`
  aliases/usages because they are not published for 2.1.0.
- `SimpleScore` now stores long values with `LongColumnType`.
- README and Korean README now describe the 8 supported score types.
- AWS SDK Java, AWS SDK Kotlin, Fory Kotlin, and MyBatis Dynamic SQL were
  materialized from the central catalog as part of the coordinated dependency
  PR batch.

## Verification

- `./gradlew :bluetape4k-exposed-timefold-solver-persistence:compileTestKotlin --no-daemon`
