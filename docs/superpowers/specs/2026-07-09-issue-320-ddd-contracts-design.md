# Issue 320 DDD Contracts Design

## Problem

`bluetape4k-exposed` already owns Exposed repository execution, transaction
boundaries, cache behavior, and Spring Boot/Ktor adapters. Follow-up issues
`#319` and `#316` need a small domain contract that can carry aggregate domain
events without depending on Spring, Spring Modulith, JaVers, or Exposed DAO
runtime types.

The current repository has auditable table/entity support in `exposed/core` and
`exposed/dao`, but no Spring-neutral `AggregateRoot` or `DomainEvent` API.
`bluetape4k-javers` has a broader `javers-ddd` precedent, but that design
includes JaVers commit-property mapping, publisher adapters, and repository
helpers. Those parts belong outside this issue.

## Current Evidence

- GitHub issue `#320` is open in milestone `1.12.0` with labels
  `enhancement`, `feature`, and `test`.
- Baseline command passed before edits:
  `repo-test-summary -- ./gradlew :bluetape4k-exposed-core:test --no-configuration-cache --no-build-cache --no-parallel --console=plain`
  reported `BUILD SUCCESSFUL` and `277 tests`.
- The smallest current module boundary is `exposed/core`, package root
  `io.bluetape4k.exposed.core`.
- Existing public core contracts such as `Auditable` are plain Kotlin
  interfaces and keep framework-specific behavior outside the contract.
- Root README already documents that `bluetape4k-exposed` owns Exposed
  repositories/cache/transaction boundaries while JaVers audit/history belongs
  to `bluetape4k-javers`.

## Constraints

- New APIs must be usable without Spring, Spring Modulith, JaVers, Exposed DAO,
  or Exposed JDBC/R2DBC dependencies.
- Public KDoc must be English.
- README changes must update both `README.md` and `README.ko.md`.
- Tests must use `bluetape4k-assertions`; no JUnit/AssertJ/Kluent assertion
  APIs in new tests.
- No new module, new dependency, publisher adapter, durable outbox, or Exposed
  DAO aggregate base is in scope.
- Existing repositories are unaffected until a caller explicitly implements the
  new contracts. No events are published, persisted, observed, or replayed
  automatically by this issue.

## Options

### Option A: Minimal core DDD package

Add `io.bluetape4k.exposed.core.ddd` in `exposed/core` with:

- `DomainEvent<ID : Any>` interface.
- `AggregateRoot<ID : Any>` interface.
- `AbstractAggregateRoot<ID : Any>` event recording/draining base.

Repository and publisher adapters remain follow-up work.

Pros:
- Small dependency surface.
- Directly satisfies `#320`.
- Easy for Spring Modulith and Ktor follow-ups to consume.

Cons:
- Does not provide repository-side collection helpers yet.

### Option B: Copy the JaVers DDD module shape

Port `AggregateRepository`, `DomainEventPublisher`, and adapter concepts from
`bluetape4k-javers`.

Pros:
- More complete sample surface.

Cons:
- Pulls this issue toward JaVers-specific workflow and publisher concerns.
- Risks overlapping `bluetape4k-javers` ownership.
- Too broad for the follow-up Spring Modulith contract.

### Option C: Put contracts in Spring Modulith module

Add the contracts under `spring-boot/spring-modulith`.

Pros:
- Close to the first likely framework adapter.

Cons:
- Violates the Spring-neutral requirement.
- Ktor and plain repository consumers would depend on Spring module naming and
  packaging.

## Decision

Use Option A.

The API will be placed in `exposed/core/src/main/kotlin/io/bluetape4k/exposed/core/ddd`.
It will model only in-memory event recording and draining. It will not publish
events, convert them to JaVers properties, store them durably, or hook into
Exposed DAO lifecycle callbacks.

## API Shape

`DomainEvent<ID : Any>`:

- `val aggregateId: ID`
- `val occurredAt: Instant`

`AggregateRoot<ID : Any>`:

- `val id: ID`
- `fun domainEvents(): List<DomainEvent<ID>>`
- `fun clearDomainEvents()`
- `fun drainDomainEvents(): List<DomainEvent<ID>>`

`AbstractAggregateRoot<ID : Any>`:

- Implements `AggregateRoot<ID>` as
  `abstract class AbstractAggregateRoot<ID : Any> : AggregateRoot<ID>`, with
  `abstract override val id: ID` supplied by subclasses.
- Keeps an internal mutable event buffer, initialized lazily or represented with
  an equivalent zero-event storage path so aggregates with no events do not pay
  per-instance list allocation.
- Provides protected `recordDomainEvent(event: DomainEvent<ID>)`.
- Validates that `event.aggregateId == id` before recording; mismatches are
  caller errors and must fail with `IllegalArgumentException`.
- Returns defensive immutable snapshots from `domainEvents()` and
  `drainDomainEvents()`.
- Returns `emptyList()` without copying when no events are recorded, and copies
  only non-empty buffers.
- Preserves event recording order in both snapshots and drains.
- Clears events only when `clearDomainEvents()` or `drainDomainEvents()` is
  called.

## Repository Guidance

Repository implementations must not treat `drainDomainEvents()` as the publish
boundary. It is only a local buffer-clear operation. To avoid event loss,
integrations should snapshot events with `domainEvents()`, persist the
aggregate, wait for the transaction commit boundary, hand the snapshot to a
publisher/durable outbox adapter, and clear or drain the aggregate buffer only
after that adapter has accepted responsibility for the events.

The normal repository sequence is:

1. mutate the aggregate and record domain events,
2. capture a defensive snapshot with `domainEvents()`,
3. persist the aggregate successfully,
4. wait for the transaction commit or equivalent durability boundary,
5. pass the snapshot to a framework-specific publisher or outbox adapter,
6. clear or drain the aggregate buffer only after that adapter accepts the
   events.

`clearDomainEvents()` exists for discard/rollback-style caller-owned cleanup,
not for successful publication before handoff. `drainDomainEvents()` is safe
only when the caller has already moved the returned events into a durable or
otherwise retryable handoff path.

For write-behind cache paths, accepting a value into an in-memory queue is not a
durability boundary. A database flush is also not sufficient if the transaction
can still roll back. Follow-up issue `#319` must publish only after an
after-commit boundary or an equivalent durable write-behind handoff.

If persistence fails after aggregate mutation, the contract does not publish or
discard events automatically. The command handler should either retry with the
same aggregate instance intentionally, discard the aggregate instance, or call
`clearDomainEvents()` when abandoning the command. Reusing a mutated aggregate
across command attempts without making that choice is a caller bug.

Exposed DAO `EntityCache` is transaction-scoped. It must not be treated as:

- an application-level cache,
- a durable outbox,
- a domain-event registry,
- or a Spring Modulith event-publication store.

## Tests

Add focused tests under `exposed/core/src/test/kotlin/io/bluetape4k/exposed/core/ddd`:

- A plain aggregate records a domain event.
- `domainEvents()` returns a snapshot and does not clear events.
- `drainDomainEvents()` returns events and clears them.
- A repeated drain returns an empty list.
- Multiple events drain in recording order.
- Recording an event whose `aggregateId` differs from the aggregate `id` fails
  with `IllegalArgumentException`.
- ID typing is covered with compile-time fixtures: define separate
  `@JvmInline value class OrderId` and `@JvmInline value class CustomerId`
  values, then use matching aggregate/event pairs so mismatched IDs fail at
  compile time rather than relying on runtime erasure checks.
- Any README/test `data class` fixtures must follow bluetape4k data-class
  convention by implementing `java.io.Serializable` and defining
  `serialVersionUID`.

Concurrency stress tests are not required for this issue because the contract is
not thread-safe by design. Aggregate instances are expected to be mutated inside
one command/transaction boundary, and the KDoc must say so. KDoc must also state
that `recordDomainEvent`, `domainEvents`, `clearDomainEvents`, and
`drainDomainEvents` must not be called concurrently on the same aggregate
instance.

## Documentation

Update root README locale pair with a short DDD contracts section:

- mention the new Spring-neutral contracts,
- show a minimal aggregate/event example,
- show the repository-side snapshot, commit, handoff, then clear/drain sequence,
- state that Exposed DAO transaction cache is not a durable event boundary,
- state that this issue does not provide a durable outbox, publisher adapter,
  or Exposed DAO lifecycle hook,
- state that event payloads should avoid secrets, credentials, tokens, and
  unnecessary PII; examples should prefer identifiers over full object
  snapshots,
- state that existing repositories are unaffected until they explicitly adopt
  the contract,
- keep JaVers and Spring Modulith ownership boundaries clear.

This contract-only change has no runtime metrics or logging surface.
Observability belongs to follow-up repository/publisher adapters, where publish
success/failure, dropped events, retry/outbox state, and after-commit behavior
must be visible.

## Release Compatibility

Before `1.12.0` is released, this API can be reverted if review or
implementation evidence rejects the design. After release, removing or renaming
the public contracts is a breaking change; corrections should be additive or
deprecation-based unless a new major compatibility decision is made.

## Risks

1. **Over-broad API:** Adding publishers or repository adapters here would
   duplicate follow-up issues. Mitigation: keep this issue to contracts only.
2. **False durability semantics:** Consumers may treat `drainDomainEvents()` as
   persistence. Mitigation: KDoc and README must state that repositories should
   clear or drain only after a commit boundary and durable/retryable handoff
   acceptance, and that no outbox is provided.
3. **Framework leakage:** Spring Modulith or JaVers terms could enter core API.
   Mitigation: core types use only Kotlin/JDK types.
4. **Thread-safety ambiguity:** Event buffers are mutable. Mitigation: document
   command/transaction scoped usage and avoid claiming thread-safety.

## Acceptance Criteria

- `AggregateRoot`, `DomainEvent`, and `AbstractAggregateRoot` exist in
  `io.bluetape4k.exposed.core.ddd`.
- The contracts compile without Spring, Spring Modulith, JaVers, or Exposed DAO
  dependencies.
- Tests prove record, snapshot, drain, repeated drain, ordered drain, aggregate
  ID mismatch rejection, and typed ID behavior.
- README locale pair documents the API, unsupported capabilities, repository
  snapshot/handoff sequence, safe payload guidance, optional adoption, and
  durability boundary.
- Public KDoc exists for each new interface/class and documents framework
  neutrality, snapshot semantics, drain/clear behavior, non-thread-safety,
  aggregate ID validation, safe payload guidance, and the fact that no
  publisher/outbox is provided.
- `:bluetape4k-exposed-core:test` passes.
