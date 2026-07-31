# Issue #163 — R2DBC Caffeine `close()`가 최종 Flush를 기다림

**Date**: 2026-05-19
**Issue**: #163
**Module**: `exposed-r2dbc-caffeine`

## 배경

`AbstractR2dbcCaffeineRepository.close()`는 write-behind channel을 닫고
write-behind job이 끝나기를 기다리지 않은 채 repository scope를 cancel했습니다.
#161이 최종 flush를 cancellation-safe로 만들었지만 `close()`는 return 전에 그 최종
flush를 기다려야 했습니다.

## 결정

`close()`는 synchronous로 두고 write-behind job이 닫힌 channel을 관찰하여 최종 flush를
완료할 때까지 기다립니다. production lifecycle path에서 `runBlocking`을 피하고 bounded
completion wait 뒤에만 cache를 invalidate하고 scope를 cancel합니다.

## 결과

write-behind shutdown은 더 강한 lifecycle contract를 제공합니다. `close()`가 정상적으로
return하면 pending write-behind entry는 worker가 flush했거나 기존 flush error path가
처리했습니다. bounded timeout은 hung DB/driver가 shutdown을 무한히 block하지 않게
합니다. timeout에 도달하면 shutdown은 warning과 함께 진행하며 caller는 남은 pending
write-behind entry가 durable하다고 보장할 수 없음을 알아야 합니다.

## 검증

- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest*CancellationSafeFinalFlush*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --console=plain --no-daemon`
- `git diff --check`

targeted test는 final-flush wait behavior, write-behind `put` 전 close, write-behind job
시작 뒤 idempotent close, WRITE_THROUGH close가 write-behind job을 초기화하지 않음을
다룹니다.

## 향후 guard

coroutine-backed worker를 닫는 synchronous lifecycle API는 worker completion이 관찰될
때까지 scope를 cancel하지 않습니다. final flush를 block하고 `close()`가 일찍 return하지
않음을 증명하는 test를 추가합니다.
