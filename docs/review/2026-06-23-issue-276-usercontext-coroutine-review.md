# Review - Issue #276 UserContext coroutine propagation

## Scope

- Issue: #276 `fix(core): preserve UserContext across coroutine dispatcher hops`
- Modules: `:bluetape4k-exposed-core`, `:bluetape4k-exposed-r2dbc`
- Files reviewed:
  - `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/auditable/UserContext.kt`
  - `exposed/core/build.gradle.kts`
  - `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/auditable/UserContextTest.kt`
  - `exposed/r2dbc/src/test/kotlin/io/bluetape4k/exposed/r2dbc/repository/AuditableR2dbcRepositoryTest.kt`

## Findings

- P0: none.
- P1: none.
- P2: none.
- P3: stale private `THREAD_LOCAL_USER` KDoc could imply raw `InheritableThreadLocal` is coroutine-safe. Fixed by documenting that coroutine propagation requires `asContextElement`.

## Evidence

- RED: the new coroutine-safe API test failed before implementation with `Unresolved reference 'withCoroutineUser'`.
- GREEN targeted: `UserContextTest.withCoroutineUser 는 coroutine dispatcher hop 이후에도 사용자명을 유지한다` passed.
- GREEN auditor: `AuditableR2dbcRepositoryTest.withCoroutineUser 는 virtual thread transaction 의 감사 사용자명에 전파된다` passed for H2, PostgreSQL, and MySQL_V8.
- GREEN module: `./gradlew :bluetape4k-exposed-core:test :bluetape4k-exposed-r2dbc:test --no-parallel --rerun-tasks`
  - `exposed/core`: 277 tests, 0 failures, 0 errors, 13 skipped.
  - `exposed/r2dbc`: 365 tests, 0 failures, 0 errors, 15 skipped.
- `git diff --check`: pass.

## Review Notes

- `withCoroutineUser` uses `withContext(asContextElement(username))`, so it adds a `ThreadContextElement` without replacing the caller `Job` or creating an external scope.
- `withThreadLocalUser` remains the synchronous thread API; KDoc no longer recommends it for coroutine dispatcher hops.
- `kotlinx-coroutines-core` is an `api` dependency because the public core API exposes `ThreadContextElement`.
