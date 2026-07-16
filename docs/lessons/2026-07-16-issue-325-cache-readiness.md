# Issue 325: Cache readiness needs lifecycle, deadline, and identity contracts

## Context

A Boolean such as `isFlushJobRunning` cannot distinguish a fresh idle worker,
an active worker, graceful draining, terminal failure, and completed shutdown.
Ktor readiness also needed to combine JDBC, R2DBC, repository, snapshot, and
custom cache observations without multiplying the endpoint timeout or metric
cardinality.

## Decision

- Model background work with `CacheWorkerState`; do not retain an ambiguous
  Boolean compatibility alias.
- Run cache contributors sequentially under one shared monotonic cache-phase
  deadline. Suppliers remain caller-owned, side-effect-free O(1), non-blocking,
  and cancellation-cooperative.
- Register a fixed set of four gauges and four finite-outcome timers per
  contributor during route installation. Component and kind are bounded
  operational identities, never data-bearing runtime values.
- Preserve the existing Ktor config constructor and default bridges. Add cache
  support with overloads so compiled database-only callers remain compatible.
- Keep authentication, concurrency control, repositories, registries, and
  shutdown in the application lifecycle.

## Outcome

Ktor can now expose one redacted readiness decision for databases and up to 16
cache contributors, including cache-only applications. Spring Actuator retains
its management-specific `OUT_OF_SERVICE` distinction, while Ktor intentionally
maps draining and stopped repositories to traffic-readiness `DOWN`. Timeout,
cancellation, and error metrics are mutually exclusive, and registry identity
collisions fail installation before a second route can claim the same meters.
Worker completion callbacks publish a non-null terminal cause immediately,
before waiting for accepted cache publications to settle, so cancellation
before the coroutine body starts cannot leave readiness stuck at `RUNNING`.

## Verification Expectations

- Pin old and new JVM descriptors from compiled output.
- Compile canonical README examples and compare both locale fences with them.
- Prove shared-budget ordering, parent cancellation, supplier cancellation,
  generation safety, fixed meter count, collision rollback, and redaction.
- Run the complete Ktor module suite and keep `docs/manual/**` unchanged until a
  release-specific manual update is planned.

## Guidance for Future Changes

Do not add a per-contributor timeout, dynamic metric tag, hidden dispatcher,
background scope, or compatibility Boolean. If a new readiness source needs
backend I/O, keep that I/O in a caller-owned monitor and contribute only its
bounded in-memory status. Add public parameters with ABI-preserving overloads
and extend the compiled descriptor fixture before implementation.
