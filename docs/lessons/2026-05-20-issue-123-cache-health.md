# Issue 123 Cache Health Lesson

## 배경

Milestone 1.8.1 issue #123은 이전에 log만 남던 WRITE_BEHIND flush failure에 초점을
맞춘 Caffeine-backed repository의 consistency health report를 요청했습니다.

## 결정

shared `CacheHealthReport` model을 추가하고 sync JDBC/R2DBC Caffeine repository
contract에 `validateConsistency()`를 노출합니다. Kotlin `Channel`은 stable queue size를
제공하지 않으므로 atomic counter로 queue depth를 명시적으로 추적합니다.

health reporting은 lazy write-behind worker를 초기화하면 안 됩니다. 별도 started flag로
lazy startup을 보존하면서 `isFlushJobRunning`을 보고합니다.

## 결과

JDBC와 R2DBC Caffeine repository는 write mode, accepted write-behind depth, worker
liveness, 마지막 non-cancellation flush error를 보고합니다.

R2DBC cancellation-safe final flush test가 중요한 regression을 발견했습니다. queue-depth
cleanup은 flush가 성공적으로 return한 뒤에만 수행해야 합니다. `flushBatch` 주위
`finally`에서 cleanup하면 cancellation이 기존 NonCancellable final flush retry 전에
batch를 비울 수 있습니다.

## 검증

- `./gradlew :bluetape4k-exposed-cache:compileKotlin :bluetape4k-exposed-jdbc-caffeine:compileKotlin :bluetape4k-exposed-r2dbc-caffeine:compileKotlin :bluetape4k-exposed-jdbc-caffeine:compileTestKotlin :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin`
- `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepositoryExtraTest"`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest"`
- `git diff --check`

## 후속 지침

`SuspendedJdbcCaffeineRepository`에 health reporting을 추가하면
successful-flush-only depth cleanup rule을 복사합니다. `flushBatch` 주위 broad `finally`에
batch cleanup을 두지 않습니다.

사용자 지시로 Claude advisor/review와 external Codex CLI review는 건너뛰었으며, 이
session은 local implementation, review, verification을 수행했습니다.
