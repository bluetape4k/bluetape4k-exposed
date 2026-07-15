# Issue #321 Transaction-Aware Snapshot Near-Cache Design

## Context

Issue #321 asks for a transaction-aware near-cache for Exposed repository
results without distributing Exposed DAO `Entity` objects or the internal
transaction-scoped `EntityCache`.

Current repository facts:

- `JdbcCacheRepository`, `SuspendedJdbcCacheRepository`, and
  `R2dbcCacheRepository` already cache serializable records and expose
  read-through, write-through, and write-behind behavior.
- Caffeine, Lettuce, and Redisson implementations own their cache and database
  write semantics. Calling their ordinary `put` methods after a transaction
  can write the database again, so those methods are not safe commit callbacks.
- `CachePersistedWrite` and `afterPersisted` already mark successful Caffeine
  persistence boundaries. They do not provide a general caller-owned Exposed
  transaction buffer.
- Exposed 1.3.1 exposes `StatementInterceptor.afterCommit` and
  `StatementInterceptor.afterRollback`. Both `JdbcTransaction` and
  `R2dbcTransaction` can register a core `StatementInterceptor`.
- Spring's `TransactionSynchronization` is already used by the JDBC DDD event
  publisher, but issue #321 must remain Spring-neutral.

The baseline command passed before this design was written:

```bash
./gradlew \
  :bluetape4k-exposed-cache:test \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  --no-daemon
```

Result: `BUILD SUCCESSFUL` in 1m 28s. The R2DBC Caffeine module reported 66
passing tests and one existing pending case.

## Goal

Add an opt-in, Spring-neutral coordinator that stages immutable snapshot cache
mutations inside an Exposed JDBC or R2DBC transaction and applies them only
after that transaction commits.

The design must guarantee:

- rollback never exposes dirty snapshots;
- the cache layer never stores or serializes an Exposed DAO `Entity` or
  `EntityCache`;
- cache callbacks never repeat a database write;
- cache mutation failure after commit cannot change the database outcome;
- repeated mutations for the same store and identifier have deterministic
  last-mutation-wins behavior;
- local Caffeine stores work immediately, while distributed implementations
  can reuse the same cache-only SPI and their existing invalidation protocols.

## Non-Goals

- Replacing Exposed DAO's transaction-scoped identity map.
- Turning Caffeine or Redis write-behind queues into a durable outbox.
- Making cache state part of the database transaction's atomic commit.
- Automatically wrapping arbitrary repository beans.
- Adding Spring Boot auto-configuration.
- Adding Ktor health routes or metrics; issue #325 owns that surface.
- Adding migration or schema-drift tooling; issue #322 owns that surface.

## Considered Approaches

### A. Exposed transaction interceptor plus cache-only store SPI

Stage cache-only mutations in transaction-local state and register one
interceptor per coordinator and transaction. Apply the final staged mutations
from `afterCommit`; discard them from `afterRollback`.

Advantages:

- works in plain Exposed, Spring-managed Exposed JDBC, and R2DBC transactions;
- preserves the repository's Spring-neutral DDD boundary;
- naturally isolates retries and concurrent transactions;
- lets each cache backend preserve its own near-cache synchronization rules.

Costs:

- requires explicit JDBC and R2DBC transaction entrypoints because the concrete
  transaction classes own interceptor registration;
- post-commit cache failure is observable but cannot be rolled back.

This is the chosen approach.

### B. Spring `TransactionSynchronization` adapter

This would reuse the pattern in `ExposedAggregateEventPublisher` and make
after-commit behavior straightforward for Spring JDBC applications.

Rejected because it excludes plain Exposed and R2DBC callers and would make the
core cache contract Spring-dependent.

### C. Update cache immediately and invalidate on rollback

This is smaller but creates a visibility window in which another thread or node
can read uncommitted data. A rollback callback cannot retract data already
observed by a caller.

Rejected because it violates the primary issue requirement.

## Architecture

### Immutable snapshot envelope

`exposed/cache` adds an immutable, serializable value envelope:

```kotlin
data class CacheSnapshot<V : Serializable>(
    val value: V,
    val revision: String? = null,
) : Serializable
```

`value` is an application-owned immutable record or DTO. `revision` is optional
metadata such as a row version, normalized update timestamp, or stable content
hash. The core coordinator treats it as an opaque token; it does not guess an
ordering between application revision formats.

The envelope must not accept an Exposed DAO `Entity` as a documented or tested
cache value. Mapping from `Entity`, `ResultRow`, or domain state into the
snapshot occurs inside the caller's transaction before staging. Public examples
use Kotlin data classes implementing `Serializable`.

### Cache-only store

`exposed/cache` adds a synchronous cache-only SPI:

```kotlin
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    val cacheName: String

    fun get(id: ID): CacheSnapshot<V>?

    fun put(id: ID, snapshot: CacheSnapshot<V>)

    fun invalidate(id: ID)
}
```

The SPI is intentionally synchronous because Exposed's core
`StatementInterceptor.afterCommit` is synchronous for both JDBC and R2DBC. A
store implementation must perform only cache work. It must not call an Exposed
repository `put`, invoke a map writer, start a new database transaction, or
delete a database row.

The initial implementation provides Caffeine adapters in the existing JDBC and
R2DBC Caffeine modules. Both adapters mutate Caffeine directly and therefore do
not invoke repository database writers.

Distributed cache modules can implement the same SPI only where their client
offers a bounded synchronous cache-only operation. Redisson implementations
must use a writer-free map so local-cache synchronization cannot delete or
rewrite a database row. Lettuce implementations must preserve the existing
near-cache invalidation protocol rather than inventing a second pub/sub format.
If a backend cannot satisfy the synchronous callback and cache-only guarantees,
it is not adapted in this issue; applications use commit-time invalidation of a
safe local store and let the next read repopulate it.

### Transaction-aware coordinator

`TransactionAwareSnapshotCache<ID, V>` owns one `SnapshotCacheStore` and
provides:

```kotlin
fun get(id: ID): CacheSnapshot<V>?

fun stagePut(
    transaction: JdbcTransaction,
    id: ID,
    snapshot: CacheSnapshot<V>,
)

fun stagePut(
    transaction: R2dbcTransaction,
    id: ID,
    snapshot: CacheSnapshot<V>,
)

fun stageInvalidate(transaction: JdbcTransaction, id: ID)

fun stageInvalidate(transaction: R2dbcTransaction, id: ID)
```

Kotlin extension functions may provide transaction-receiver syntax when that
keeps call sites clearer, but they delegate to the same coordinator and do not
duplicate lifecycle state.

The coordinator stores mutations under a private Exposed transaction user-data
key. The transaction-local buffer is an insertion-ordered map keyed by cache
store identity and entity identifier. A later mutation for the same key replaces
the earlier mutation without changing unrelated key order. The final mutation
therefore wins:

- put then put: publish the latest snapshot;
- put then invalidate: invalidate;
- invalidate then put: publish the snapshot.

Only the first staged mutation for a coordinator in a transaction registers an
interceptor. JDBC and R2DBC entrypoints share mutation and error-handling logic.

## Transaction Lifecycle

### Commit

1. The application maps transaction-bound state to an immutable snapshot.
2. `stagePut` or `stageInvalidate` records the mutation in the current
   transaction buffer.
3. Exposed commits the database transaction.
4. `afterCommit` detaches the final mutation list from transaction state.
5. The coordinator applies mutations to the cache-only store in deterministic
   order.
6. The transaction buffer and interceptor references are released.

The cache mutation happens after the database commit. Another process can read
old cache data during the short commit-to-cache window, so this feature provides
commit safety rather than distributed atomicity.

### Rollback

`afterRollback` clears the transaction buffer without invoking the store.
Neither an explicit rollback nor an exception-triggered rollback can expose a
staged snapshot.

### Retry and nesting

Exposed transaction retries create a new transaction object. Because buffers
are transaction-local, a failed attempt cannot leak mutations into a later
attempt.

Nested work that participates in the same Exposed transaction shares the same
buffer. An independently committed transaction owns a separate buffer and
applies only its own mutations.

## Read-Through Usage

The coordinator does not open transactions. A caller follows this pattern:

1. read `TransactionAwareSnapshotCache.get(id)`;
2. on a miss, load the row or DAO inside the caller-owned Exposed transaction;
3. map it to an immutable record;
4. call `stagePut` with the active transaction;
5. return the record to the caller;
6. allow the cache to populate only after commit.

Write paths stage the committed snapshot or, when cross-node ordering is
uncertain, stage invalidation so the next read reloads canonical database state.

No API silently falls back to an immediate cache write when no transaction is
available. The caller must pass the active concrete transaction, which makes the
boundary explicit and testable.

## Failure Semantics

Database failure and cache failure are different outcomes.

- Mapping or staging failure occurs before commit and propagates normally.
- Rollback discards all staged mutations.
- A non-fatal store failure in `afterCommit` is recorded through a configurable
  failure observer and logged with cache name, mutation type, and exception
  type. Values, credentials, SQL, URLs, and serialized snapshots are never
  logged.
- A store failure does not attempt database rollback, repeat the committed DB
  operation, or apply later mutations for the same failed key blindly.
- Independent mutations continue after an ordinary store exception so one bad
  entry does not prevent unrelated invalidations. Fatal JVM errors are not
  swallowed.
- Cancellation signals are not treated as an ordinary cache failure. No
  coroutine is launched by the synchronous callback.

The default failure observer logs and continues. Applications needing durable
cache repair must use an application-owned outbox or repair queue; the
coordinator is not that queue.

The observer contract receives sanitized structural context rather than the
snapshot or identifier:

```kotlin
fun interface SnapshotCacheFailureObserver {
    fun onFailure(failure: SnapshotCacheFailure)
}

data class SnapshotCacheFailure(
    val cacheName: String,
    val mutationType: SnapshotCacheMutationType,
    val cause: Throwable,
)
```

`SnapshotCacheMutationType` is a bounded `PUT`/`INVALIDATE` enum. The default
observer logs `cacheName`, `mutationType`, and the cause type without logging
the cause message because a backend exception may contain a URL or key.

## Consistency and Cross-Node Rules

- The database is the source of truth.
- `revision` is metadata for comparison, diagnostics, or backend-specific
  conditional logic; the core does not order opaque revisions.
- Last mutation wins only within one transaction buffer. It does not claim a
  total order between transactions or nodes.
- For concurrent writers, commit-time invalidation is the safe default because
  the next cache miss reloads canonical state.
- A backend may publish the committed snapshot directly only when its existing
  near-cache protocol propagates the update or invalidation to peer nodes.
- Redisson must retain its configured `RLocalCachedMap` sync strategy.
- Lettuce must retain its existing near-cache invalidation channel and TTL
  behavior.
- The coordinator never propagates Exposed `EntityCache` contents, transaction
  handles, DAO instances, or lazy relationship state.

## Initial Integration Scope

Required in this issue:

- core snapshot envelope, cache-only SPI, mutation model, coordinator, and
  JDBC/R2DBC interceptor registration in `exposed/cache`;
- direct Caffeine store adapters for JDBC and R2DBC Caffeine modules;
- a JDBC Redisson snapshot store using a writer-free `RLocalCachedMap` and the
  configured local-cache sync strategy, with a dedicated snapshot namespace;
- focused fake-store tests for core lifecycle, ordering, failure isolation, and
  two-node invalidation semantics;
- JDBC and R2DBC integration tests proving commit-only update and rollback
  discard;
- a sequential Redisson integration test proving a committed invalidation is
  observed by a second near-cache client and rollback publishes nothing;
- English and Korean cache README documentation explaining Exposed
  `EntityCache` versus repository snapshot near-cache;
- KDoc for all public contracts.

Conditional in this issue:

- add a JDBC Lettuce adapter only if current source inspection proves it
  can execute synchronously after commit, bypass every DB writer, and preserve
  the existing peer invalidation protocol without a new dependency or blocking
  bridge.

Explicitly deferred:

- async adapters that require `runBlocking`, detached coroutines, or a new
  background worker;
- R2DBC Redis/Redisson/Lettuce adapters that would perform blocking network I/O
  from the synchronous Exposed commit callback;
- Ktor operational routes and metrics (#325);
- example application composition (#326).

## Testing Strategy

### Core tests

- staged put is invisible before commit and visible after commit;
- rollback invokes no store mutation;
- put/put, put/invalidate, and invalidate/put are last-mutation-wins;
- different identifiers preserve deterministic order;
- only one interceptor is registered per coordinator and transaction;
- different transactions do not share buffered state;
- failed transaction attempts do not leak into a successful retry;
- one store failure does not skip independent later mutations;
- failure logs and observers expose no snapshot payload;
- a fake two-node store proves committed invalidation removes stale peer state;
- an attempted example using an Exposed DAO object is absent from the public
  value contract and documentation fixtures use immutable serializable records.

### JDBC Caffeine integration tests

- a transaction stages an immutable `ResultRow`-derived snapshot;
- commit populates Caffeine only after DB success;
- rollback leaves both local cache and peer test observer unchanged;
- staged invalidation removes a pre-existing snapshot after commit;
- ordinary repository write modes are not invoked by the cache-only adapter.

### R2DBC Caffeine integration tests

- `suspendTransaction` uses the same commit-only semantics;
- rollback and coroutine cancellation publish no dirty snapshot;
- cache callback performs no `runBlocking` or detached coroutine work;
- staged invalidation removes a pre-existing snapshot after commit.

### JDBC Redisson integration tests

- the snapshot store creates a writer-free local cached map under a dedicated
  namespace;
- a committed put is visible to a second client;
- a committed invalidation removes the peer client's stale local entry through
  Redisson's configured sync strategy;
- rollback produces no Redis mutation or peer invalidation;
- no Exposed map writer or database delete hook is invoked.

### Regression commands

```bash
./gradlew :bluetape4k-exposed-cache:test
./gradlew :bluetape4k-exposed-jdbc-caffeine:test
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test
./gradlew :bluetape4k-exposed-jdbc-redisson:test
./gradlew detekt
git diff --check
```

Redis/Testcontainers-backed validation remains sequential if a conditional
distributed adapter is added.

## Documentation

Update the English/Korean README pairs for `exposed/cache` and the affected
Caffeine modules. Documentation must state:

- Exposed DAO `EntityCache` is a transaction-local identity map, not an
  application cache;
- only immutable serializable snapshots belong in this cache;
- commit-safe does not mean DB/cache atomic or crash-durable;
- invalidation is safer than snapshot publication when cross-node ordering is
  not guaranteed;
- post-commit cache failures require application-owned repair if stronger
  guarantees are needed.

No new diagram is required. The feature is a lifecycle/API contract that is
clearer as a compact commit/rollback example and behavior table; issue #325 will
own the operational topology documentation when routes and metrics are added.

## Compatibility and Rollback

- Existing repository interfaces and write modes remain unchanged.
- The feature is opt-in; existing callers perform no transaction buffering.
- No database schema or serialized existing cache format is migrated.
- New envelopes should use a distinct cache namespace to avoid mixing raw DTO
  values with `CacheSnapshot` values.
- Rollback is application-level removal of the coordinator usage and eviction
  of its dedicated namespace. Existing repository caches continue unchanged.

## Failure Modes

1. **Dirty snapshot exposure:** prevented by staging only and applying from
   `afterCommit`; rollback tests prove no mutation.
2. **Database rewrite from callback:** prevented by the cache-only SPI and
   direct adapter tests that bypass repository writers.
3. **Cross-node stale entry:** mitigated by commit-time invalidation as the
   default and reuse of existing backend invalidation protocols.
4. **Post-commit cache outage:** database remains committed; failures are
   sanitized, isolated, and observable but not retried implicitly.
5. **Retry leakage:** prevented by transaction-local buffers tied to the
   concrete Exposed transaction object.
6. **R2DBC callback blocking:** prevented by requiring synchronous cache-only
   stores and rejecting adapters that need `runBlocking` or detached jobs.

## Acceptance Criteria Mapping

- No DAO `Entity` or `EntityCache` in distributed values: immutable snapshot
  envelope, safe examples, and public API/KDoc audit.
- Commit-only cache update: core, JDBC, and R2DBC commit/rollback tests.
- Rollback non-update: interceptor lifecycle tests and integration tests.
- Stale invalidation: last-mutation-wins tests plus two-node invalidation fake.
- Coexistence with write-through/write-behind: cache-only SPI never calls the
  existing repository `put` path or map writer.
- Local near-cache: Caffeine adapters in JDBC and R2DBC modules.
- Distributed coordination: writer-free JDBC Redisson adapter with a real
  two-client invalidation test; Lettuce remains conditional on proving the same
  synchronous cache-only boundary.
- Documentation distinction: English/Korean README parity and public KDoc.

## Definition of Done

- The public API and lifecycle match this design without unresolved
  placeholders or hidden Spring dependencies.
- Every required core/JDBC/R2DBC behavior has a failing-first regression test
  and passes after implementation.
- Targeted Gradle tests, Detekt, and `git diff --check` pass.
- English/Korean documentation is equivalent and uses actual API names.
- Pre-PR and PR review converge at P0=0 and P1=0.
- PR delivery stops at merge-ready state until fresh explicit merge approval.
