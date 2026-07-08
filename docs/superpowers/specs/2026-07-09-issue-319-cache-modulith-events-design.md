# Issue #319 Cache Spring Modulith Events Design

## Context

GitHub issue #319 targets milestone `1.12.0` and asks the Spring Modulith
integration to publish domain events from cache write paths without weakening
cache durability semantics.

Current source facts:

- `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`, and
  `R2dbcCacheRepository` expose cache operations but no post-persistence hook.
- `AbstractJdbcCaffeineRepository` owns the JDBC Caffeine `WRITE_THROUGH` and
  `WRITE_BEHIND` persistence points.
- `spring-boot/spring-modulith` already provides a JDBC-only
  `EventPublicationRepository` for Spring Modulith publication state.
- Spring Modulith documents `ApplicationEventPublisher.publishEvent(...)` as
  the normal event entrypoint, and `@ApplicationModuleListener` integrates
  listener execution with the event publication registry.

## Goal

Add an opt-in JDBC Caffeine integration that publishes Spring application
events only after the cache write has reached the durable database boundary.

The implementation must make the following timing explicit:

- `WRITE_THROUGH`: publish after the synchronous DB write succeeds.
- `WRITE_BEHIND`: publish after the retained async batch flush succeeds.
- Failed DB writes or failed retained flushes must publish nothing.
- Queue acceptance is not a publication boundary.

## Scope

In scope:

- Add a protected extension point to `AbstractJdbcCaffeineRepository` for
  post-persistence cache writes.
- Add a Spring Modulith opt-in repository base class in
  `spring-boot/spring-modulith` for JDBC Caffeine repositories.
- Support `put` and `putAll` through the existing JDBC Caffeine write paths.
- Publish one event per persisted entity, preserving the repository write order.
- Keep invalidation-only operations as cache-only and do not publish events.
- Document the timing contract in `spring-boot/spring-modulith/README.md` and
  `README.ko.md`.
- Add one README sequence diagram because the existing diagrams describe the
  publication repository lifecycle, not the cache write publication boundary.

Out of scope for this PR:

- Suspended JDBC Caffeine and R2DBC Caffeine event hooks.
- A durable outbox for the process-local write-behind queue.
- Auto-wrapping arbitrary existing repository beans.
- Publishing delete/invalidation events.

## API Design

### JDBC Caffeine Hook

`exposed/jdbc-caffeine` adds a named persisted-write value type:

```kotlin
data class CachePersistedWrite<ID : Any, E : Serializable>(
    val id: ID,
    val entity: E,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = ...
    }
}
```

`AbstractJdbcCaffeineRepository` gains protected open hooks:

```kotlin
protected open fun afterPersisted(id: ID, entity: E) {
}

protected open fun afterPersisted(writes: List<CachePersistedWrite<ID, E>>) {
}
```

The single-write hook avoids an extra collection allocation on the
`WRITE_THROUGH` hot path. The batch hook is for successful write-behind flushes;
its default implementation delegates to the single-write hook in iteration
order.

Call sites:

- after `writeToDb(id, entity)` returns in `WRITE_THROUGH`
- after `WRITE_BEHIND` `flushBatch(batch)` commits, queue depth is decremented,
  and the retained batch is cleared

The batch hook receives an immutable snapshot of named writes that were actually
persisted. Ordinary DB failures skip the hook. `CancellationException` from the
DB write/flush path remains re-thrown.

Hook and publisher failures are post-commit notification failures. They must not
turn a successful DB write or flush into a failed cache write, must not retain or
replay an already committed write-behind batch, and must not kill the
write-behind worker. The repository logs non-sensitive context and continues.

### Spring Modulith Opt-In Base

`spring-boot/spring-modulith` provides:

```kotlin
abstract class SpringModulithJdbcCaffeineRepository<ID : Any, E : Serializable>(
    config: LocalCacheConfig,
    private val eventPublisher: ApplicationEventPublisher,
) : AbstractJdbcCaffeineRepository<ID, E>(config) {

    protected abstract fun toDomainEvent(id: ID, entity: E): Any?
}
```

It overrides `afterPersisted(...)` and calls
`ApplicationEventPublisher.publishEvent(...)` for every non-null mapped event.
Returning `null` lets callers suppress publication for selected entities without
inventing a second predicate API.

`toDomainEvent(...)` must return a minimal application-owned event DTO. It must
not return the cached entity, `Pair<ID, E>`, credentials, tokens, raw secrets, or
full records containing sensitive fields. The bridge accepts already-constructed
event objects only; it must not accept serialized payloads, event class names, or
any caller-controlled reflective type information.

This keeps Spring dependencies isolated in the Spring Modulith module while the
JDBC Caffeine module remains Spring-neutral.

The Spring Modulith module declares:

```kotlin
api(project(":bluetape4k-exposed-jdbc-caffeine"))
```

The dependency is exported because the new public base class extends
`AbstractJdbcCaffeineRepository` and exposes `LocalCacheConfig` through its
constructor. This changes the Spring Modulith artifact dependency surface but
does not leak Spring dependencies back into `exposed/jdbc-caffeine`. The feature
does not add auto-configuration or bean auto-wrapping; applications opt in by
extending the base class.

The constructor intentionally has no `LocalCacheConfig` default. Callers must
choose `WRITE_THROUGH` or `WRITE_BEHIND` explicitly for event publication.
`READ_ONLY` is allowed only as the inherited cache mode behavior and publishes
nothing because no durable DB write occurs.

## Behavioral Contract

- `put(id, entity)` in `WRITE_THROUGH` publishes only after the DB transaction
  succeeds. Publisher or mapper exceptions are logged and do not roll back the
  already committed DB write or fail the cache write call.
- `put(id, entity)` in `WRITE_BEHIND` publishes only after the background
  flush commits the retained batch, decrements queue depth, and clears the
  retained batch. Publisher or mapper exceptions are logged and do not retain
  or replay the already committed batch.
- `putAll(entities, batchSize)` preserves the existing repository behavior of
  delegating to `put` for each entry; publication follows those per-entry
  persistence boundaries. Order follows the supplied `Map` iteration order; use
  an ordered map such as `linkedMapOf` when deterministic publication order is
  required.
- If the write-behind queue is full, no cache write and no publication occurs.
- If a write-behind flush fails, the batch is retained, queue depth remains, and
  no publication occurs until a later successful flush.
- If a retained write-behind batch later succeeds together with newly appended
  writes, the hook publishes the complete committed batch snapshot in the order
  the worker flushed it.
- Duplicate IDs are not coalesced. Each accepted write item can publish one
  mapped event after that write item reaches the DB boundary, even when multiple
  writes target the same ID.
- `close()` final flush uses the same successful-flush publication rule. If the
  final flush fails, no publication occurs and `lastFlushError` records the DB
  failure. If close times out, later publication is allowed only if the
  background worker actually commits before scope cancellation completes.
- `invalidate`, `invalidateAll`, and `clear` only mutate the local cache.
- `READ_ONLY` mode only updates the in-process cache and publishes no event.

Publication fanout is synchronous with the repository worker/caller after DB
persistence. It is outside the Exposed DB transaction. Slow event publication can
increase write-through call latency and write-behind worker cycle latency, but it
must not keep already persisted items in queue depth or retained-batch state.

## Publication Failure Semantics

Mapper and publisher exceptions are separate from database persistence failures.

- `WRITE_THROUGH`: DB write success remains success. Mapper or publisher failure
  is logged with repository context and event type when available. The exception
  is not propagated to the caller.
- `WRITE_BEHIND`: DB flush success remains success. Queue depth is decremented,
  the retained batch is cleared, and mapper or publisher failure is logged. The
  worker keeps running and does not replay the committed batch.
- `CancellationException` from the database write/flush path is still rethrown.
  Mapper or publisher implementations should not throw cancellation signals for
  normal event rejection; they should return `null` to suppress publication.

This means the cache/database write can succeed while a post-commit event is not
handed to Spring. Spring Modulith durable listener tracking begins only after
`ApplicationEventPublisher.publishEvent(...)` accepts the event.

## Crash Windows

The process-local write-behind queue is not a durable outbox.

- Before write-behind flush: queued writes and their events can be lost on
  process crash.
- After DB commit but before `publishEvent`: the DB row is durable, but the
  event can be lost because Spring has not received it yet.
- After `publishEvent` returns: Spring Modulith publication durability follows
  the listener/publication repository configuration.
- During shutdown final flush: a successful final flush can publish events; a
  failed final flush publishes nothing and records `lastFlushError`; a close
  timeout can leave queued writes/events unflushed.

Operators that require stronger write/event coupling should prefer
`WRITE_THROUGH` or an application-level durable outbox instead of relying on the
process-local write-behind queue.

## Observability

No new metric API is added in this PR. Operational signals are:

- existing `validateConsistency()` fields: mode, queue depth, worker running
  state, and last DB flush error
- existing queue-full exception on enqueue backpressure
- logs for retained DB flush retries, mapped-null skips, publication success or
  failure, and close timeout

Logs must not include serialized event payloads, credentials, tokens, or full
cached records.

## Documentation And Diagram Decision

README updates are required because #319 changes public integration behavior.

A new diagram is required. The current Spring Modulith diagrams show:

- runtime wiring for the JDBC `EventPublicationRepository`
- publication row lifecycle after an application event is already published

They do not show the distinction between cache enqueue, DB persistence, and
Spring event publication. The new sequence diagram must show both
`WRITE_THROUGH` and `WRITE_BEHIND` timing so users do not treat process-local
queue acceptance as durable publication.

The README and KDoc must include a concrete subclass, a small event DTO,
`WRITE_THROUGH` or `WRITE_BEHIND` configuration, and an
`@ApplicationModuleListener` example. They must state that only synchronous JDBC
Caffeine repositories are supported by this feature; suspended JDBC Caffeine and
R2DBC Caffeine repositories do not publish Spring Modulith events in this PR.
They must also state unsupported scope: `READ_ONLY` publication, delete or
invalidation events, auto-wrapping existing beans, and a durable write-behind
queue/outbox.

Diagram asset:

- `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.svg`
- `docs/images/readme-diagrams/spring-boot-exposed-spring-modulith-cache-write-sequence-01.png`

## Verification Requirements

- Unit/integration tests for the protected hook in JDBC Caffeine:
  - `WRITE_THROUGH` success publishes hook after DB write.
  - `WRITE_BEHIND` success publishes hook only after flush.
  - write-behind queue full failure publishes nothing.
  - duplicate IDs publish per accepted write item, not last-write-wins.
  - retained batch plus later appended writes publish in flushed order after the
    combined commit.
  - hook exceptions do not retain/replay already committed write-behind batches
    and do not roll back successful write-through persistence.
- Spring Modulith tests:
  - mapped events are published through `ApplicationEventPublisher`.
  - `null` mapped events are skipped.
  - `putAll` publishes all mapped events in supplied map iteration order.
  - publisher exceptions do not retain/replay already persisted write-behind
    batches and do not roll back successful write-through persistence.
  - example event DTOs contain only minimal identifiers and no full cached
    records or sensitive credential data.
  - the bridge performs no serialization, deserialization, or reflective event
    class loading.
  - a slow/blocking publisher fixture proves already persisted write-behind
    items leave queue depth before publication fanout completes.
  - `READ_ONLY` mode publishes nothing.
- Auto-configuration regression:
  - existing Spring Modulith auto-configuration still loads without creating
    cache repository beans.
- Regression tests for failed write-through and retained write-behind failure
  where practical with current test fixtures.
- Test synchronization must use concrete fixtures: latches in test repositories,
  `validateConsistency()` queue depth polling, and `close()` final flush behavior
  where appropriate. Do not rely on arbitrary sleeps as the primary assertion
  mechanism.
- Targeted Gradle verification:
  - `:bluetape4k-exposed-jdbc-caffeine:test`
  - `:bluetape4k-exposed-spring-modulith:test`
- Documentation verification:
  - SVG XML validation.
  - CairoSVG PNG render.
  - Sequence diagram style and connector audits.
  - full-size PNG inspection.
  - `git diff --check`.

## Risks

- `WRITE_BEHIND` flush is asynchronous; tests must wait on repository-provided
  lifecycle methods instead of timing sleeps where possible.
- Publishing is in-process after DB persistence, so Spring Modulith durability
  begins only once Spring receives the event. It does not make the write-behind
  queue itself durable across process death.
- Adding a protected hook to a public abstract class is source-compatible, but
  subclasses overriding similarly named members do not exist today and should be
  checked by compilation.

## Migration And Rollback

- No database schema change is introduced beyond existing Spring Modulith
  publication tables.
- Existing repositories are unaffected until they explicitly extend
  `SpringModulithJdbcCaffeineRepository`.
- Rollback options:
  - return `null` from `toDomainEvent(...)` to suppress publication
  - switch the application repository back to `AbstractJdbcCaffeineRepository`
  - drain write-behind queues before deploying or rolling back a mapped-event
    change
- Existing Spring Modulith publication rows remain governed by the configured
  Spring Modulith repository and completion mode; this feature does not migrate
  or delete them.
