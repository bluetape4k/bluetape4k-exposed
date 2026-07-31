# Write-Behind Queue Overflow: 무음 데이터 손실 → IllegalStateException

**Date**: 2026-05-16
**Issue**: #79
**Module**: `exposed-jdbc-caffeine`
**File**: `AbstractJdbcCaffeineRepository`

## 근본 원인

WRITE_BEHIND mode의 `put()`은 내부 Channel에 `trySend()`를 사용했습니다. queue가
가득 차면 `trySend()`는 실패 결과를 반환하지만, 이전에는 `log.warn`으로만
처리되어 DB write 없이 entity가 조용히 버려졌습니다. 호출자는 데이터가 손실된
사실을 알 수 없었습니다.

검토 중 두 번째 문제도 발견했습니다. `cache.put(key, entity)`가 `trySend()`
**이전**에 실행되었으므로 queue overflow가 발생하면 DB에는 enqueue되지 않은
entity를 cache의 `get(id)`로 볼 수 있었습니다. 이는 cache-DB 불일치를 만드는
phantom entry입니다.

## 결정 사항

1. **overflow 시 `IllegalStateException`을 던진다** (`warn` log가 아님): queue
   overflow는 명백한 데이터 손실 상황입니다. 호출자가 즉시 알아야 back-pressure를
   적용하거나 `writeBehindQueueCapacity`를 늘릴 수 있습니다.

2. **성공한 `trySend()` 뒤에 `cache.put()`을 둔다**: phantom-entry pattern을
   방지합니다. enqueue가 실패하면 cache를 갱신하지 않아 cache와 DB가 일관성을
   유지합니다.

3. **`close()`에서 독립적인 `runCatching`을 사용한다**: 각 shutdown 단계
   (`queue.close`, `job.join`, `cache.invalidateAll`, `scope.cancel`)를 독립적으로
   감싸므로 한 단계가 실패해도 나머지 resource cleanup을 건너뛰지 않습니다.

4. **R2DBC counterpart는 영향이 없다**: `AbstractR2dbcCaffeineRepository`는
   queue가 가득 차면 즉시 실패하는 대신 coroutine을 block하는 suspending `send()`를
   사용하므로 데이터 손실 경로가 없습니다.

## 검증

- `JdbcCaffeineRepositoryExtraTest.WriteBehindOverflowTest` — H2, PostgreSQL,
  MySQL을 parameterized로 검증합니다. `capacity=500, batchSize=500`을 사용해
  CPU-bound `put` loop가 IO-bound worker가 한 batch를 drain하기 전에 queue를
  채우므로 overflow를 안정적으로 유발합니다.
- 수정 후 모든 dialect에서 276 tests가 통과했고 failure는 0건입니다.

## 향후 지침

- **WRITE_BEHIND channel `trySend` pattern**: `trySend` 실패 시 항상 예외를
  던지고 log만 남긴 채 계속하지 않습니다. 무음 데이터 손실은 예외보다 진단하기
  어렵습니다.
- **Cache write 순서**: DB path가 실패할 수 있는 모든 write mode에서는 cache-DB
  불일치를 막기 위해 DB/queue operation이 성공한 **뒤에** cache를 갱신합니다.
- **`close()` pattern**: 하나의 resource 실패가 나머지를 건너뛰지 않도록 모든
  resource (Channel, Job, Cache, Scope)에 독립적인 `runCatching`을 적용합니다.
