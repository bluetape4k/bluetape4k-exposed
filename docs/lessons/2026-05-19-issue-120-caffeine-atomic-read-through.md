# Issue 120 Caffeine Atomic Read-Through

## Context

GitHub issue #120 reported that Caffeine-backed `get(id)` and `getAll(ids)`
used check-then-load cache miss handling, allowing concurrent readers to load
and publish stale data after a newer writer updated the database and cache.

## Decision

Use Caffeine's per-key atomic loader path for read-through reads. Keep nullable
JDBC miss semantics through a small `getNullable` wrapper because Kotlin treats
`Cache.get` values as non-null. Route `getAll(ids)` through `get(id)` per key so
concurrent bulk reads receive the same per-key atomicity as single-key reads.

For R2DBC, use `AsyncCache.get` with `CoroutineScope.future { ... }` so suspend
DB loading is registered atomically without `runBlocking`.

For suspended JDBC, use a per-key coroutine `Mutex` plus `putIfAbsent` to avoid
`runBlocking` while preserving key-level loader coalescing and preventing a
read-through load from overwriting a newer cached write.

## Outcome

JDBC, suspended JDBC, and R2DBC Caffeine repositories no longer perform explicit
`getIfPresent -> DB read -> put` read-through logic for `get` or `getAll`.
Concurrent regression tests now verify that same-key concurrent misses run one
loader per key.

## Verification

- `git diff --check`
  - Success.
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test --no-daemon --console=plain`
  - `BUILD SUCCESSFUL`
  - JDBC Caffeine: 313 tests, 22 skipped.
  - R2DBC Caffeine: 60 tests, 1 skipped.
- GitHub Actions on PR #179
  - Compile, secret scan, Gradle wrapper validation, changed-module detection,
    `submit-gradle`, JDBC/R2DBC Caffeine H2 tests, coverage report, and CI
    status all passed.

## Future Guard

Do not reintroduce read-through cache miss paths that manually combine
`getIfPresent` with later `put`/`putIfAbsent`. Caffeine bulk `getAll` did not
coalesce concurrent bulk loads reliably in this version, so use per-key `get`
when the contract requires key-level atomicity.
