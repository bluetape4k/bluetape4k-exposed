# Issue #323 Transaction-Aware Domain Event Publisher Design

## Context

Issue #323 asks `bluetape4k-exposed` to bridge the Spring-neutral DDD
contracts from #320 into Spring Boot repository workflows without making
`exposed/core` depend on Spring or Spring Modulith.

Current repository evidence:

- `AggregateRoot`, `DomainEvent`, and `AbstractAggregateRoot` already live in
  `exposed/core` and deliberately provide only an in-memory event buffer.
- `SpringModulithJdbcCaffeineRepository` from #319 publishes events for the
  synchronous JDBC Caffeine `WRITE_THROUGH` and `WRITE_BEHIND` persistence
  boundaries. It is cache-specific and does not publish events recorded by a
  general `AggregateRoot`.
- `examples/ddd-spring-modulith-demo` currently performs manual
  `ApplicationEventPublisher` iteration inside `OrderApplicationService` and
  clears the aggregate after `TransactionTemplate.execute` returns.
- `spring-boot/jdbc` already owns Spring transaction integration and provides
  the `springTransactionManager` used by Exposed JDBC repositories.
- The baseline command passed before edits:
  `./gradlew :bluetape4k-exposed-spring-boot-jdbc:test :bluetape4k-exposed-spring-modulith:test :examples-ddd-spring-modulith-demo:test --no-configuration-cache --no-daemon --console=plain`.

## Goal

Provide an explicit, transaction-aware Spring Boot service that accepts a
Spring-neutral `AggregateRoot` after its repository write, hands its recorded
events to Spring while the surrounding transaction is active, and clears the
aggregate buffer only after that transaction commits.

The integration must work with plain Spring Boot. When Spring Modulith is on
the application classpath, the same Spring application events can enter its
listener publication flow without a compile-time Spring Modulith dependency in
`spring-boot/jdbc`.

## Non-Goals

- No R2DBC or coroutine transaction synchronization in this issue.
- No durable outbox, retry queue, event store, or exactly-once guarantee.
- No AOP interception or automatic wrapping of arbitrary repository beans.
- No new repository base class or requirement that aggregates use Exposed DAO
  `Entity` types.
- No changes to the Spring-neutral DDD contracts from #320 unless review proves
  that the adapter cannot be implemented safely without an additive contract.
- No replacement for the cache-specific event publication from #319.

## Design Alternatives

### Option A: Explicit transaction-aware publisher service

Selected.

Applications inject one service and call it after a successful repository save
inside the same Spring transaction.

Pros:

- Works with custom Exposed repositories and Spring Data Exposed repositories.
- Keeps transaction timing visible at the application boundary.
- Does not force inheritance or proxy repository internals.
- Keeps plain Spring Boot support independent from Spring Modulith.

Cons:

- The application service must make one explicit registration call.
- It cannot prove that a caller actually persisted the aggregate before
  registration; documentation and examples must show the required sequence.

### Option B: Repository base class or decorator

Rejected.

Pros:

- Can combine save and event registration in one method for one repository
  shape.

Cons:

- The repository has no common aggregate `save` contract across custom JDBC,
  Spring Data DAO, and cache-backed implementations.
- Inheritance would expose Spring concerns in application repository design and
  still would not cover every persistence path.

### Option C: Repository AOP interception

Rejected.

Pros:

- Requires fewer explicit application calls.

Cons:

- Method-name matching cannot reliably identify persistence completion.
- Proxy ordering relative to Exposed and Spring transactions would be hard to
  reason about and test.
- Hidden publication makes rollback and duplicate behavior less obvious.

## Module And API Placement

Add the public API to:

```text
spring-boot/jdbc/src/main/kotlin/
  io/bluetape4k/spring/data/exposed/jdbc/ddd/
```

Proposed public type:

```kotlin
class ExposedAggregateEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun <ID : Any> publishAfterSave(aggregate: AggregateRoot<ID>)
}
```

Its English KDoc must cover the in-transaction handoff timing, caller obligation
to persist and hand off in the same transaction, default `AFTER_COMMIT` listener
timing, committed buffer cleanup, empty-buffer no-op, event-bearing transaction
preconditions, poison behavior, unsupported NESTED/savepoint use, unsupported
same-instance overlapping `REQUIRES_NEW`, and `@throws IllegalStateException`.
It also distinguishes immediate validation/publication exceptions from a
poisoned `beforeCommit` failure, warns that synchronous listeners run before
commit, and states the immutable/stable event-reference requirement. One
event-bearing call per aggregate instance and transaction is supported.

Add a dedicated auto-configuration class under
`io.bluetape4k.spring.data.exposed.jdbc.config` and register it directly in
`AutoConfiguration.imports`. It creates the publisher only when the required
Spring transaction and DDD contract classes are present and no user bean of the
same type exists.

The implementation uses Spring Framework transaction synchronization APIs
already present in the module dependency graph. It must not add a Spring
Modulith dependency to `spring-boot/jdbc`.

## Verified Spring Transaction Constraint

Local dependency source establishes the required timing:

- Spring Modulith 2.0.6 `@ApplicationModuleListener` is an asynchronous
  `@TransactionalEventListener` with a `REQUIRES_NEW` listener transaction.
- Spring Framework 7.0.8
  `TransactionalApplicationListenerSynchronization.register(...)` registers an
  AFTER_COMMIT callback only when transaction synchronization and an actual
  transaction are active at `publishEvent(...)` time.

Publishing a new event from another `afterCommit` callback is therefore too
late for the normal transactional listener registration path. The bridge must
publish the Spring handoff while the command transaction is active. Default
`AFTER_COMMIT` transactional listeners and Spring Modulith listeners then
execute after the command commits.

## Transaction Lifecycle

The required application flow is:

1. Mutate an aggregate and record domain events.
2. Start or join a Spring-managed JDBC transaction.
3. Persist the aggregate with an Exposed repository.
4. Call `publishAfterSave(aggregate)` before the transaction completes.
5. Hand the captured events to Spring `ApplicationEventPublisher` in recording
   order while the transaction is active.
6. Default `AFTER_COMMIT` Spring transactional listeners register their work;
   Spring Modulith can record listener publications in the command transaction.
7. Commit the database transaction.
8. Clear the aggregate buffer from `afterCompletion(STATUS_COMMITTED)`.

If the aggregate has no events, registration is a no-op even outside a
transaction. Before handing off an event-bearing snapshot, the method requires
active transaction synchronization and an actual active transaction. Failure
of either check throws `IllegalStateException`; this rejects synchronization-only
contexts.

Spring's thread-local API cannot prove which transaction-manager bean owns the
current transaction or whether a preceding repository call persisted this
aggregate. The caller must ensure that the Exposed repository write and handoff
participate in the same command transaction. The publisher deliberately makes
no transaction-manager or `DataSource` identity claim.

The empty-buffer no-op applies only before registration. If the aggregate was
already registered in the current transaction and caller code manually cleared
or drained it, a second call still hits the identity reservation first, poisons
the transaction, and fails rather than masking the lifecycle violation.

Rollback prevents default `AFTER_COMMIT` transactional-listener delivery and
committed Spring Modulith publication, and it does not clear the aggregate buffer. Ordinary
synchronous listeners may already have observed the in-transaction handoff;
the caller owns retry or discard of that aggregate instance.

## Snapshot And Mutation Contract

The single event-bearing call retains the immutable list snapshot returned by
`domainEvents()` without another copy and publishes it while the transaction is active. Applications that
mutate and save an aggregate more than once must call `publishAfterSave` only
after the final save. A second event-bearing call for the same aggregate object
in one transaction poisons the registry and throws `IllegalStateException`.

The synchronization verifies before commit that the current snapshot has the
same size and the same event object references in the same order. A mismatch
fails the commit, prevents default `AFTER_COMMIT` listener delivery and
committed Spring Modulith publication, and leaves the aggregate buffer intact.
Ordinary synchronous listeners may already have observed the in-transaction
handoff.

This is a shallow identity check, not a deep payload fingerprint. `DomainEvent`
instances used with this bridge must be deeply immutable after registration,
and `AggregateRoot.domainEvents()` must preserve the recorded event object
references and order until clear. The implementation directly updates the
English KDoc for `AggregateRoot`, `domainEvents`, `drainDomainEvents`,
`clearDomainEvents`, `AbstractAggregateRoot`, `DomainEvent`, and the public
auto-configuration class. `drainDomainEvents` must explicitly prohibit use with
this bridge because its immediate clear breaks rollback retention.
`clearDomainEvents` must prohibit caller use from registration until completion.
For this bridge, `domainEvents()` must be side-effect-free and O(E) or better.
Mutating an event payload after handoff is unsupported and cannot be detected
reliably by this bridge.

## Duplicate Registration

The publisher stores one transaction-scoped registry inside its own Spring
`TransactionSynchronization`. It locates that synchronization only in
`TransactionSynchronizationManager.getSynchronizations()` for the current
transaction; it does not bind a separate thread-local resource.

- One synchronization is registered per publisher bean and Spring transaction.
- Aggregates are tracked by object identity, not `equals`, because two distinct
  aggregate instances may represent separate application work even when their
  IDs compare equal.
- Use an `IdentityHashMap`-equivalent transaction-local structure for average
  O(1) identity lookup. The publisher bean holds no shared mutable registry and
  uses no lock.
- An already registered aggregate identity is checked before calling
  `domainEvents()` again. Repeated event-bearing registration is therefore
  rejected without another snapshot or publication.
- The method order is fixed: when synchronization is active, first reject an
  identity already present in the current publisher synchronization; obtain the
  snapshot and return for an empty buffer; validate the event-bearing
  transaction; locate or register the synchronization and recheck identity;
  reserve the aggregate identity; then enter the `publishEvent` loop. Identity
  reservation before the first event prevents synchronous-listener re-entry
  from recursively publishing the same aggregate.
- Before commit, the current aggregate snapshot must equal the latest registered
  snapshot by list size and element reference identity.
- A publication exception or repeated registration poisons the registry;
  `beforeCommit` throws even if application code caught the original exception.
- Spring automatically suspends the outer synchronization list for
  `REQUIRES_NEW`, so the inner transaction receives a distinct publisher
  synchronization without custom resource binding.
- `afterCompletion(STATUS_COMMITTED)` clears each aggregate buffer once. It
  catches each clear failure independently and continues cleanup for the other
  aggregates. Rollback and `STATUS_UNKNOWN` do not clear buffers.

`PROPAGATION_NESTED` and savepoint-scoped handoff are unsupported. The default
Exposed `SpringTransactionManager` does not expose the savepoint callback needed
to retract or poison already registered Spring listener synchronization safely.
The same aggregate object must not be handed off in overlapping outer and
`REQUIRES_NEW` transactions; inner commit would clear the shared object buffer.
Use distinct aggregate instances and idempotent consumers across transaction
boundaries.

This rejects duplicate publication caused by repeated registration of the same
object in one transaction. Distinct aggregate objects are not deduplicated,
even when their aggregate IDs or events compare equal. The bridge provides no
global event deduplication across object reloads, process retries, or separate
transactions; consumers must remain idempotent.

## Publication Failure Semantics

Each `DomainEvent` is already a Spring-compatible event object, so this bridge
adds no mapping or serialization layer. Optional durable Spring Modulith
publication still uses the application's configured serializer.

- `publishEvent` runs inside the command transaction immediately after the
  repository save and before commit.
- If a `publishEvent` call throws, the exception propagates and the registry is
  poisoned. If the caller catches it, `beforeCommit` still rejects the commit.
  The aggregate buffer remains intact.
- If the transaction later rolls back, default `AFTER_COMMIT` listeners do not
  execute, Spring Modulith publication writes roll back with the command, and
  the aggregate buffer remains intact.
- If the transaction commits, the synchronization clears the aggregate buffer
  from `afterCompletion(STATUS_COMMITTED)`.
- Ordinary synchronous `@EventListener` consumers can run before commit and can
  observe a transaction that later rolls back. Commit-safe application side
  effects must use `@TransactionalEventListener`,
  `@ApplicationModuleListener`, or a durable outbox.

The implementation must not log event payloads, aggregate IDs, credentials,
tokens, secrets, or personally identifiable information. Exception category,
event type name, event count, and aggregate type name are sufficient diagnostic
context. Throwable messages and causes are not logged because application
exceptions may embed sensitive values. A post-commit clear failure is logged at
error level with a stable committed-cleanup category; operators must not retry
the already committed command and must discard the affected aggregate instance.

A synchronous default `AFTER_COMMIT` listener failure occurs after persistence
has committed. Spring logs and isolates failures raised from transaction
completion callbacks; asynchronous Spring Modulith listener failures are also
decoupled from the command return. In both cases the committed aggregate buffer
is cleared, and callers must not retry the command based on listener failure.
Listener retry/replay belongs to the listener or Spring Modulith publication
infrastructure, not this publisher.

`STATUS_UNKNOWN` emits an error log with the stable category
`aggregate-event-completion-unknown`; committed cleanup failure uses
`aggregate-event-cleanup-failed`. Logs use structured fields named `category`,
`aggregateType`, `eventType`, `eventCount`, `traceId`, `spanId`, and `requestId`;
tests inspect structured/MDC fields rather than formatted message text. At
registration the synchronization captures only the allowlisted correlation
keys `traceId`, `spanId`, and `requestId`, each limited to 128 ASCII
letters/digits plus `.`, `_`, `:`, or `-`. Raw headers, arbitrary MDC entries,
domain identifiers, and throwable text are never copied. Missing correlation
fields remain absent. These categories are alertable operator signals; a
built-in metric/callback API is rejected for #323 to avoid adding a new
observability dependency or public SPI for two completion anomalies.

README troubleshooting includes one decision table:

| Outcome | Persistence | Buffer | Command retry |
|---|---|---|---|
| No active transaction or same-transaction precondition violation | Indeterminate | Preserved | No automatic retry; reconcile first |
| Full rollback or poisoned handoff | Rolled back | Preserved | Allowed only in a fresh transaction; synchronous side effects may need deduplication |
| Committed listener failure | Committed | Cleared | Never retry command; use listener retry/replay |
| Committed cleanup failure | Committed | May remain | Never retry; discard aggregate instance |
| `STATUS_UNKNOWN` | Indeterminate | Preserved | No automatic retry; reconcile first |

Production rollout requires at least one allowlisted correlation field and an
application-owned audit/trace mapping from that opaque value to the command and
its persistence key. If correlation is absent, operators quarantine the
affected time window and use application audit records; automatic repair is
forbidden. Reconciliation inspects aggregate persistence, Spring Modulith
publication when enabled, and listener side effects:

- persistence present + publication present: do not replay the command; use
  Modulith replay or listener-specific recovery,
- persistence present + publication absent: do not replay the command; run an
  application-owned idempotent repair that derives the event from persisted
  state,
- persistence absent + publication absent: retry only as a new command after
  ruling out irreversible synchronous side effects,
- persistence absent + publication present: quarantine as an invariant breach
  and compensate manually; do not replay either path.

## Spring Modulith Boundary

`ExposedAggregateEventPublisher` depends only on Spring Framework and the
Spring-neutral DDD contracts.

When Spring Modulith is present, its application listeners and publication
registry receive the same events through `ApplicationEventPublisher` while the
command transaction is active. `@ApplicationModuleListener` execution occurs
after commit in its own transaction. The publisher does not call Spring
Modulith APIs and does not inspect listener or publication repository state.

The bridge supplies no serializer and does not weaken the existing Spring
Modulith publication-store trust boundary. Applications using durable
publication must provide an `EventSerializer` that supports every published
event type. Serialized payloads and event class names can be stored and replayed,
so events must minimize sensitive data and operators must protect the
publication database with appropriate access control, encryption, integrity,
and retention policies. As an application precondition, the configured
serializer must reject non-application or unsupported event classes. The bridge
does not enforce that allowlist; repository hardening beyond the existing module
is outside #323.

Plain Spring Boot consumers that need post-commit invocation use the default
`@TransactionalEventListener(phase = AFTER_COMMIT)`. A listener that performs a
database write must start an explicit new transaction, for example with
`REQUIRES_NEW`; after-commit invocation alone does not create a transaction for
that write. Other listener phases and plain synchronous `@EventListener` are
supported by Spring but are not commit-safe consumers for this bridge.

The existing `spring-boot/spring-modulith` module remains responsible for the
Exposed-backed `EventPublicationRepository`, restart replay, completion modes,
and publication observability.

The existing `SpringModulithJdbcCaffeineRepository` remains responsible for
cache write events from #319. It publishes application-owned events created
from persisted cache records and is not replaced by the aggregate publisher.

## Auto-Configuration

Add a separate phase class:

```text
ExposedAggregateEventPublisherAutoConfiguration
```

Requirements:

- Register the class directly in `AutoConfiguration.imports`.
- Declare
  `@AutoConfiguration(after = [ExposedSpringDataAutoConfiguration::class])` so
  the default transaction manager is evaluated first.
- Use `@ConditionalOnClass` for `AggregateRoot`,
  `ApplicationEventPublisher`, and Spring transaction synchronization APIs.
- Use `@ConditionalOnSingleCandidate(PlatformTransactionManager::class)` so the
  default bean is created only when Spring can select one autowire candidate.
  Multiple managers with one `@Primary` satisfy this condition; it does not
  mean exactly one manager bean.
- Use `@ConditionalOnMissingBean(ExposedAggregateEventPublisher::class)` to
  allow application replacement.
- The bean does not inject or identify a manager; the condition only avoids
  implying a default in ambiguous contexts. Runtime publication binds to the
  transaction active at the call site.
- Applications with multiple transaction managers and no single autowire
  candidate provide the publisher bean explicitly. With one `@Primary`, the
  auto-configured publisher remains valid because it does not inject a manager
  and still binds to the transaction active at the call site.
  README examples must show `transactionManagerRef`/`@Qualifier` selection for
  the repository and command boundary; the publisher itself needs only
  `ApplicationEventPublisher`.
- Do not add configuration properties in this issue; there is no safe immediate
  publication mode or retry mode to configure.

## Example Migration

Update `examples/ddd-spring-modulith-demo` so `OrderApplicationService`:

1. saves the aggregate inside `TransactionTemplate`,
2. calls `ExposedAggregateEventPublisher.publishAfterSave(order)`,
3. optionally throws to prove rollback behavior,
4. returns without manually iterating or clearing domain events.

This makes the example exercise the public integration rather than duplicate
its lifecycle logic.

The example continues to use Spring Modulith listeners and the Exposed-backed
publication repository. Its existing restart replay and idempotent listener
tests remain required. The existing `OrderHandoffFailedException` continues to
carry the failed aggregate so tests and callers can inspect the preserved event
buffer, but its log-safe message becomes a stable category without aggregate ID
or nested exception text.

Migration is a replacement, not dual publication: remove the manual
`ApplicationEventPublisher` loop and manual clear in the same deployment that
adds `publishAfterSave`. Running both paths duplicates events. Rolling back the
application version restores the complete manual path; mixed instances are
compatible only when consumers are idempotent.

Before rollout, source search must prove that the example's manual loop and
manual clear are gone. Alert rules for both structured anomaly categories,
allowlisted correlation propagation, audit lookup, database/publication read
access, and the reconciliation decision table must be ready before canary. The
canary verifies one persisted aggregate, one durable publication, one listener
side effect, and no anomaly-category logs. On duplicate publication, missing
publication, or a completion anomaly: stop rollout, preserve logs and affected
records, reconcile and repair the canary command, then roll back the whole
application binary if the defect is version-related. Binary rollback alone does
not repair an already indeterminate command. Do not mix old and new paths in one
instance.

## Tests

Add focused tests in `spring-boot/jdbc` using a real Spring transaction manager
and deterministic in-memory database transaction boundary:

- The Spring handoff occurs inside the transaction after repository persistence.
- A default `AFTER_COMMIT` transactional listener observes one event only after
  commit.
- Multiple events preserve aggregate recording order.
- Successful commit clears the aggregate event buffer.
- Rollback prevents default `AFTER_COMMIT` listener delivery while preserving
  the buffer.
- A second event-bearing registration of the same aggregate poisons the
  transaction and publishes no second snapshot.
- Recording another event after registration fails before commit, prevents
  default `AFTER_COMMIT` listener delivery, and preserves the buffer.
- No-event aggregate registration is a no-op.
- Manual clear/drain followed by a second call is rejected from the reserved
  identity path rather than treated as an empty-buffer no-op.
- Event-bearing registration fails when synchronization is inactive or no actual
  transaction is active; an empty-buffer call remains a no-op outside a
  transaction.
- A publisher failure poisons the transaction even when application code catches
  the original exception; an earlier synchronous listener may have observed a
  successfully handed-off prefix.
- `PROPAGATION_NESTED`/savepoint handoff is documented as unsupported and is not
  used by the default Exposed integration tests.
- Outer and inner `REQUIRES_NEW` transactions receive distinct synchronization
  registries; tests use distinct aggregate instances and cover inner
  commit/rollback.
- Commit and rollback completion each leave the next same-thread transaction
  with a fresh registry.
- A throwing `clearDomainEvents()` implementation cannot prevent registry
  cleanup attempts for other aggregates; the committed command is
  not reported as rollback-safe retry work.
- `STATUS_UNKNOWN` preserves buffers, discards synchronization state, and is
  documented as indeterminate and unsafe for automatic retry.
- A throwing default `AFTER_COMMIT` listener does not roll back persistence;
  committed buffer cleanup still runs and the command is not retried.
- One synchronization serves multiple aggregates, each normal aggregate calls
  `domainEvents()` once at registration and once before commit, and duplicate
  registration checks identity before another snapshot call.
- A synchronous listener that re-enters `publishAfterSave` with the same
  aggregate sees the reserved identity, poisons the transaction, and cannot
  recursively publish another event.
- Cleanup log capture verifies the exact structured keys/category, allowlisted
  correlation capture, and absence of throwable message, payload, aggregate ID,
  arbitrary MDC, and other sensitive values.
- Unknown-completion log capture verifies its exact structured category,
  preserved buffer, registration-time correlation retention, missing-correlation
  behavior, and the same sensitive-data exclusions.
- Auto-configuration backs off when an application bean is present.
- Auto-configuration is ordered after `ExposedSpringDataAutoConfiguration`,
  and creates the default publisher without Spring Modulith classes when Spring
  can determine a single transaction-manager autowire candidate.
- `ApplicationContextRunner` covers no manager, one manager, multiple managers
  without a primary, multiple managers with one primary, user override, and
  no-Spring-Modulith cases.
- A manual multi-manager command test proves that a caller-selected repository
  transaction can use the explicitly provided publisher; manager identity
  remains a documented caller precondition rather than a runtime claim.

Update the DDD Spring Modulith example tests to prove:

- commit persists the order and publishes exactly one domain event,
- rollback after registration leaves no order, listener side effect, or
  publication row,
- the aggregate retains events when the transaction or handoff fails,
- durable publication uses the configured serializer and preserves the existing
  sensitive-payload regression coverage,
- the example serializer rejects an unsupported event class without including
  its payload in logs,
- existing restart replay and idempotency behavior still passes.

Concurrency stress testing is not required. The registry is transaction- and
thread-bound through Spring's synchronization manager; the design does not
permit sharing one aggregate instance across concurrent commands. Tests must
not use ad hoc threads or sleeps. Each event-bearing aggregate is snapshotted
at registration and once before commit. The publisher retains the returned
immutable snapshot without another copy. Identity registry operations remain
average O(1), with no bean-global locks or shared mutable registry. Locating the
publisher synchronization uses Spring's sorted synchronization snapshot, so a
call costs O(E + S log S) time and O(E + S) temporary/reference storage where E
is the aggregate event count and S is the current synchronization count. Tests
with multiple pre-existing synchronizations must still observe exactly one
publisher synchronization for multiple aggregates.

## Documentation And Diagram

Update the English/Korean README pair for `spring-boot/jdbc` with:

- dependency and auto-configuration behavior,
- explicit save-then-publish usage,
- no-active-transaction failure behavior,
- rollback, synchronous-listener, and duplicate-registration semantics,
- the plain Spring Boot versus optional Spring Modulith boundary,
- the immutable event and stable event-reference requirements,
- the serializer, sensitive-data, and trusted-publication-store boundary when
  Spring Modulith persistence is enabled,
- the need for a new transaction when an `AFTER_COMMIT` listener writes to a
  database,
- default single-autowire-candidate auto-configuration plus an executable Kotlin example
  using `transactionManagerRef`/`@Qualifier` to align repository and command
  transaction in multi-manager setups,
- replacement-only migration and rollback guidance,
- unsupported nested/savepoint handoff and same-instance outer/`REQUIRES_NEW`
  reuse,
- committed listener/cleanup failure and `STATUS_UNKNOWN` no-retry guidance,
- the consolidated outcome/retry decision table and unknown-completion
  reconciliation procedure,
- the exclusion of R2DBC and durable outbox behavior.

Update the DDD Spring Modulith example README pair to use the new publisher and
remove manual publication guidance. Add a short cross-link from the Spring
Modulith README pair without duplicating the full contract.

A lifecycle diagram is required because the main behavioral change is timing.
Create matching SVG and PNG assets:

```text
docs/images/readme-diagrams/
  spring-boot-exposed-jdbc-domain-event-sequence-01.svg
  spring-boot-exposed-jdbc-domain-event-sequence-01.png
```

The diagram must distinguish repository persistence, in-transaction Spring
handoff, transaction commit, after-commit transactional listener execution,
optional Spring Modulith handling, and rollback. Use English labels and embed
the same asset in the localized README pair.

## Verification

Required local commands:

```text
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test \
  :bluetape4k-exposed-spring-modulith:test \
  :examples-ddd-spring-modulith-demo:test \
  --no-configuration-cache --no-daemon --console=plain
```

Also run:

- Kotlin/IDE diagnostics for touched files when available.
- `git diff --check`.
- Rendered SVG/PNG validation through `bluetape4k-diagram`.
- Local 7-Tier code review with `P0 = 0` and `P1 = 0`.
- PR body and metadata verification, followed by CI monitoring.

No workflow registration change is expected because no module is added or
renamed. If source inspection shows that existing CI path filters omit the
touched modules, the plan must be revised before implementation.

## Risks And Mitigations

1. **Synchronous listener side effects:** ordinary `@EventListener` consumers
   run before commit and cannot be rolled back reliably. Mitigation: require
   default `AFTER_COMMIT` `@TransactionalEventListener`,
   `@ApplicationModuleListener`, or an outbox for post-commit effects; database
   writes from a plain after-commit listener require a new transaction.
2. **Duplicate delivery across retries:** separate transactions can publish the
   same business event more than once. Mitigation: document idempotent consumers
   and avoid claiming exactly-once behavior.
3. **Aggregate mutation after registration:** a later event could describe
   unpersisted state. Mitigation: before-commit snapshot validation and explicit
   single registration after the final save; event payloads must be deeply
   immutable and event object references stable.
4. **Transaction registry bleed:** a custom thread-bound registry could survive
   completion or leak into `REQUIRES_NEW`. Mitigation: store state only inside
   Spring's current transaction synchronization list and test repeated and
   nested-new transactions.
5. **Spring Modulith coupling:** putting the bridge in the Modulith module would
   prevent plain Spring Boot use. Mitigation: place it in `spring-boot/jdbc` and
   use only `ApplicationEventPublisher`.
6. **Overlap with #319:** generic aggregate publication could accidentally
   replace cache write semantics. Mitigation: retain both APIs and document
   their distinct persistence boundaries.
7. **Savepoint rollback:** Spring listener synchronization cannot be retracted
   reliably after nested handoff with the default Exposed manager. Mitigation:
   declare `PROPAGATION_NESTED`/savepoint handoff unsupported.
8. **Post-commit clear failure:** persistence is already committed, so retrying
   the command can duplicate delivery. Mitigation: isolate and log each cleanup
   failure, discard synchronization state and the aggregate instance, and never
   report the committed command as retry-safe.
9. **Durable payload exposure:** Spring Modulith may store serialized payloads
   and class names. Mitigation: minimize event data, use an application-owned
   serializer and trusted event types, and protect the publication database.
10. **Transaction-manager ambiguity:** Spring thread-local state is not manager
    identity proof. Mitigation: make no runtime ownership claim, auto-configure
    only when Spring can determine a single manager autowire candidate, and
    require the caller to align repository persistence and handoff in one
    transaction.
11. **Completion uncertainty:** listener failure happens after commit, while
    `STATUS_UNKNOWN` cannot prove commit or rollback. Mitigation: never retry a
    committed listener/cleanup failure; preserve the buffer but require operator
    reconciliation for unknown completion.

## Acceptance Criteria

- `spring-boot/jdbc` exposes an explicit transaction-aware aggregate event
  publisher with English KDoc.
- The publisher is auto-configured only when Spring can determine a single
  transaction-manager autowire candidate, is ordered after the existing JDBC
  auto configuration, and backs off for an application bean.
- Events are handed to Spring in order inside the active transaction;
  default `AFTER_COMMIT` listeners execute only after commit and never on full
  rollback.
- Event-bearing handoff requires synchronization and an actual transaction;
  repository persistence in that same transaction is a documented caller
  precondition rather than an unverifiable runtime claim.
- Successful commit clears the aggregate buffer from committed completion;
  rollback, pre-commit validation failure, and publisher failure preserve it.
- Repeated event-bearing registration of one aggregate poisons the transaction
  and cannot duplicate a snapshot.
- Publication failure remains fail-closed even if caught by application code.
- Publisher state lives only in the current Spring synchronization list, so
  outer and inner `REQUIRES_NEW` registries are isolated; the same aggregate
  object must not cross those overlapping transaction boundaries.
- `PROPAGATION_NESTED`/savepoint handoff is explicitly unsupported.
- Committed listener/cleanup failures and unknown completion have distinct,
  documented no-automatic-retry behavior.
- Mutation and repeated saves must finish before the single registration;
  events are deeply immutable and retain stable object identity until clear.
- Plain Spring Boot use does not require Spring Modulith.
- Durable Spring Modulith use documents serializer ownership, trusted event
  types, sensitive payload controls, and idempotent consumers.
- The DDD Spring Modulith example uses the new public publisher.
- README locale pairs and the lifecycle diagram describe the exact timing and
  durability limitations.
- Relevant tests, diagnostics, diagram validation, diff check, 7-Tier review,
  PR checks, and CI pass before merge is requested.
