# Issue #119 — JDBC Caffeine Close가 runBlocking Join을 피함

**Date**: 2026-05-19
**Issue**: #119
**Module**: `exposed-jdbc-caffeine`

## 배경

`AbstractJdbcCaffeineRepository.close()`와
`AbstractSuspendedJdbcCaffeineRepository.close()`는
`runBlocking { writeBehindJob.join() }`으로 write-behind job을 기다렸습니다. 이는
close가 shutdown을 무한히 block하지 않도록 bounded synchronous wait를 사용하는 R2DBC
Caffeine repository와 맞지 않았습니다.

## 결정

JDBC Caffeine close path를 R2DBC Caffeine pattern과 정렬합니다. write-behind channel을
닫고 `invokeOnCompletion` 및 `CountDownLatch.await(timeout)`으로 job completion을
기다린 뒤 cache를 invalidate하고 scope를 cancel합니다. timeout warning에는 explicit
data-loss risk 문구를 남깁니다.

## 결과

JDBC Caffeine close는 더 이상 write-behind job join에 `runBlocking`을 사용하지
않습니다. blocking 및 suspended JDBC repository는 이제 write-behind `put` 전 close와
job 시작 뒤 repeated close를 안전하게 처리하는 것을 포함해 R2DBC와 같은 bounded
shutdown behavior를 공유합니다.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest*close - write-behind*" --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.SuspendedJdbcCaffeineRepositoryExtraTest*close - write-behind*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --console=plain --no-daemon`
- Claude CLI review 및 rereview: P0/P1=0
- Codex current-session review: P0/P1=0

## 향후 guard

repository shutdown path에서 `runBlocking`을 피합니다. synchronous `close()`가
coroutine work를 기다려야 하면 bounded wait를 사용하고 timeout data-loss risk를
명시하며 close-before-put, repeated close, final database persistence test를 추가합니다.
