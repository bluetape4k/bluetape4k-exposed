# Issue #343 Cleanup Plan — force unwrap hotspots

## Target scope

1. Production code hotspots:
   - `ktor/exposed/.../ExposedKtorHealthRoutes.kt` JDBC dispatcher invariant.
   - `utils/batch/.../BatchStepBuilder.kt` required reader/writer builder state.
   - `exposed/jdbc-redisson/.../AbstractJdbcRedissonRepository.kt` and suspended variant cache map values.
2. Shared test-helper hotspots:
   - JDBC `withDb` / `withDbSuspending` / `withTables` helpers.
   - R2DBC `withDb` / `withTables` helpers, including nullable default isolation level.

## Non-goals for this pass

- Do not mechanically remove every test-only `!!` from the repo.
- Leave pure assertion or fixture unwraps for later issue #337 unless they are in shared helpers.

## Behavior lock

- Run affected module compile/tests before and after code changes.
- Preserve exception class semantics: use `requireNotNull` for caller-provided required inputs and `checkNotNull` for internal state/invariants.

## Expected remaining `!!`

- Test-only assertion fixtures and examples outside the scoped hotspots.
- Existing comments/KDoc that mention historical `!!` rationale unless they are directly misleading.
