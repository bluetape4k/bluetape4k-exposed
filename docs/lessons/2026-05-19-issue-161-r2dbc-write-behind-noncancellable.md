# R2DBC Caffeine Write-Behind 최종 Flush Cancellation

## 배경

Issue #161은 `AbstractR2dbcCaffeineRepository`가 write-behind job `finally` block에서
suspend `flushBatch()`를 호출하는데 job이 이미 cancelled일 수 있음을 발견했습니다.
이 경우 최종 in-memory batch가 cancellation 중 버려질 수 있습니다.

## 결정

write-behind loop가 non-empty batch로 끝나면 최종 `flushBatch(batch)`를
`withContext(NonCancellable)` 안에서 실행합니다. ordinary cancellation이 job을 통해
전파되도록 normal in-loop flush behavior는 바꾸지 않습니다.

## 결과

write-behind job은 이제 이미 수집한 최종 batch를 non-cancellable cleanup context에서
다시 시도합니다. 수정 범위는 의도적으로 #161에 한정하며 별도의 `close()` ordering
문제는 #163으로 남아 있습니다.

## 검증

- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:compileTestKotlin --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --tests "io.bluetape4k.exposed.r2dbc.caffeine.repository.WriteBehindCacheTest*CancellationSafeFinalFlush*" --console=plain --no-daemon`
- `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --rerun-tasks --console=plain --no-daemon`
- Claude Review: blocking finding과 #161 범위의 missing-test gap이 없었습니다.
- Codex CLI review: actionable defect가 없었습니다.

## 향후 지침

- cancelled coroutine의 suspend cleanup에는 cleanup operation 주위에만
  `withContext(NonCancellable)`를 사용합니다.
- #163은 분리합니다. queue를 닫고 scope를 cancel하는 순서는 shutdown이 natural
  write-behind completion을 기다리도록 lifecycle-ordering fix가 여전히 필요합니다.
