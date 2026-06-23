# Issue 280 Caffeine Write-Behind Durability Lessons

Date: 2026-06-23
Issue: #280

## Lesson

Write-behind queues must treat "accepted into memory" and "durably flushed" as separate states. Clearing a batch or
publishing a cache value before the queue/flush boundary succeeds turns transient failures into silent data loss.

## Guidance

- Return an explicit success/failure signal from write-behind flush helpers; do not make callers infer success from a
  swallowed exception.
- Decrement queue depth and clear a batch only after the database transaction commits successfully.
- For suspended enqueue paths, call `send` before publishing to cache so cancellation, closed channels, and full queue
  backpressure cannot leave dirty cache state.
- Regression tests should cover both permanent failures that stay visible and transient failures that retry the same
  retained batch.
- Full queue tests should assert that pending/cancelled sends do not publish values to cache before queue acceptance.

## Follow-up

If write-behind durability becomes a stronger product requirement, move retry state to a durable outbox rather than
depending on process-local queues.
