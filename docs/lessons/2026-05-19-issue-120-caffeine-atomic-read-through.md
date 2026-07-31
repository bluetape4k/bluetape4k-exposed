# Issue 120 Caffeine Atomic Read-Through

## 배경

GitHub issue #120은 Caffeine-backed `get(id)`와 `getAll(ids)`가 check-then-load
cache miss handling을 사용해 concurrent reader가 더 새로운 writer가 database와 cache를
업데이트한 뒤 stale data를 load하고 publish할 수 있다고 보고했습니다.

## 결정

read-through read에는 Caffeine의 per-key atomic loader path를 사용합니다. Kotlin이
`Cache.get` value를 non-null로 취급하므로 nullable JDBC miss semantics는 작은
`getNullable` wrapper로 유지합니다. `getAll(ids)`는 key마다 `get(id)`를 통과시켜
concurrent bulk read도 single-key read와 같은 per-key atomicity를 얻습니다.

R2DBC는 `CoroutineScope.future { ... }`와 함께 `AsyncCache.get`을 사용해 suspend
DB loading을 `runBlocking` 없이 원자적으로 등록합니다.

suspended JDBC는 per-key coroutine `Mutex`와 `putIfAbsent`를 사용하여 `runBlocking`
없이 key-level loader coalescing을 보존하고 read-through load가 더 새로운 cached write를
덮어쓰지 않게 합니다.

## 결과

JDBC, suspended JDBC, R2DBC Caffeine repository는 `get` 또는 `getAll`에 명시적인
`getIfPresent -> DB read -> put` read-through logic를 더 이상 수행하지 않습니다.
concurrent regression test는 이제 같은 key의 concurrent miss가 key마다 loader 하나를
실행함을 검증합니다.

## 검증

- `git diff --check` — 성공.
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test :bluetape4k-exposed-r2dbc-caffeine:test --no-daemon --console=plain`
  - `BUILD SUCCESSFUL`
  - JDBC Caffeine: 313 tests, 22 skipped.
  - R2DBC Caffeine: 60 tests, 1 skipped.
- PR #179의 GitHub Actions에서 Compile, secret scan, Gradle wrapper validation,
  changed-module detection, `submit-gradle`, JDBC/R2DBC Caffeine H2 test, coverage
  report, CI status가 모두 통과했습니다.

## 향후 guard

`getIfPresent`와 이후 `put`/`putIfAbsent`를 수동으로 결합하는 read-through cache miss
path를 다시 도입하지 않습니다. 이 version의 Caffeine bulk `getAll`은 concurrent bulk
load를 안정적으로 coalesce하지 못하므로 contract가 key-level atomicity를 요구하면
per-key `get`을 사용합니다.
