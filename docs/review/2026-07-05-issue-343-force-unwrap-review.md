# Issue #343 Review — force unwrap hotspots

## Changed scope

- Removed force unwraps from scoped production code:
  - Ktor Exposed health readiness dispatcher invariant.
  - Batch step builder reader/writer required state.
  - JDBC Redisson repository `getAll` map value filtering.
- Removed force unwraps from scoped shared helper artifacts:
  - JDBC `withDb`, `withDbSuspending`, `withTables`, and `withTablesSuspending`.
  - R2DBC `withDb` and `withTables`, including nullable default isolation level handling.
- Removed literal force-unwrap examples/rationale markers from `src/main` KDoc/comments so production grep is not noisy.

## Remaining uses

- Final Kotlin grep summary after this pass:
  - Total `!!` lines: 212.
  - `src/main` `!!` lines: 0.
  - Scoped hotspot files `!!` lines: 0.
- Remaining force unwraps are test-only assertions, fixture conveniences, benchmark cleanup, or follow-up assertion-normalization candidates. They are intentionally left for issue #337 or narrower module-specific cleanup.

## Verification

- Baseline before code changes: `./gradlew --no-parallel :bluetape4k-exposed-ktor:test :bluetape4k-exposed-batch:test :bluetape4k-exposed-jdbc-tests:compileTestKotlin :bluetape4k-exposed-r2dbc-tests:compileTestKotlin :bluetape4k-exposed-jdbc-redisson:test` — BUILD SUCCESSFUL in 36s.
- After hotspot edits: same command — BUILD SUCCESSFUL in 1m 46s.
- After KDoc/comment cleanup: same command — BUILD SUCCESSFUL in 1s, configuration cache reused.

## Review result

- Production and shared-helper hotspots named in #343 no longer use force unwrap.
- Failure semantics are explicit: `requireNotNull` remains for caller-required Ktor/builder inputs; `checkNotNull` is used for internal DB helper state and R2DBC isolation invariants.
