# Write-Behind Queue Overflow: Silent Data Loss → IllegalStateException

**Date**: 2026-05-16  
**Issue**: #79  
**Module**: `exposed-jdbc-caffeine`  
**File**: `AbstractJdbcCaffeineRepository`

## Root Cause

`put()` in WRITE_BEHIND mode used `trySend()` on the internal Channel. When the queue
was full, `trySend()` returned a failure result, which was previously handled by a
`log.warn` — silently discarding the entity without any DB write. The caller had no
indication that data was lost.

Secondary issue found during review: `cache.put(key, entity)` ran **before** `trySend()`,
so on queue overflow the entity was visible via `get(id)` from the cache despite never
being queued for the DB — a phantom entry creating cache-DB inconsistency.

## Decisions

1. **Throw `IllegalStateException` on overflow** (not a warn log): queue overflow is
   unambiguously a data loss scenario. Callers must be informed immediately so they can
   apply back-pressure or increase `writeBehindQueueCapacity`.

2. **Move `cache.put()` after successful `trySend()`**: prevents the phantom-entry
   pattern. If the enqueue fails, the cache is not updated, keeping cache and DB consistent.

3. **Independent `runCatching` in `close()`**: each shutdown step (queue.close,
   job.join, cache.invalidateAll, scope.cancel) is wrapped independently so a failure
   in one step does not skip resource cleanup for the remaining steps.

4. **R2DBC counterpart is NOT affected**: `AbstractR2dbcCaffeineRepository` uses the
   suspending `send()` which blocks the coroutine when the queue is full rather than
   failing immediately — no data loss path exists there.

## Verification

- `JdbcCaffeineRepositoryExtraTest.WriteBehindOverflowTest` — parameterized across H2,
  PostgreSQL, MySQL: uses `capacity=500, batchSize=500` so the CPU-bound put loop fills
  the queue before the IO-bound worker can drain a single batch; reliably triggers overflow.
- 276 tests passing, 0 failures across all dialects after the fix.

## Future Guidance

- **WRITE_BEHIND channel trySend pattern**: always throw on trySend failure; never log
  and continue. Silent data loss is harder to diagnose than an exception.
- **Cache write ordering**: for any write mode where the DB path can fail, always update
  the cache AFTER the DB/queue operation succeeds to avoid cache-DB inconsistency.
- **close() pattern**: every resource (Channel, Job, Cache, Scope) must have its own
  independent `runCatching` so that a failure in one step does not skip the rest.
