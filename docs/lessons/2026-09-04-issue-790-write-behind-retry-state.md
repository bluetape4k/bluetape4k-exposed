# Issue #790 Write-behind retry 상태의 entry별 소유권 lesson

## Context

`ExposedR2dbcLettuceSuspendedLoadedMap`의 write-behind flush는 한 배치에
서로 다른 retry count를 가진 항목이 들어올 수 있다. 기존 구현은 배치 첫
항목의 count를 전체 항목의 다음 시도로 재사용해, 새 항목을 조기에
dead-letter로 보내거나 이미 누적된 retry count를 되돌릴 수 있었다.

## Decision or Finding

- retry count는 batch가 아닌 각 queue entry가 소유한다.
- flush 실패 시 각 entry의 `retryCount + 1`을 개별 계산한다.
- 개별 next retry가 `MAX_DEAD_LETTER_RETRY`에 도달하면 해당 entry만
  dead-letter로 보내고, 나머지 entry는 채널에 개별 재적재한다.
- `CancellationException` 재전파와 queue 포화 시 dead-letter fallback은
  기존 경계를 유지한다.

## Outcome

혼합 retry batch에서 retry 2 항목은 다음 실패 시 dead-letter되고 fresh
항목은 retry 1로 재적재된다. 따라서 한 항목의 retry 상태가 같은 batch의
다른 항목의 보존 여부를 결정하지 않는다.

## Verification

- RED: 기존 구현에서 혼합 실패 후 fresh entry의 다음 writer 호출이 없어
  `freshEntryRetried.await()`가 10초 timeout으로 실패했다.
- GREEN: 새 회귀 테스트에서 세 번째 혼합 batch는 `retried`와 `fresh`를
  함께 시도하고, 다음 batch에는 `fresh`만 재시도되는 것을 확인했다.
- `:bluetape4k-exposed-r2dbc-lettuce:test --tests
  "io.bluetape4k.exposed.r2dbc.lettuce.map.ExposedR2dbcLettuceSuspendedLoadedMapTest.write-behind mixed retry batch keeps each entry retry count independent"
  --no-build-cache`: 1 passing.
- 동일 테스트 클래스: 6 passing.
- 모듈 전체 테스트: 149 passing, 4 pending, failures 0.
- `:bluetape4k-exposed-r2dbc-lettuce:detekt --no-build-cache`: BUILD SUCCESSFUL.
- `git diff --check`: PASS.

## Future Guidance

write-behind retry 또는 dead-letter 정책을 수정할 때는 서로 다른 retry
count의 entry를 한 batch에 섞은 회귀를 먼저 고정한다. 재시도 상태를
batch-level 변수로 승격하거나 첫 원소에서 추론하지 말고, retry·queue
포화·dead-letter·cancellation 결과를 entry별로 검토한다. 동기 JDBC와
Projects의 대응 구현도 같은 invariant를 유지하는지 교차 저장소
conformance test에서 확인한다.
