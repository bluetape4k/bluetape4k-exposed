# Issue #162 — JDBC Caffeine `findAll()` Cache Warming Failure

**Date**: 2026-05-19
**Issue**: #162
**Module**: `exposed-jdbc-caffeine`

## 배경

`AbstractJdbcCaffeineRepository.findAll()`은 bare `runCatching {}`으로 Caffeine
entry를 warm했습니다. 이는 심각한 cache-key 또는 memory failure를 포함한 모든
`Throwable`을 삼키고 cache warming 실패 신호를 남기지 않았습니다.

같은 pattern이 `AbstractSuspendedJdbcCaffeineRepository.findAll()`에도 있어 blocking
path만 고치면 suspend repository에 같은 invisible failure mode가 남습니다.

## 결정

blocking과 suspend `findAll()` cache-warming loop 모두에서 `runCatching {}`을
명시적인 `try/catch`로 교체합니다. broad exception handling 전에
`CancellationException`을 다시 던지고 query-result behavior를 보존하려 ordinary cache
warming failure는 non-fatal로 두며 `Error`나 `Exception`이 아닌 `Throwable`은 catch하지
않습니다.

## 결과

두 repository variant는 이제 row의 cache warming이 실패하면 warning을 남기고 해당
cache write만 건너뜁니다. cancellation과 fatal failure는 더 이상 조용히 소비되지
않습니다.

## 검증

- `./gradlew :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest*CacheWarmingFailureTest*" --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.SuspendedJdbcCaffeineRepositoryExtraTest*SuspendedCacheWarmingFailureTest*" --console=plain --no-daemon`
- targeted cache-warming regression test는 H2, PostgreSQL, MySQL 8에서 captured warning
  log를 포함한 ordinary `Exception` skip behavior, `serializeKey()` failure의 WARN-level
  logging, `CancellationException` propagation, fatal `Error` propagation을 다룹니다.
- 이 worktree에서는 IntelliJ MCP diagnostics가 `project_not_found`로 사용할 수 없어
  Gradle compile/test를 fallback으로 사용했습니다.

## 향후 guard

silent `runCatching {}` block을 교체할 때 하나의 file로 범위를 정하기 전에 sibling
blocking/suspend implementation을 확인합니다. 기존 behavior가 `runCatching`을 썼다면
non-fatal `Exception` handling과 emitted warning, `CancellationException` propagation,
fatal `Throwable` propagation test를 추가합니다.
