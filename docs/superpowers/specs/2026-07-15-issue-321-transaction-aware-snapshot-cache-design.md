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
- public APIs reject a direct Exposed DAO `Entity`, and the documented value
  contract prohibits nested `Entity`, `EntityCache`, lazy, or mutable state;
- cache callbacks never repeat a database write;
- cache mutation failure after commit cannot change the database outcome;
- repeated mutations for the same store and identifier have deterministic
  last-mutation-wins behavior;
- local Caffeine stores work immediately, while distributed implementations
  can reuse the invalidation SPI and their existing invalidation protocols.

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
interceptor per transaction. Apply the final staged mutations
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

fun interface CacheSnapshotMapper<S, V : Serializable> {
    fun toSnapshot(source: S): CacheSnapshot<V>
}

fun interface CacheSnapshotValueValidator<V : Serializable> {
    fun validate(value: V)
}

fun interface SnapshotValueSizer<V : Serializable> {
    fun estimatedRetainedBytes(value: V): Long
}
```

`value` is an application-owned immutable record or DTO. `revision` is optional
metadata such as a row version, normalized update timestamp, or stable content
hash. The core coordinator treats it as an opaque token; it does not guess an
ordering between application revision formats.

Mapping from `Entity`, `ResultRow`, or domain state occurs synchronously inside
the caller's active root transaction through `CacheSnapshotMapper`; only the
mapper result is staged. Public staging entrypoints reject a top-level Exposed
DAO `Entity` at runtime before changing transaction state. Public examples and
compile tests use immutable Kotlin data classes implementing `Serializable`.

`Serializable` and the top-level runtime check cannot prove deep immutability.
Callers are responsible for ensuring that nested collections, relationships,
and lazy references are detached and immutable. An optional application value
validator can reject domain-specific mutable or oversized graphs before
staging. The default validator rejects top-level DAO entities and otherwise
accepts the value; it does not perform reflection-based deep graph traversal.

### Snapshot-specific configuration

Snapshot adapters use a dedicated configuration rather than
`LocalCacheConfig`:

```kotlin
data class SnapshotCacheConfig(
    val namespace: String,
    val schemaVersion: String,
    val maxStagedMutations: Int = 10_000,
    val maxParticipatingStores: Int = 8,
)

data class CaffeineSnapshotCacheConfig(
    val snapshot: SnapshotCacheConfig,
    val maximumSize: Long = 10_000,
    val maximumWeight: Long? = null,
    val expireAfterWrite: Duration = Duration.ofMinutes(10),
    val expireAfterAccess: Duration? = null,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration = Duration.ofMillis(250),
    val fenceStripes: Int = 1_024,
    val maxOutstandingMissTokens: Int = 10_000,
)
```

`SnapshotCacheConfig` deliberately has no `CacheWriteMode`: a snapshot store is
always cache-only and cannot mean read-through, write-through, or write-behind.
Passing `LocalCacheConfig` is not supported, so existing write-mode semantics
cannot be silently ignored. All positive bounds and durations are validated at
construction. The common object contains only namespace and transaction-wide
limits used by every backend. `CaffeineSnapshotCacheConfig` owns capacity,
expiry, staged-weight, local-drain, and local ordering-fence settings; Redisson
never accepts or silently ignores them. `fenceStripes` must be a power of two
between 64 and 65,536, and `maxOutstandingMissTokens` must be positive.
`maxStagedMutations` bounds entry count and callback
work, not serialized bytes or total heap usage; documentation requires
applications with large DTOs to enforce their own payload limit in the value
validator.
`localDrainBudget` is a transaction-wide cooperative budget for in-process
Caffeine work, not a hard preemption boundary or per-store allowance; one
second is the recommended production ceiling.
`schemaVersion` is an application-owned, nonblank format token and changes
whenever serialized field meaning or nested generic shape changes.
When `maximumWeight` or `maxStagedWeight` is set, the Caffeine factory requires
a `SnapshotValueSizer<V>` that returns a conservative retained-byte estimate;
it configures Caffeine's weighted capacity and enforces the staged-byte ceiling
before buffer mutation. Without a sizer the optional weight limits must be null,
and documentation makes clear that entry counts alone are not heap bounds. A
reusable `maximumEstimatedPayloadBytes(sizer, limit)` validator is provided for
applications that want per-value rejection.

The JDBC Redisson invalidator uses this exact additional configuration:

```kotlin
data class JdbcRedissonSnapshotInvalidatorConfig(
    val snapshot: SnapshotCacheConfig,
    val nearCacheMaximumSize: Int = 10_000,
    val maxEncodedKeyBytes: Int = 4 * 1024,
    val maxBatchEncodedKeyBytes: Int = 64 * 1024,
    val maxCommitEncodedKeyBytes: Int = 256 * 1024,
    val maxOutstandingChunks: Int = 64,
    val maxOutstandingEncodedBytes: Long = 4L * 1024 * 1024,
    val namespaceVerificationTimeout: Duration = Duration.ofSeconds(2),
    val multiNode: Boolean = true,
    val synchronizationStrategy: LocalCachedMapOptions.SyncStrategy =
        LocalCachedMapOptions.SyncStrategy.INVALIDATE,
    val reconnectionStrategy: LocalCachedMapOptions.ReconnectionStrategy =
        LocalCachedMapOptions.ReconnectionStrategy.CLEAR,
    val trustedBinaryCache: Boolean = false,
)
```

Construction rejects non-positive size/byte/outstanding limits or verification
timeouts,
`maxBatchEncodedKeyBytes > maxCommitEncodedKeyBytes`, any
reconnect strategy other than `CLEAR`, and `NONE` synchronization in multi-node
mode. `UPDATE` is unnecessary for invalidation-only operation and is rejected.

Distributed identifiers use library-owned scalar policies only in this initial
scope:

```kotlin
sealed interface SnapshotIdentifierPolicy<ID : Any>

fun longSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<Long>
fun uuidSnapshotIdentifierPolicy(): SnapshotIdentifierPolicy<UUID>

sealed interface SnapshotRedissonCodec<ID : Any> : Codec {
    val codecVersion: String
}

fun <ID : Any> snapshotRedissonCodec(
    delegate: Codec,
    codecVersion: String,
    identifierPolicy: SnapshotIdentifierPolicy<ID>,
): SnapshotRedissonCodec<ID>
```

The wrapper requires `codecVersion` to match `[A-Za-z0-9._-]{1,64}` and owns
the map-key encoder for the selected scalar policy; the delegate supplies value
and non-map-key codec behavior only. The canonical Long and UUID encoders are
pure, deterministic, and fixed-length. The wrapper applies
the scalar policy before encoding and rejects DAO entities, transactions,
composite graphs, and lazy references by construction.
Distributed identifiers are Redis-visible infrastructure keys and must be
non-secret, non-credential, non-PII surrogate row identifiers. The initial
scope intentionally provides no String policy because syntax cannot distinguish
a public identifier from a bearer token, credential, or personal data.
Applications map `EntityID`, composite IDs, or sensitive domain IDs to a
non-sensitive Long/UUID surrogate. Composite and String IDs remain supported
by local Caffeine only.

Canonical map-key wire encoding is normative and versioned:

- Long uses exactly eight bytes: signed two's-complement, most-significant byte
  first. Golden vectors are `0 -> 0000000000000000`,
  `1 -> 0000000000000001`, and `-1 -> ffffffffffffffff`.
- UUID uses exactly 16 bytes: `mostSignificantBits` as the first signed
  big-endian Long followed by `leastSignificantBits` in the same form. The
  golden vector `00112233-4455-6677-8899-aabbccddeeff` maps to
  `00112233445566778899aabbccddeeff`.
- Decoders require the exact length and are the inverse of those encoders; no
  platform-native byte order, text rendering, or delegate key codec participates.

The canonical key-encoding identifier (`bt4k-long-be-v1` or
`bt4k-uuid-be-v1`) enters the remote fingerprint. Changing any byte rule
requires a new encoding identifier, `codecVersion`, and namespace rollout.

### Cache-only store

`exposed/cache` adds a synchronous cache-only SPI:

```kotlin
@InternalSnapshotCacheApi
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits

    @InternalSnapshotCacheApi
    fun claimMiss(
        miss: SnapshotCacheMiss<ID, V>,
    ): ClaimedSnapshotMiss<ID, V>

    fun applySnapshots(
        snapshots: List<SnapshotCacheMutation.Put<ID, V>>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport

    fun applyInvalidations(
        ids: List<ID>,
        deadline: SnapshotCacheDeadline,
    ): SnapshotCacheApplyReport
}

@InternalSnapshotCacheApi
fun interface ClaimedSnapshotMiss<ID : Any, V : Serializable> {
    fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V>
}

@InternalSnapshotCacheApi
interface AsyncSnapshotInvalidationStore<ID : Any> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits

    fun measure(id: ID): MeasuredInvalidation<ID>

    fun submitInvalidation(
        batch: List<MeasuredInvalidation<ID>>,
    ): CompletionStage<SnapshotCacheApplyReport>
}

data class MeasuredInvalidation<ID : Any>(
    val id: ID,
    val encodedBytes: Int,
    val encodedSha256: String,
)

interface SnapshotCacheDeadline {
    fun remaining(): Duration
    val isExpired: Boolean
}

data class SnapshotCacheLimits(
    val maxStagedMutations: Int,
    val maxParticipatingStores: Int,
    val maxStagedWeight: Long? = null,
    val localDrainBudget: Duration? = null,
)

data class SnapshotStoreId(
    val backend: String,
    val namespace: String,
)

sealed interface SnapshotCacheMutation<ID : Any, V : Serializable> {
    val id: ID

    data class Put<ID : Any, V : Serializable>(
        override val id: ID,
        val snapshot: CacheSnapshot<V>,
        @InternalSnapshotCacheApi
        val localFence: SnapshotLocalFence<ID>? = null,
    ) : SnapshotCacheMutation<ID, V>

    data class Invalidate<ID : Any, V : Serializable>(
        override val id: ID,
    ) : SnapshotCacheMutation<ID, V>
}

@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor()

class SnapshotCacheLookup<ID : Any, V : Serializable> private constructor(
    val snapshot: CacheSnapshot<V>?,
    val miss: SnapshotCacheMiss<ID, V>?,
) {
    companion object {
        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> hit(
            snapshot: CacheSnapshot<V>,
        ): SnapshotCacheLookup<ID, V>

        @InternalSnapshotCacheApi
        fun <ID : Any, V : Serializable> miss(): SnapshotCacheLookup<ID, V>
    }
}

class SnapshotCacheMiss<ID : Any, V : Serializable> internal constructor() {
    override fun toString(): String = "SnapshotCacheMiss(opaque)"
}

data class SnapshotCacheApplyReport(
    val results: List<SnapshotCacheOperationResult>,
) {
    @InternalSnapshotCacheApi
    fun requireReconciled(
        operation: SnapshotCacheOperation,
        expectedCount: Int,
    ): SnapshotCacheApplyReport
}

data class SnapshotCacheOperationResult(
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
)

enum class SnapshotCacheOperation { GET, PUT, INVALIDATE }
enum class SnapshotCacheOutcome {
    SUCCESS,
    FAILED,
    NOT_ATTEMPTED,
    REJECTED,
}

sealed interface SnapshotCacheFailureBuffer {
    val capacity: Int
    val size: Int
    val droppedCount: Long
    val observerFailureCount: Long

    fun poll(): SnapshotCacheFailure?
    fun drainTo(
        observer: SnapshotCacheFailureObserver,
        maxElements: Int = capacity,
    ): SnapshotCacheDrainResult
}

data class SnapshotCacheDrainResult(
    val deliveredCount: Int,
    val observerFailedCount: Int,
    val remainingCount: Int,
    val observerExceptionType: String? = null,
)

fun snapshotCacheFailureBuffer(
    capacity: Int = 1_024,
): SnapshotCacheFailureBuffer
```

The snapshot-store SPI is opt-in internal API, synchronous, and in-process;
normal consumers cannot call raw apply methods to bypass miss-token/transaction
staging. The distributed
invalidation SPI returns completion stages and is internal to reviewed adapter
modules. An implementation must perform only cache work. It must not call an Exposed
repository `put`, invoke a map writer, start a new database transaction, or
delete a database row.

`ID` values are required to be immutable and to retain stable `equals` and
`hashCode` semantics for the whole transaction and cache lifetime. Supported
composite identifiers are immutable data classes whose components satisfy the
same rule; mutable identifier fixtures are rejected from documentation and a
composite-ID test locks the contract.

The registry coalesces to one final mutation per store/id before calling the
SPI. Local work not started before the cooperative budget expires is
`NOT_ATTEMPTED` with its affected count. Local stores may iterate in-process and report isolated
per-entry failures. Because the buffer already coalesces each key, grouping
cannot reorder two mutations for the same identifier.

The public Caffeine `lookup(id)` operation acquires the stripe lock and returns
an exactly-one result: either `snapshot` or a library-constructed opaque `miss`.
The facade stores the ID and an ID-bound opaque `SnapshotLocalFence` in a
weak-identity capability registry protected by an explicit lock and keyed by
the miss object; neither capability has an ID/fence/token getter,
is not `Serializable`, and has a constant sanitized `toString`. Snapshot
staging requires that miss token; there is no public bare-id snapshot PUT
overload. Built-in
Caffeine stores require their own live opaque miss capability on every PUT and
reject a missing, foreign, reused, or out-of-range token before registry/cache
mutation. Other stores must reject a miss capability they do not own.
The private lookup constructor and opt-in factories enforce XOR: exactly one of
`snapshot` and `miss` is non-null. Claim atomically removes the weak-registry
entry and returns an opaque `ClaimedSnapshotMiss` preparer that privately owns
the ID/fence until it produces the PUT mutation. The common mapped-staging path
claims first, then runs the mapper/validator, then calls `prepare(snapshot)`;
the preparer itself accepts exactly one call. Mapper failure simply discards the
claimed preparer. This makes every miss token
one-shot even when the originating transaction rolls back. Values disappear
when an unclaimed token is garbage-collected.
`maxOutstandingMissTokens` bounds deliberately retained unclaimed tokens;
lookup fails before a database transaction when the bound is full after stale
weak entries are expunged.

The initial implementation provides Caffeine adapters in the existing JDBC and
R2DBC Caffeine modules. Both mutate Caffeine directly and therefore do not
invoke repository database writers. They accept `CaffeineSnapshotCacheConfig`
and honor `maximumSize`, `expireAfterWrite`, and `expireAfterAccess`; the
snapshot namespace is never unbounded by entry count.

Distributed snapshot publication is not implemented in this issue. A timed-out
older Redis PUT can complete after a newer commit or invalidation and resurrect
stale data unless the backend has an atomic comparable-revision fence. The core
revision is intentionally opaque, and the existing Redisson/Lettuce layers do
not provide that contract. The JDBC Redisson integration is therefore a
writer-free, invalidation-only store that rejects `Put` mutations and submits
encoded-byte-bounded multi-invalidate chunks. Existing repository read-through paths
may populate cache-safe DTOs; #321 adds commit-safe invalidation of those
entries. Lettuce remains conditional on proving the same nonblocking,
cache-only, existing-protocol invalidation boundary. Distributed snapshot PUT
is deferred until a separately reviewed fencing design.

### Module boundary and public entrypoints

`exposed/cache` owns only engine-neutral contracts: snapshot envelope and
mapper, snapshot configuration, store identity, mutation/report models, the
cache-only store SPI, failure observation, and the shared transaction registry
algorithm. Its main source set does not add an R2DBC dependency and exposes no
public `JdbcTransaction` or `R2dbcTransaction` signature.

The three existing backend modules already depend on both `exposed/cache` and
their matching Exposed engine. They own the concrete facades and exact public
factories. Facade constructors remain internal so compatibility fingerprints
cannot be omitted. Public non-inline factories take explicit `KClass` tokens;
reified conveniences delegate to these public functions and therefore require
no `@PublishedApi` construction path:

```kotlin
fun <ID : Any, V : Serializable> jdbcCaffeineSnapshotCache(
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: CaffeineSnapshotCacheConfig,
        valueSizer: SnapshotValueSizer<V>? = null,
        validator: CacheSnapshotValueValidator<V> =
            rejectDirectEntitySnapshotValues(),
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): JdbcCaffeineSnapshotCache<ID, V>

fun <ID : Any, V : Serializable> r2dbcCaffeineSnapshotCache(
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: CaffeineSnapshotCacheConfig,
        valueSizer: SnapshotValueSizer<V>? = null,
        validator: CacheSnapshotValueValidator<V> =
            rejectDirectEntitySnapshotValues(),
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): R2dbcCaffeineSnapshotCache<ID, V>

fun <ID : Any, V : Serializable> jdbcRedissonSnapshotInvalidator(
        redissonClient: RedissonClient,
        codec: SnapshotRedissonCodec<ID>,
        idType: KClass<ID>,
        valueType: KClass<V>,
        config: JdbcRedissonSnapshotInvalidatorConfig,
        failureBuffer: SnapshotCacheFailureBuffer =
            snapshotCacheFailureBuffer(),
    ): JdbcRedissonSnapshotInvalidator<ID>

data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

fun JdbcRedissonSnapshotInvalidator<*>.quotaHealth(): SnapshotInvalidationQuotaHealth
```

The supplied `SnapshotRedissonCodec` instance must configure the existing
repository map exactly, so repository access and invalidation use the same
library-owned map-key bytes; its delegate type, `codecVersion`, and `ID`/`V`
tokens enter the remote compatibility fingerprint. Two same-class codecs with
different configuration tokens are incompatible. `ExposedRedissonCodecSafety`
applies the trusted-binary gate.
The Redisson client and codec are caller-owned. The invalidator starts no
thread, executor, scheduler, or coroutine and is not `AutoCloseable`. The
library-owned bounded failure buffer performs only nonblocking `offer`; caller
code polls or drains it outside transaction and Redisson event-loop callbacks.
Caffeine facades likewise own no closeable resource. Caffeine facades expose
`storeId`, their `failureBuffer`, and
`lookup(id): SnapshotCacheLookup<ID, V>`; the Redisson invalidator exposes only
`storeId`, its `failureBuffer`, structural shared-quota health, and no read or
snapshot-publication method. A caller-supplied shared buffer remains the same
exposed instance.

`rejectDirectEntitySnapshotValues()` is classpath-safe: it resolves the Exposed
DAO base class by name only when DAO is present and performs an assignability
check without a static DAO type reference. DAO-free consumers therefore do not
get `NoClassDefFoundError`. Nested graphs remain the caller contract described
above.
The Redisson factory accepts only the wrapper's scalar identifier policy. On
each stage the wrapper encodes the ID, records the byte count plus SHA-256, and
releases the temporary buffer. It rejects a key exceeding
`maxEncodedKeyBytes` and atomically enforces the per-store
`maxCommitEncodedKeyBytes` total before changing the registry. The measured
size is retained with the ID so commit-time batches stay within
`maxBatchEncodedKeyBytes`.

During the actual Redisson call, the same wrapper's library-owned map-key
encoder re-encodes each immutable scalar ID, compares length/hash with the
staged measurement, and enforces the active chunk's actual byte budget before
returning bytes to Redisson. A mismatch is an internal invariant failure: the
new buffer is released and that chunk fails before network submission while the
coordinator continues later chunks. Caller codecs cannot inject a
nondeterministic map-key encoder. Thus the configured caps cover bytes actually
handed to Redisson, not only an estimate.

The JDBC Caffeine module provides these transaction extensions:

```kotlin
fun <ID : Any, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V>

fun <ID : Any, S, V : Serializable> JdbcTransaction.stageSnapshot(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V>

fun <ID : Any, V : Serializable> JdbcTransaction.stageInvalidation(
    cache: JdbcCaffeineSnapshotCache<ID, V>,
    id: ID,
)
```

`r2dbc-caffeine` declares the same three signatures with
`R2dbcTransaction`/`R2dbcCaffeineSnapshotCache`:

```kotlin
fun <ID : Any, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
): CacheSnapshot<V>

fun <ID : Any, S, V : Serializable> R2dbcTransaction.stageSnapshot(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
): CacheSnapshot<V>

fun <ID : Any, V : Serializable> R2dbcTransaction.stageInvalidation(
    cache: R2dbcCaffeineSnapshotCache<ID, V>,
    id: ID,
)
```

`jdbc-redisson` exposes only:

```kotlin
fun <ID : Any> JdbcTransaction.stageInvalidation(
    invalidator: JdbcRedissonSnapshotInvalidator<ID>,
    id: ID,
)
```

Successful staging returns the accepted snapshot so the miss path does not map
twice. Compile-checked English and Korean examples use these final names; there
is no optional API shape. Snapshot extensions verify current/root transaction,
`maxAttempts == 1`, and facade ownership, then atomically claim the miss before
mapping or registry mutation. A mapping/staging failure consumes that token;
retry repeats the entire lookup plus single-attempt transaction from an
application-owned outer loop so it cannot publish across transaction attempts.

The common module declares the following public opt-in boundary so adapter
modules can call it without Kotlin `internal` leakage:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class InternalSnapshotCacheApi

@InternalSnapshotCacheApi
interface SnapshotTransactionBridge<TX : Transaction> {
    fun isRoot(transaction: TX): Boolean
    fun isCurrent(transaction: TX): Boolean
    fun maxAttempts(transaction: TX): Int
    fun registerInterceptor(
        transaction: TX,
        interceptor: StatementInterceptor,
    )
}

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    snapshot: CacheSnapshot<V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V>

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, S, V : Serializable> stageMappedSnapshotMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    miss: SnapshotCacheMiss<ID, V>,
    source: S,
    mapper: CacheSnapshotMapper<S, V>,
    validator: CacheSnapshotValueValidator<V>,
): CacheSnapshot<V>

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: AsyncSnapshotInvalidationStore<ID>,
    id: ID,
)
```

JDBC and R2DBC singleton bridges implement `isRoot` with
`outerTransaction == null` and delegate registration to the concrete
transaction's `registerInterceptor`. `isCurrent` compares the receiver with the
engine's current JDBC transaction or R2DBC coroutine-context transaction; every
public snapshot extension also requires `maxAttempts == 1` before claiming its
one-shot miss token. Invalidation staging does not carry a miss token and can
participate in ordinary Exposed retries because each attempt has its own
transaction registry. The common coordinator's public-but-opt-in
staging entrypoints accept this bridge, the transaction object, store, miss
capability, snapshot or source/mapper, and validator. They claim before mapping;
only adapter-module extensions call them. Consumers do not
implement or call the bridge. No new Gradle module, annotation dependency, or
cross-engine dependency is introduced.

### Transaction registry and coordinator

The shared coordinator stores one registry under a private Exposed transaction
user-data key. It is not keyed by facade instance. The registry is an
insertion-ordered map keyed by `SnapshotStoreId` and entity identifier. The
first store registered for a `SnapshotStoreId` becomes the drain target and
records its opaque instance token plus non-secret compatibility fingerprint.
Another facade using the same logical identity in that process must have the
same token by reference identity (`===`) and the same fingerprint or staging
fails before buffer mutation. Every store creates one private token object;
value-equal tokens never establish store identity.
This prevents two distinct local Caffeine instances with the same namespace
from silently updating only the first cache. Within one registered store,
mutations preserve last-mutation-wins semantics:

- put then put: publish the latest snapshot;
- put then invalidate: invalidate;
- invalidate then put: publish the snapshot.

Only the first staged mutation in a root transaction registers an interceptor.
All stores in that transaction drain from the same registry. JDBC and R2DBC
bridges share mutation and error-handling logic without leaking engine types
from `exposed/cache`.

The transaction user-data key points to a state object, while a weak identity
guard protected by an explicit lock maps the transaction object to the same state without a
strong back-reference. The interceptor's non-throwing `beforeCommit` changes
`OPEN` to `BOUNDARY_STARTED` and moves the coalesced buffer into its private
pending field before Exposed clears user data. `beforeRollback` marks the state
terminal and clears active/pending payloads. `afterCommit` moves pending data to
a local drain value and clears the state before any cache call;
`afterRollback` clears defensively. The weak terminal guard remains until the
transaction is garbage-collected and contains no snapshot payload after a
normal callback sequence.

This design intentionally supports one physical boundary per participating
transaction object. The bridge also verifies that the receiver is the current
Exposed transaction. Staging after `BOUNDARY_STARTED`, from a captured
non-current transaction, or from any commit/rollback callback fails
before mapping or buffer mutation. Manual repeated `commit()`/`rollback()` on a
participating transaction is unsupported; tests cover commit-then-stage,
rollback-then-stage, callback-time staging, and interceptor accumulation. A
first snapshot call after an earlier manual commit on a still-current reusable
Exposed object is outside the supported contract, so documentation requires the
snapshot API to be used only inside the ordinary single-boundary
`transaction {}`/`suspendTransaction {}` scope.

`SnapshotCacheConfig.maxStagedMutations` must be positive. The transaction-wide
effective limit is the smallest limit among participating stores. Replacing an
existing identity/id mutation is allowed at the limit. Adding a new identity/id
beyond the limit throws before commit and leaves the existing registry
unchanged. This bounds retained entry count and post-commit fan-out work
without silently dropping consistency work; optional weight limits provide the
separate byte/heap guard when configured.

`maxParticipatingStores` is also positive and defaults to eight. The smallest
participating-store value is enforced before registering a new identity, which
bounds per-transaction phase fan-out and aggregate async submission work.

Participant registration, effective count/weight-limit recomputation, and the
candidate mutation form one prechecked atomic state transition. If a newly
introduced store lowers a limit below `existing + candidate`, the coordinator
rejects the candidate without registering that store or changing the previous
effective limit or buffer. Replacement weight is calculated as
`totalWeight - oldMutationWeight + newMutationWeight`; rejection preserves the
old mutation and total exactly.

When `afterCommit` is reached, draining has three phases independent of
registration order:

1. partition every measured Redisson invalidation into encoded-byte-bounded
   chunks and attempt admission/submission for every chunk from every
   invalidator without awaiting any result;
2. apply final in-process Caffeine invalidations;
3. apply final in-process Caffeine snapshot PUTs.

All Redisson futures are offered before local work, so no backend is awaited
while the Exposed transaction/connection remains in its callback. A saturated
quota shared by invalidators on the same `RedissonClient` can reject later
chunks across those facades; the contract guarantees non-waiting progress, not
per-facade fairness under saturation. Completion callbacks retain only
structural counts; only non-success results perform a bounded failure-buffer
`offer`. Successful completion releases quota and updates structural health
without occupying failure-buffer capacity. No future is
awaited or cancelled from the transaction callback. The adapter reuses
Redisson's client event loop and creates no executor, scheduler, coroutine, or
durable background worker.

The coordinator submits each prepared chunk independently, catches an immediate
submission exception, records that chunk as failed, and continues submitting
all remaining chunks/stores. Actual re-encoding and chunk validation happen
before quota admission. A successful admission returns an exactly-once lease;
if submission fails before a future exists, the lease is released immediately.
Once a future exists, ownership transfers to its completion callback, which
releases the lease in `finally` after recording the structural result. Encoding
or admission rejection creates no lease. Repeated synchronous failures
therefore cannot leak quota.

A weak identity registry protected by an explicit lock holds one quota per `RedissonClient`. The first
factory pins `maxOutstandingChunks` and `maxOutstandingEncodedBytes`; every
later facade for that exact client must provide identical values or factory
construction fails before map access or staging. Before each command the
coordinator atomically reserves one chunk and its actual encoded bytes; quota
exhaustion skips submission and offers one sanitized `REJECTED` result. A
never-completing client therefore closes the quota instead of accumulating
unbounded commands or retained buffers across transactions. Factory
documentation requires a finite Redisson command timeout and retry policy no
greater than five seconds and verifies it in integration fixtures.

The facade exposes shared structural quota health: configured and outstanding
chunk/byte counts, rejection count, and whether admission is saturated. It
contains no identifiers or Redis endpoint data. If quota remains saturated
beyond the configured Redisson command timeout, operators quiesce new staging,
close the affected client so its futures complete exceptionally, verify quota
returns to zero, drain the failure buffer, and create a new client plus
facades. The failure buffer is a library concrete type backed by a bounded
queue; completion paths call only nonblocking `offer`. Saturation increments
its sanitized `droppedCount`. `drainTo` invokes the caller observer only on the
thread that explicitly calls `drainTo`, outside transaction and Redisson
callbacks. It removes an element before delivery. An observer exception consumes
that failing element, increments `observerFailureCount`, stops the drain, and
returns a `SnapshotCacheDrainResult` with one observer failure and only the
exception type. `droppedCount` remains queue-saturation loss only; delivered,
observer-failed, remaining, and dropped counts are never conflated. The buffer
is diagnostic, not durable repair storage.

Each Caffeine facade owns a fixed power-of-two array of lock/generation-token
stripes. A cache-miss lookup captures an opaque capability that privately binds
the store owner, logical identifier, stripe, and generation before the database
load. The capability is a regular non-serializable class with an internal
constructor and no data-class copy/component surface. After commit, a PUT asks
the owning registry to acquire the stripe lock and applies only when the owner
and generation still match by identity and the identifier matches the captured
identifier; it replaces the token with a new private object and performs
`cache.put` before releasing the lock. Invalidation acquires the same stripe,
replaces the token unconditionally, and invalidates before unlock. Therefore an
older transaction callback cannot publish after a newer PUT or invalidation on
the same stripe. Identity tokens cannot numerically wrap. Hash collisions may
conservatively
skip an unrelated PUT but cannot be used to retarget a capability to the
colliding identifier, cannot expose stale data, and the fixed stripe array
does not create an unbounded tombstone map. The captured generation is internal
metadata and is not serialized or distributed. A fence mismatch skips the PUT
and offers one structural `REJECTED` result; the next read remains a safe cache
miss.

Every store report crosses the coordinator boundary through
`requireReconciled(operation, expectedCount)`. It rejects negative expectations,
wrong-operation results, and under/over-counts using `Long` accumulation so an
`Int` overflow cannot make a malformed report appear valid.

The local phases share a monotonic `SnapshotCacheDeadline` derived from the
smallest `localDrainBudget`. This is a cooperative budget: built-in Caffeine
stores poll it before each entry, mark the remaining work `NOT_ATTEMPTED`, and
report an overrun if a single cache operation/listener returns after expiry.
The synchronous SPI cannot preempt arbitrary user code, so the design does not
claim a hard callback-latency bound. Every report reconciles exactly to its
phase input count by operation and outcome.

## Transaction Lifecycle

### Commit

1. The application maps transaction-bound state to an immutable snapshot.
2. `stageSnapshot` or `stageInvalidation` records the mutation in the current
   transaction buffer.
3. Exposed commits the database transaction.
4. `afterCommit` detaches and clears transaction state.
5. The coordinator submits all bounded distributed invalidations without
   waiting, then cooperatively drains local invalidations and PUTs.
6. Async completion observers report structural outcomes without retaining the
   transaction or snapshot payload.

The cache mutation happens after the database commit. Another process can read
old cache data during the short commit-to-cache window, so this feature provides
commit safety rather than distributed atomicity.

### Rollback

`beforeRollback` marks the state terminal and clears the transaction buffer
without invoking the store; `afterRollback` repeats cleanup defensively.
Neither an explicit rollback nor an exception-triggered rollback can expose a
staged snapshot.

The buffer key is not preserved across rollback. A normal callback sequence
leaves only the payload-free weak terminal guard until transaction collection.

### Retry and nesting

Exposed transaction retries create a new transaction object. Invalidation-only
work is transaction-local, so a failed attempt cannot leak mutations into a
later attempt. Snapshot fill is deliberately single-attempt: its extension
checks `maxAttempts == 1` before consuming the pre-read miss capability.
Applications that retry a read fill repeat `lookup` and the whole
single-attempt `transaction`/`suspendTransaction` in an outer policy. This keeps
the fence capture before each attempt's database read instead of reusing stale
input in Exposed's internal lambda replay.

Nested work that reuses the same Exposed transaction shares the same buffer.
When Exposed creates a savepoint-backed nested transaction with a non-null
`outerTransaction`, `stageSnapshot` and `stageInvalidation` fail before changing
either buffer. A nested transaction's `afterCommit` means only savepoint success, not
physical connection commit, so publishing from it could expose data that the
outer transaction later rolls back. Applications stage from the root
transaction after nested work returns successfully.

## Read-Through Usage

The coordinator does not open transactions. A caller follows this pattern:

1. call the backend facade's `lookup(id)`;
2. return immediately when `snapshot` is present, or retain the one-shot
   `miss` token;
3. for a miss, load the row or DAO inside the caller-owned Exposed transaction;
4. call the source-plus-mapper `stageSnapshot` overload with that token;
5. unwrap the returned `CacheSnapshot.value` for the caller;
6. use `revision` only for application diagnostics or comparison;
7. allow the cache to populate only after commit.

Write paths stage invalidation so the next read reloads canonical database
state. A snapshot PUT is a read-miss fill and requires its pre-read
`SnapshotCacheMiss`
token; write paths cannot manufacture a bare-id PUT.

No API silently falls back to an immediate cache write when no transaction is
available. The active transaction is the extension receiver, which makes the
boundary explicit and compile-checkable.

The JDBC documentation fixture uses the final shape:

```kotlin
val lookup = orderSnapshots.lookup(orderId)
lookup.snapshot?.let { return it.value }
val miss = requireNotNull(lookup.miss)

return transaction {
    maxAttempts = 1
    val row = Orders.selectAll().where { Orders.id eq orderId }.single()
    stageSnapshot(orderSnapshots, miss, row) { source ->
        CacheSnapshot(
            value = OrderSnapshot(
                id = source[Orders.id].value,
                lines = source[Orders.lines].toList(),
            ),
            revision = source[Orders.version].toString(),
        )
    }.value
}
```

The R2DBC fixture uses the identical body inside `suspendTransaction`; write
examples call `stageInvalidation(cache, id)` after the database mutation. A
negative compile/documentation fixture shows that the extensions are available
only on the matching active transaction type, and rollback examples prove that
the returned application value does not imply early cache visibility.

## Failure Semantics

Database failure and cache failure are different outcomes.

- Mapping or staging failure occurs before commit and propagates normally.
- Rollback discards all staged mutations.
- A non-fatal store failure in `afterCommit` is offered to the bounded failure
  buffer with store identity, operation, outcome, affected count, and exception
  type. Values, identifiers, credentials, SQL, URLs, and serialized snapshots
  are never retained or logged.
- A store failure does not attempt database rollback or repeat the committed DB
  operation. Each failed distributed operation group is reported once with its
  affected count and is not decomposed into an unbounded per-key retry loop.
- Local stores isolate ordinary per-entry failures so one bad entry does not
  prevent unrelated mutations. Fatal JVM errors are not swallowed.
- Store `Exception`s, including `CancellationException` observed by this
  coordinator after physical commit, never escape its callback. They are
  offered as post-commit cache failures and cannot change the committed
  database outcome.
  A cancellation observed before commit follows ordinary transaction rollback
  and publishes nothing. No coroutine is launched by the synchronous callback.
- Cancellation, connection loss, or driver failure while an R2DBC physical
  commit is in progress has an unknown database outcome. It is not described as
  rollback. Because Exposed does not invoke `afterCommit` until the awaited
  commit completes, the coordinator publishes no snapshot in this state. The
  cache may remain stale and recovers through normal miss/reload or an
  application-owned reconciliation path. The cache buffer receives no event
  because no cache callback ran; the caller receives the commit/cancellation
  exception and owns any unknown-commit monitoring.
- JVM-fatal `Error`s are not converted into cache health events and may escape;
  this is the only allowed throwing path after commit.

Exposed invokes registered interceptors sequentially and does not isolate each
callback. If an earlier third-party `afterCommit` interceptor throws after the
physical commit, this coordinator's `afterCommit` may never run: no cache
mutation or cache failure event is emitted, cache state remains stale, the
caller observes the third-party exception, and pending values remain only until
the transaction object is collected. If an earlier rollback callback prevents
this interceptor, no cache mutation occurs; `beforeRollback` normally already
cleared payloads. The library cannot convert another interceptor's exception or
guarantee callback ordering. JDBC/R2DBC tests place a throwing interceptor
before this one and lock these safe stale-cache/retention outcomes.

The default usage drains the buffer to the logging observer from an
application-owned maintenance/health task. Applications needing durable cache
repair must use an application-owned outbox or repair queue; the bounded buffer
is not that queue.

The observer contract receives sanitized structural context rather than the
snapshot or identifier:

```kotlin
fun interface SnapshotCacheFailureObserver {
    fun onFailure(failure: SnapshotCacheFailure)
}

fun loggingSnapshotCacheFailureObserver(): SnapshotCacheFailureObserver

data class SnapshotCacheFailure(
    val storeId: SnapshotStoreId,
    val operation: SnapshotCacheOperation,
    val outcome: SnapshotCacheOutcome,
    val affectedCount: Int,
    val exceptionType: String? = null,
)
```

The observer receives only bounded, low-cardinality structural data.
`storeId.namespace` is an operator-authored static namespace, not an entity key.
`affectedCount` is an event measurement and must never be used as a metrics tag.
The raw throwable remains internal to the coordinator logging path, which logs
the type without its message, stack rendering, suppressed exceptions, or cause
chain because backend exceptions may contain URLs, credentials, or keys.

A custom observer runs only in an explicit `failureBuffer.drainTo` call. If it
throws an `Exception`, the sanitized structured drain result reports the
consumed failure and stops the current drain; it cannot affect a transaction
callback or async Redisson completion. JVM-fatal `Error`s retain the ordinary
caller-thread policy.

The JDBC Redisson invalidator attempts every byte-bounded async chunk and
returns without waiting. Admitted chunks are submitted; rejected chunks are
recorded and do not stop later attempts. Only failed completion, synchronous submission failure, or
admission rejection is offered to the failure buffer; a never-completing future
remains visible through shared quota health and is bounded by quota rather than
a library timeout task. A late
invalidation can cause an extra miss but cannot resurrect stale data. No retry,
compensating mutation, distributed `get`, or distributed PUT is added by this
feature. Caffeine reads are in-process; a non-fatal local read exception follows
the same fail-open-as-miss and failure-buffer rule.

## Consistency and Cross-Node Rules

- The database is the source of truth.
- `revision` is metadata for comparison, diagnostics, or backend-specific
  conditional logic; the core does not order opaque revisions.
- Last mutation wins within one transaction buffer. Local Caffeine callbacks
  are ordered by the bounded stripe fence, which may conservatively reject a
  stale or colliding PUT; no total order is claimed between nodes.
- For concurrent writers, commit-time invalidation is the safe default because
  the next cache miss reloads canonical state.
- Distributed adapters in this issue publish invalidations only; no opaque
  revision is treated as a cross-node fence.
- The Redisson invalidator uses `INVALIDATE` synchronization so peer nodes
  receive key invalidations without snapshot broadcast. It rejects `NONE` and
  `UPDATE`.
- A Redisson reconnect clears the dedicated local snapshot cache before serving
  local hits, avoiding stale state after missed invalidations.
- Lettuce must retain its existing near-cache invalidation channel and TTL
  behavior.
- The public distributed integration accepts only library-policy-validated
  scalar identifiers and therefore cannot expose or serialize `EntityCache`,
  transaction handles, DAO instances, composite graphs, or lazy relationship
  state as distributed values. Local snapshot callers
  remain responsible for the documented detached-value contract.

### Namespace and rollout contract

`SnapshotStoreId.backend` is a bounded library constant such as `caffeine` or
`redisson`. `namespace` is required to match
`[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`, is explicitly operator-owned, and must
never contain tenant, request, or entity identifiers. Distributed deployments
use a versioned namespace such as `orders-snapshot:v1`; the backend, namespace,
codec/snapshot type fingerprint,
and synchronization mode jointly identify one compatible store. Reusing a
namespace with an incompatible fingerprint fails during facade construction or
first same-transaction registration rather than mixing values locally.

Redisson additionally stores the fingerprint under a reserved remote metadata
key derived from the namespace, using a string codec and an atomic
claim-or-compare script during facade creation. If metadata is absent but the
remote map already contains entries, construction fails closed rather than
claiming an unknown legacy format. The script claims the marker only when both
metadata and map are absent, or accepts an exact existing marker. Creation is
bounded by `namespaceVerificationTimeout` and fails closed on timeout,
connection failure, or a mismatch; it does not serve traffic with an
unverified namespace. The metadata
key has no TTL and is deleted only with the corresponding namespace during the
documented operational cleanup. This makes incompatible codec or value-type
reuse detectable across processes rather than only inside one transaction.

The canonical fingerprint input is UTF-8, line-delimited, field-name-sorted
`bt4k-snapshot-fingerprint/v1` data containing backend, namespace, key raw class,
snapshot raw class, required application `schemaVersion`, codec class, and sync
strategy, plus the required `codecVersion` and canonical key-encoding
identifier. Local maximum size, TTL, max-idle,
and other near-cache tuning are not
serialized-format fields and are excluded from the remote marker. Its stored
form is lowercase SHA-256 hex.
Connection endpoints, usernames, credentials, and arbitrary `toString()` output
are forbidden. The explicit schema token covers nested generic/schema meaning
that JVM raw class tokens cannot express.

For a format change, operators deploy readers and writers configured for `v2`,
warm or naturally repopulate `v2`, cut all nodes over, stop `v1` writers, wait
for in-flight request drain, and then explicitly delete the `v1` remote map,
near-cache state, and metadata key. Redisson local-cache TTL/max-idle does not
expire remote map entries and is never used as proof that cleanup is complete. A
rollback stops `v2` writers, quiesces traffic, clears the retained `v1` remote
map and every node's local view while retaining/revalidating its fingerprint
marker, then switches every node to an empty `v1` so reads rebuild from the
database. Only after verified reads may operators clean up `v2`. Retaining `v1`
does not mean its data remains fresh while `v2` is active. Mixed-version nodes
must not share an unversioned namespace.

`jdbc-redisson` also exposes a bounded, idempotent administrative helper:

```kotlin
fun <ID : Any> clearSnapshotNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult
```

It is called only after operators remove the namespace from every live client
and drain traffic. It verifies the expected marker, deletes remote map data
before the marker, clears the caller's local view, and verifies absence within
the one total timeout. It is safe to rerun after partial failure: map-absent and
marker-present resumes with marker deletion; marker-absent and map-present
fails closed. Rollback preparation uses a separate `clearMapRetainingMarker`
operation with the same quiescence, timeout, and verification rules. Neither
helper guesses client quiescence or runs automatically.

Both helpers are marked delicate administrative APIs. The non-secret
fingerprint prevents accidental format deletion but is not authorization.
Callers require a dedicated Redis ACL identity allowed to inspect/unlink only
the target namespace and must never expose these helpers through request-facing
paths. Large-map removal uses Redis asynchronous unlink semantics rather than a
blocking delete. The client timeout bounds acknowledgment/verification only;
it cannot cancel server-side cleanup already accepted, and rerun inspection
resumes safely. Operational guidance requires network isolation, alerts/rate
controls for repeated invalidations, and sufficient database load shedding:
an untrusted namespace writer cannot inject snapshots through this API but can
force peer evictions and cache-miss amplification.

## Initial Integration Scope

Required in this issue:

- engine-neutral snapshot envelope/mapper/configuration, cache-only SPI,
  mutation/report model, bounded failure buffer, and registry algorithm in
  `exposed/cache`;
- concrete interceptor lifecycle bridges and compile-checked transaction
  extensions in `jdbc-caffeine`, `r2dbc-caffeine`, and `jdbc-redisson`, with no
  new Gradle module or cross-engine dependency;
- direct Caffeine store adapters with fixed-size striped commit-order fences for
  JDBC and R2DBC Caffeine modules;
- a JDBC Redisson invalidator using the required scalar-policy codec wrapper and a writer-free
  `RLocalCachedMap` under a dedicated versioned namespace; it reuses
  `ExposedRedissonCodecSafety`, defaults `trustedBinaryCache=false`, validates
  the key/value codec path, registers remote format metadata, requires
  `INVALIDATE` plus reconnect `CLEAR`, and exposes no distributed get/put;
- focused fake-store tests for core lifecycle, ordering, failure isolation, and
  two-node invalidation semantics;
- JDBC and R2DBC integration tests proving commit-only update and rollback
  discard;
- a sequential Redisson integration test proving a committed invalidation is
  observed by a second near-cache client and rollback publishes nothing;
- English and Korean cache/backend README documentation explaining Exposed
  `EntityCache` versus repository snapshot near-cache, exact API names,
  quota/failure-buffer behavior, recovery, and namespace rollout;
- KDoc for all public contracts.

Conditional in this issue:

- add a JDBC Lettuce adapter only if current source inspection proves it
  can submit nonblocking invalidation after commit, bypass every DB writer, and
  preserve the existing peer invalidation protocol without a new dependency or
  blocking bridge.

Explicitly deferred:

- async adapters that require `runBlocking`, detached coroutines, or a new
  background worker;
- R2DBC Redis/Redisson/Lettuce adapters that would perform blocking network I/O
  from the synchronous Exposed commit callback;
- Ktor operational routes and metrics (#325);
- example application composition (#326).
- paired `docs/manual/{en,ko}` feature pages until the stable manual baseline is
  advanced from its currently pinned 1.11.0 tree to 1.12.0; the 1.12 release
  checklist must add and parity-validate the pages before release. This feature
  PR must not place develop-only APIs into the stable 1.11 manual.

## Testing Strategy

### Core tests

- staged put is invisible before commit and visible after commit;
- rollback invokes no store mutation;
- put/put, put/invalidate, and invalidate/put are last-mutation-wins;
- different identifiers preserve deterministic order;
- repeated mutations through the same facade coalesce, while a second store
  instance reusing its logical `SnapshotStoreId` is rejected even if the
  fingerprint matches;
- store identity uses private token reference equality, including a regression
  with equal-but-distinct token objects;
- only one interceptor is registered per root transaction;
- only the coordinator buffer key survives Exposed's pre-`afterCommit`
  user-data cleanup, and the key is removed before store application;
- different transactions do not share buffered state;
- failed invalidation attempts do not leak into Exposed's successful retry;
  snapshot-fill retry reacquires a miss token in an outer single-attempt loop;
- savepoint-backed nested transactions are rejected before buffer mutation;
- nested-commit/outer-rollback, nested-rollback/outer-commit, and full root
  commit tests prove no nested callback publishes early for JDBC and R2DBC;
- one store failure does not skip independent later mutations;
- top-level DAO `Entity` values are rejected before buffer mutation, and a
  custom value validator can reject application-specific mutable/oversized
  graphs;
- `maxStagedMutations` accepts replacement at the limit and rejects a new key
  at limit plus one without changing the existing buffer;
- a many-key transaction coalesces before operation-phase partitioning and
  invokes local stores at most once per non-empty phase;
- the transaction-wide entry limit uses the smallest participating-store limit
  and does not claim a byte-level bound;
- one cooperative local-drain budget spans multiple local stores; expired work
  is `NOT_ATTEMPTED`, overruns are observed, and counts reconcile to input;
- all distributed/local invalidations run before any snapshot PUT regardless
  of registration order; every remote chunk gets an admission/submission
  attempt first and a stalled local PUT cannot suppress peer invalidation;
- immutable composite IDs retain stable map semantics, and documentation
  rejects mutable identifier types;
- the default direct-Entity validator loads and works with Exposed DAO absent
  from the runtime classpath;
- failure-buffer entries and drained observers expose no snapshot payload;
- an exception containing a URL, credential, and key contributes only its
  exception type to the public failure buffer;
- buffer saturation is a nonblocking drop with a structural counter; a throwing
  custom observer consumes one event and returns distinct delivered/failed/
  remaining counts from explicit `drainTo`, while post-commit
  `CancellationException` does not escape or change the committed result;
- a preceding throwing JDBC/R2DBC interceptor can skip this coordinator's
  after-callback; cache state remains unchanged and retained payload is bounded
  by transaction lifetime;
- participating transaction objects reject manual boundary reuse,
  callback-time staging, and post-boundary staging without accumulating
  interceptors;
- captured non-current JDBC and R2DBC transaction receivers are rejected by the
  exact bridge check before mapping;
- a later lower-limit participant is rejected atomically without changing the
  previous registry, limits, or buffer;
- weighted replacement uses subtract-old/add-new arithmetic at the exact limit
  and preserves the old mutation on rejection;
- a fake R2DBC commit-boundary seam, when supported by the current transaction
  fixture, proves cancellation during the awaited physical commit publishes no
  snapshot and emits no cache event rather than reporting rollback; if the seam is
  unavailable, bytecode/source evidence and a focused contract test document
  the limitation;
- a fake two-node store proves committed invalidation removes stale peer state;
- an attempted example using an Exposed DAO object is absent from the public
  value contract and documentation fixtures use immutable serializable records.

### JDBC Caffeine integration tests

- a transaction stages an immutable `ResultRow`-derived snapshot;
- commit populates Caffeine only after DB success;
- rollback leaves both local cache and peer test observer unchanged;
- staged invalidation removes a pre-existing snapshot after commit;
- ordinary repository write modes are not invoked by the cache-only adapter.
- `SnapshotCacheConfig` and `CaffeineSnapshotCacheConfig` have no write mode and
  no `LocalCacheConfig` overload is exposed; Redisson accepts no Caffeine-only
  tuning.
- configured value sizing enforces per-value validation, Caffeine
  `maximumWeight`, and staged retained-byte limits; an oversized nested DTO is
  rejected before buffer mutation.
- a late PUT staged by an older transaction cannot repopulate after a newer
  transaction's PUT or invalidation; a stripe collision may skip an unrelated
  PUT but never exposes stale data, and fence storage remains fixed-size.
- a miss token captured before a long DB read is rejected if a newer
  invalidation finishes before staging/commit; no bare-id PUT or token reuse is
  available to bypass that ordering.
- snapshot fill rejects `maxAttempts > 1`; an application-owned outer retry
  repeats lookup plus a fresh single-attempt transaction, and a failed first
  transaction followed by a fresh-token second transaction commits exactly one
  snapshot.
- opaque miss tokens expose no ID/fence getter or data-bearing `toString`, are
  not serializable, disappear from the weak registry on claim/GC, and fail
  lookup before DB work at the configured outstanding-token bound.
- mapped staging claims and removes the miss before invoking the mapper; mapper
  failure leaves no reusable capability or registry mutation.

### R2DBC Caffeine integration tests

- `suspendTransaction` uses the same commit-only semantics;
- rollback and coroutine cancellation publish no dirty snapshot;
- cache callback performs no `runBlocking` or detached coroutine work;
- staged invalidation removes a pre-existing snapshot after commit.
- the final R2DBC extension signatures compile from a consumer fixture and the
  common cache artifact exposes no `R2dbcTransaction` type.
- weighted capacity and oversized-graph rejection match the JDBC facade.
- the same fixed-size stripe fence rejects an older callback after a newer PUT
  or invalidation under concurrent `suspendTransaction` execution.
- R2DBC snapshot fill likewise requires `maxAttempts = 1`; external retry
  reacquires a fresh miss token before the next database read.

### JDBC Redisson integration tests

- the invalidator attaches to a writer-free local cached map under a dedicated
  namespace using the required scalar-policy codec wrapper;
- facade creation atomically creates or compares the remote canonical format
  fingerprint and fails closed on mismatch or timeout;
- oversized encoded IDs and per-commit encoded totals are rejected before
  registry mutation; accepted IDs are partitioned into bounded byte chunks;
- scalar ID policies reject composite/nested objects before codec invocation;
- no distributed String policy exists; documentation/compile fixtures require
  a non-sensitive Long/UUID surrogate and prohibit secret, credential, or PII
  Redis keys;
- Long/UUID canonical key encoders and decoders pass the normative zero,
  negative, and UUID golden vectors; fingerprint fixtures include the encoding
  identifier and reject a changed identifier under the same namespace;
- actual submission re-encoding must match staged length/SHA-256 and the active
  chunk budget; a delegate with nondeterministic general encoding cannot alter
  the wrapper's canonical map-key bytes;
- every invalidator chunk from every store receives an admission/submission
  attempt before local drain; admitted chunks submit, rejected chunks report
  structurally, later attempts continue, and the transaction callback never
  awaits Redis;
- multiple stalled invalidators are never awaited and do not retain the Exposed
  transaction/connection; same-client quota saturation may reject later
  facades without blocking them;
- never-completing futures across many commits saturate the shared client quota,
  bound retained chunks/bytes, and produce `REJECTED` without new submission;
- re-encoding failure, partial synchronous submission failure, and an
  already-shutdown client release each pre-future quota lease exactly once and
  do not suppress later chunks, stores, or local drain;
- same-client factories with different quota limits fail before remote map
  access; matching facades report one shared structural quota state;
- bounded failure-buffer saturation never blocks the Redisson event loop,
  captures no ID/transaction/snapshot, and increments only the dropped counter;
- a high volume of successful completions releases shared quota while leaving
  the failure buffer empty;
- a permanently saturated quota documents and tests quiesce, client close,
  zero-quota verification, buffer drain, and client/facade replacement;
- positive local-cache size and reconnect `CLEAR` are applied without claiming
  that local TTL expires remote map entries;
- Fory, Kryo, and JDK binary codecs are rejected by default; trusted binary
  codec use requires explicit opt-in;
- multi-node `NONE` and `UPDATE` are rejected, `INVALIDATE` propagates no
  snapshot payload, and reconnect clears stale local entries;
- a committed invalidation removes the peer client's stale local entry through
  Redisson's configured sync strategy;
- an older timed-out invalidation completing after a newer cache population can
  cause only a miss and cannot resurrect stale data;
- rollback produces no Redis mutation or peer invalidation;
- no Exposed map writer or database delete hook is invoked;
- namespace collision/fingerprint rejection and the documented v1-to-v2
  rollout/rollback sequence are covered by configuration contract tests;
- same codec class with a different `codecVersion` is rejected;
- cleanup helpers are bounded and idempotent, delete map-before-marker, refuse
  unsafe partial state, and rebuild an emptied v1 before rollback traffic.

### Regression commands

```bash
./gradlew :bluetape4k-exposed-cache:test
./gradlew :bluetape4k-exposed-jdbc-caffeine:test
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test
./gradlew :bluetape4k-exposed-jdbc-redisson:test
./gradlew exportManualModuleInventory
ruby scripts/manual/validate_manuals.rb \
  build/manual/module-inventory.json docs/manual/manifest.yaml
./gradlew detekt
git diff --check
```

Redis/Testcontainers-backed validation remains sequential if a conditional
distributed adapter is added.

Add non-gating benchmarks for one-key and maximum count/weight buffers,
repeated-key coalescing, multi-store phase partitioning, peak drain allocation,
striped lookup/fence contention, Redisson key encoding/chunk submission,
failure-buffer saturation, and timeout/outage connection-hold behavior. CI
assertions use deterministic retained count/weight, submission counts, and
proof that the Exposed callback does not await Redis rather than flaky network
wall-clock thresholds.

## Documentation

Update the English/Korean README pairs for `exposed/cache` and the affected
backend modules. Documentation must state:

- Exposed DAO `EntityCache` is a transaction-local identity map, not an
  application cache;
- only immutable serializable snapshots belong in this cache;
- snapshot fill requires the one-shot miss token captured before the database
  load and a transaction with `maxAttempts = 1`; retry repeats lookup plus the
  whole single-attempt transaction, while write paths invalidate and cannot
  issue a bare-id snapshot PUT;
- commit-safe does not mean DB/cache atomic or crash-durable;
- invalidation is safer than snapshot publication when cross-node ordering is
  not guaranteed;
- post-commit cache failures require application-owned repair if stronger
  guarantees are needed;
- snapshot configuration has no repository write mode;
- Redisson invalidations are submitted after commit without holding the
  transaction for completion; local drain uses a cooperative budget and may
  report not-attempted work or overruns;
- entry limits are not heap limits unless a value sizer, weighted Caffeine
  capacity, and staged-weight ceiling are configured;
- versioned namespaces, mixed-version restrictions, rollout, and rollback
  ordering;
- codec/schema versioning, encoded-key byte caps, Redis ACL/network isolation,
  invalidation amplification risk, and delicate cleanup API restrictions.
- client-wide outstanding chunk/byte quotas, `REJECTED` saturation behavior,
  shared quota-health recovery, exactly-once lease release, and bounded failure
  buffer draining.

The stable `docs/manual/{en,ko}` pages remain pinned to 1.11.0 and are not
changed by this feature PR. Advancing the manual baseline to 1.12.0 is a release
gate: the release checklist must add paired snapshot-cache guidance, set the
exact 1.12.0 ref/commit, and run the manual inventory/parity validators before
publication.

No new diagram is required. The feature is a lifecycle/API contract that is
clearer as a compact commit/rollback example and behavior table; issue #325 will
own the operational topology documentation when routes and metrics are added.

## Compatibility and Operational Rollback

- Existing repository interfaces and write modes remain unchanged.
- The feature is opt-in; existing callers perform no transaction buffering.
- No database schema or serialized existing cache format is migrated.
- New envelopes require a versioned, dedicated namespace to avoid mixing raw
  DTO values with `CacheSnapshot` values.
- Code rollback follows the namespace procedure above: quiesce traffic, empty
  and revalidate the retained previous namespace, switch every node back so it
  rebuilds from the database, then clean up the abandoned new namespace.
  Existing repository caches continue unchanged.

## Failure Modes

1. **Dirty snapshot exposure:** prevented by staging only and applying from
   `afterCommit`; rollback tests prove no mutation.
2. **Database rewrite from callback:** prevented by the cache-only SPI and
   direct adapter tests that bypass repository writers.
3. **Cross-node stale entry:** mitigated by commit-time invalidation as the
   default and reuse of existing backend invalidation protocols.
4. **Post-commit cache outage:** database remains committed; Redisson work is
   submitted without waiting, only non-success is offered to a bounded
   structural buffer, quota health exposes stalled work, and no retry is
   implicit.
5. **Retry/reuse leakage:** prevented by transaction-identity state, a weak
   terminal guard, and fail-fast rejection after the first physical boundary.
6. **R2DBC/JDBC pool blocking:** Redisson futures are all submitted and never
   awaited by the callback; local Caffeine work remains cooperatively budgeted.
7. **Savepoint publication:** prevented by rejecting staging on a transaction
   with a non-null `outerTransaction`; only the root can publish after physical
   commit.
8. **Observer-induced false failure:** prevented by never invoking application
   observers from commit or Redisson callbacks; observers run only in explicit
   buffer drains.
9. **Namespace/codec collision:** prevented by versioned operator-owned store
   identities and a non-secret compatibility fingerprint checked before
   staging.
10. **Unbounded callback latency:** remote outage waits are removed. Local SPI
    work is cooperative and can overrun on arbitrary listener/user code, so
    overruns are reported rather than described as hard-preempted.
11. **Async outage accumulation:** client-wide chunk/byte quotas retain
    reservations until original-future completion; saturation rejects new work
    structurally instead of growing queued commands without bound.
12. **Late local snapshot resurrection:** prevented by a fixed-size striped
    generation fence that orders Caffeine PUT/invalidation under the stripe
    lock and safely turns collisions into misses.

## Acceptance Criteria Mapping

- No DAO `Entity` or `EntityCache` in distributed values: the distributed API
  accepts only identifiers covered by a library-owned scalar policy and the
  required deterministic codec wrapper; local snapshots add classpath-safe
  top-level Entity rejection, a caller-extensible value validator, safe
  examples, and KDoc.
- Commit-only cache update: core, JDBC, and R2DBC commit/rollback tests.
- Rollback non-update: interceptor lifecycle tests and integration tests.
- Nested transaction safety: JDBC/R2DBC savepoint tests reject child staging and
  prove outer rollback cannot expose a child snapshot.
- Stale invalidation: last-mutation-wins tests, cross-transaction local fence
  tests, and a two-node invalidation fake.
- Coexistence with write-through/write-behind: cache-only SPI never calls the
  existing repository `put` path or map writer.
- Local near-cache: Caffeine adapters in JDBC and R2DBC modules.
- Distributed coordination: writer-free JDBC Redisson adapter with a real
  two-client invalidation test; Lettuce remains conditional on proving the same
  nonblocking cache-only boundary.
- Bounded overhead: positive entry-count limits, optional Caffeine retained-byte
  limits, bounded weak miss-capability registries,
  actual-encoding-verified Redisson key/commit/chunk caps, client-wide
  outstanding chunk/byte quotas, a bounded nonblocking failure buffer,
  nonblocking chunk submission, a fixed-size local fence, a cooperative
  local-drain budget, deterministic counts, and expanded
  allocation/encoding/outage benchmarks.
- API/module boundary: compile tests prove exact JDBC/R2DBC extension names,
  lookup/miss-token snapshot fill, snapshot-specific configuration, and no
  engine type leakage from the common cache artifact.
- Documentation distinction: English/Korean README parity and public KDoc now;
  paired manual parity is an explicit 1.12.0 release gate because the stable
  manual remains pinned to 1.11.0.

## Definition of Done

### Independent Review Resolution

The final design was reread independently after all corrections. Every lens
converged with no remaining finding:

| Lens | P0 | P1 | P2 | P3 |
|---|---:|---:|---:|---:|
| Stability and transaction lifecycle | 0 | 0 | 0 | 0 |
| Caller usability and retry behavior | 0 | 0 | 0 | 0 |
| Performance and bounded-resource behavior | 0 | 0 | 0 | 0 |
| Public API and module boundaries | 0 | 0 | 0 | 0 |
| Security and identifier privacy | 0 | 0 | 0 | 0 |
| Operator rollout and outage recovery | 0 | 0 | 0 | 0 |

The review fixed the decisive risks before implementation: Exposed callback
state moves before user-data clearing; snapshot fill uses a pre-read opaque miss
capability and single-attempt transaction; Caffeine uses a bounded striped
commit-order fence; Redisson is invalidation-only with canonical Long/UUID key
encoding, exactly-once quota leases, and no callback wait; application observer
code runs only during explicit bounded-buffer drains; and stable 1.11 manuals
remain untouched until the 1.12 release gate.

- The public API and lifecycle match this design without unresolved
  placeholders or hidden Spring dependencies.
- Every required core/JDBC/R2DBC behavior has a failing-first regression test
  and passes after implementation.
- Targeted Gradle tests, Detekt, and `git diff --check` pass.
- English/Korean documentation is equivalent and uses actual API names.
- Pre-PR and PR review converge at P0=0 and P1=0.
- PR delivery stops at merge-ready state until fresh explicit merge approval.
