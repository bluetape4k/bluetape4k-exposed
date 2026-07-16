# Issue 326: Write-through cache compensation is not a transaction

## Context

The Ktor example needed to combine a Spring-neutral aggregate, an existing
R2DBC Caffeine repository, PostgreSQL persistence, and explicit event handoff.
The repository's `WRITE_THROUGH` path changes Caffeine before PostgreSQL, while
the aggregate owns an in-memory event buffer until another component accepts
the event.

## Decision

- Keep orchestration in `OrderCommandService`, not in Ktor routes or a new
  repository decorator.
- Snapshot pending events, persist the aggregate, publish the snapshot, and
  clear the aggregate buffer only after successful handoff.
- Invalidate the order cache when persistence throws or cancellation crosses
  the persistence boundary. This repairs later reads but does not make the
  cache and PostgreSQL update atomic.
- Use client-generated canonical UUIDs so an `UPDATE` followed by `INSERT` can
  preserve the requested order identity without a database-generated-ID race.
- Keep the publisher application-owned and non-durable. A production recovery
  guarantee requires an outbox or another durable handoff boundary.

## Outcome

The example now traces one bodyless Ktor command through validation, command
service orchestration, aggregate transition, Caffeine-first write-through,
PostgreSQL persistence, event handoff, and sequential idempotency. A failed
write invalidates the cache, a failed publisher leaves the event buffered, and
stable HTTP errors distinguish persistence from event-handoff failure without
returning exception text.

The application also owns the full PostgreSQL R2DBC lifecycle. Construction
unwinds in reverse order, shutdown closes the repository before unregistering
the Exposed database and disposing the pool, and the captured process-wide
default database is restored only when no external owner has replaced it.

## Proof

- Docker-free suite: 32 tests, zero failures, and zero Testcontainers output.
- PostgreSQL suite: 4 tests covering the scenario, readiness/outage, and a
  second lifecycle with default restoration; zero failures with Ryuk disabled
  for the local Colima socket.
- Documented walkthrough: JDBC count `2`, R2DBC count `1 -> 2`, first command
  `eventPublished=true`, repeated command `eventPublished=false`, and matching
  GET state.
- Compose: loopback PostgreSQL became healthy; a separate disposable project
  proved `down -v --remove-orphans` removes only its named volume.
- Diagram XML, render parity, connector, geometry, endpoint, mixed-corner, and
  sequence-style audits passed. The PNGs are 3360 x 2100 and 3360 x 4400.
- English/Korean Bash blocks compare byte-for-byte and every local link exists.

## Misses

- The first fresh Testcontainers run failed before tests because Ryuk tried to
  mount the Colima socket inside its own container. The repository's documented
  `TESTCONTAINERS_RYUK_DISABLED=true` setting produced the successful proof.
- The example project has no module-local `detekt` task. Root `detekt` succeeds
  but reports `NO-SOURCE`, so compilation and the focused tests remain the
  effective Kotlin static proof for this example.
- The code-review graph had no indexed nodes for the new example. Direct
  import/call-site scans, scope scans, fresh compilation, and runtime tests were
  used instead.

## Future Guard

Do not replace snapshot/publish/clear with `drainDomainEvents()`: a failed
non-durable publisher must leave events available to the current aggregate
owner. Do not describe cache invalidation as rollback; readers may observe the
short interval after Caffeine changes and before PostgreSQL succeeds. If the
example grows durable delivery, design an outbox explicitly instead of adding
retry or recovery semantics to this request-local publisher.
