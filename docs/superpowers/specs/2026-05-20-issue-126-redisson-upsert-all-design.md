# Issue 126 Redisson `upsertAll` Design

## Context

Milestone 1.8.1 issue #126 asks for a bulk `upsertAll(Map<ID, E>)` API on
Redisson-backed JDBC and R2DBC repositories so cache warming can use one explicit
bulk operation instead of repeated single-entry writes.

Current Redisson repository interfaces already expose `putAll(entities, batchSize)`.
The gap is API intent: callers cannot distinguish ordinary cache write naming
from a deliberate bulk upsert/warm path.

## Decision

Add `upsertAll` as a Redisson-specific public API:

- JDBC: `fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`
- Suspended JDBC parity: `suspend fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`
- R2DBC: `suspend fun upsertAll(entities: Map<ID, E>, batchSize: Int = 100)`

`putAll` delegates to `upsertAll` to keep behavior centralized.

## Redisson API Evidence

Local Redisson 4.4.0 jar inspection showed `RMap.putAll(Map, Int)` and
`RMapAsync.putAllAsync(Map, Int)` are available. `fastPutAllAsync` is not
available in this version, so the implementation uses Redisson's existing
batched map write path instead of inventing an `RBatch` wrapper.

## Behavior

- Empty maps are no-ops.
- `batchSize <= 0` throws `IllegalArgumentException` through
  `requirePositiveNumber("batchSize")`.
- Write-through and write-behind persistence remains delegated to the configured
  Redisson map writer.
- `findAll` cache population routes through `upsertAll(..., DEFAULT_BATCH_SIZE)`
  so warm paths share the same API.

## Out of Scope

- No Spring Boot or Actuator integration.
- No new Redisson dependency or version change.
- No change to writer semantics for auto-increment IDs.

