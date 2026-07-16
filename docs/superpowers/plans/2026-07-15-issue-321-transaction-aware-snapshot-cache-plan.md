# Issue #321 Transaction-Aware Snapshot Near-Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in JDBC and R2DBC snapshot near-caches whose mutations are staged in the active root Exposed transaction, discarded on rollback, and applied only after commit, with Caffeine local stores and a JDBC Redisson invalidation adapter.

**Architecture:** Put snapshot values, limits, transaction coordination, failure reporting, and engine-neutral cache-only SPIs in `exposed/cache`. JDBC/R2DBC Caffeine modules adapt Exposed transaction lifecycles and use opaque miss capabilities plus striped local ordering fences. JDBC Redisson distributes invalidations only, with canonical key encoding, bounded non-blocking admission, namespace compatibility checks, and explicit recovery/admin APIs. No existing repository write mode is reused and no cache callback performs a database write.

**Tech Stack:** Kotlin 2.3+, JetBrains Exposed 1.3.1 APIs available through the central catalog, Caffeine, Redisson, kotlinx-coroutines, JUnit 5, bluetape4k assertions, Testcontainers, JMH/Gradle benchmarks.

---

## Delivery Contract

- Repository: `bluetape4k-exposed`
- Issue: `#321`
- Base branch: `develop`
- Head branch: `feat/issue-321-transaction-aware-snapshot-cache`
- Pull request: create after all local gates and independent code review pass.
- Merge: stop after reporting the exact merge-ready PR; obtain fresh user approval before merging.
- Scope exclusions: no Spring Boot auto-configuration, no Ktor health route, no schema-drift tooling, no durable outbox, no direct `Entity` caching, no Lettuce adapter in this issue.
- Dependency rule: add no production dependency. Existing module dependency graphs already contain Exposed core/JDBC/R2DBC, Caffeine, Redisson, test fixtures, and benchmark dependencies needed by this plan. Task 9 adds the cataloged `testcontainers-toxiproxy` dependency only to `testImplementation`: `bluetape4k-testcontainers` exposes `ToxiproxyServer` as a public subtype, but its implementation dependency does not put the Toxiproxy container/client API on a consumer's test compile classpath; the direct test dependency is required for the peer-only disconnect fixture and has no published runtime effect.
- Manual rule: update module READMEs and public KDoc, but do not change stable `docs/manual/{en,ko}` content pinned to 1.11.0.

## Acceptance Mapping

| ID | Acceptance criterion | Implementation tasks | Proof |
|---|---|---|---|
| AC-1 | Rollback never exposes staged snapshots | 3, 4, 5, 6 | coordinator and adapter rollback tests |
| AC-2 | Public APIs reject direct Exposed DAO `Entity` values | 1 | runtime rejection and compile-facing API tests |
| AC-3 | Cache callbacks cannot repeat database writes | 2, 4, 6 | cache-only SPI shape and transaction integration tests |
| AC-4 | Repeated mutations are deterministic last-mutation-wins | 3 | replacement/order/limit tests |
| AC-5 | Caffeine protects a newer invalidation from an older fill | 2, 4, 6 | controlled concurrency fence tests |
| AC-6 | Exposed retry attempts cannot reuse stale miss capability | 2, 4, 6 | `maxAttempts` rejection and outer-retry tests |
| AC-7 | Redisson distributes invalidation without Redis snapshot reads/writes | 7, 8, 9 | spy/contract and two-client Testcontainers tests |
| AC-8 | Distributed admission and failures remain bounded/non-blocking | 8 | quota, never-completing future, buffer drain tests |
| AC-9 | Namespace/schema/key encoding incompatibility fails before use | 7, 9 | golden vectors and marker mismatch tests |
| AC-10 | Public API and bilingual documentation are complete | 10 | KDoc compilation and README parity review |
| AC-11 | Existing benchmark module can measure the new path | 11 | benchmark class compilation and bounded smoke run |

## Risk Prediction and Rerun Triggers

| Risk | Preventive design | Rerun trigger |
|---|---|---|
| Exposed clears transaction user data before `afterCommit` | Move the active buffer into interceptor-owned pending state in `beforeCommit` | Any Exposed/catalog upgrade or callback-order change |
| Another interceptor throws before this adapter's `afterCommit` | Hold no strong transaction reference; stale cache is safe and pending state is GC-reclaimable | Interceptor registration/order modification |
| Old DB fill overwrites a newer invalidation | Capture identity generation token at lookup; validate and mutate under one stripe lock | Fence or Caffeine implementation change |
| Exposed automatic retries reuse attempt-local state | Reject `maxAttempts != 1`; document outer retry around the full lookup/transaction cycle | Transaction bridge change |
| Staging limit bypass through replacements or multiple stores | Enforce transaction-wide entry/store minima and replacement weight deltas atomically | Coordinator/store registration change |
| Redisson future or synchronous submission leaks quota | Exactly-once lease release on pre-future failure or completion callback | Quota/submission refactor |
| Redis key codec drift causes cross-node incompatibility | Canonical Long/UUID bytes plus remote marker fingerprint | Codec, schema, or Redisson upgrade |
| Event-loop/thread blocking | No `await`, `get`, scheduler, executor, or worker thread in invalidation submission | Redisson adapter change |
| Testcontainers instability hides a regression | Run Redis integration sequentially and preserve unit-level deterministic proofs | Docker/Redis/Redisson upgrade |
| Stable manuals accidentally point at unreleased APIs | Leave manual sources unchanged and validate pinned inventory | Any docs/manual or manifest diff |

## Repository Hazard Check

- Module registration: not applicable. All implementation lands in existing registered modules; `settings.gradle.kts` must remain unchanged.
- Generated catalogs/checkers: not applicable. No artifact or module is added/moved.
- Broad backend matrix: Redisson integration tests run sequentially. Caffeine tests remain local/in-memory.
- Lettuce: deliberately excluded. Existing JDBC Lettuce access is synchronous and existing suspended/R2DBC access awaits futures; there is no reusable non-blocking peer-invalidation protocol with the lifecycle required by this design. Adding one would be a separate distributed-backend feature.
- Benchmark: extend `benchmark/exposed-benchmark`; do not register another project.

## Task 1: Add immutable snapshot values, validation, and configuration

**Files:**
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshot.kt`
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheConfig.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshotTest.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheConfigTest.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/CacheSnapshotDaoFreeClasspathTest.kt`

- [x] Write failing tests for a serializable immutable DTO envelope, optional revision, schema rejection, positive limits/durations, sizer requirements, payload validator behavior, and top-level `Entity` rejection. Enforce namespace syntax `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`; document (rather than attempt to infer lexically) that it must be an operator-owned static name and never a tenant, request, or entity identifier. Require `fenceStripes` to be a power of two in the exact inclusive range 64..65,536.
- [x] Run `./gradlew :bluetape4k-exposed-cache:test --tests '*CacheSnapshotTest' --tests '*SnapshotCacheConfigTest'` and confirm the tests fail because the API does not exist.
- [x] Implement this public surface with English KDoc on every public declaration:

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

- [x] Provide the exact classpath-safe `rejectDirectEntitySnapshotValues()` default validator and `maximumEstimatedPayloadBytes(sizer, limit)` for opt-in payload rejection. Resolve the DAO base class by name only when present and use assignability without a static DAO type reference; do not recursively reflect over object graphs.
- [x] Add `CacheSnapshotDaoFreeClasspathTest.kt` that launches a child classloader/process without Exposed DAO and proves validator construction/DTO validation do not throw `NoClassDefFoundError`.
- [x] Re-run the targeted tests and confirm they pass.
- [x] Commit with Lore trailers:

```text
Define detached snapshot values before transaction integration

Constraint: Snapshot values must not retain Exposed Entity state
Rejected: Reflection-based deep immutability validation | it is incomplete and expensive
Confidence: high
Scope-risk: narrow
Tested: exposed cache snapshot value and configuration tests
```

## Task 2: Add cache-only SPI, opaque miss capabilities, and local ordering fences

**Files:**
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistry.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStoreTest.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistryTest.kt`

- [x] Write failing tests proving lookup returns exactly one of snapshot/miss; miss objects reveal no ID/fence, are not serializable, have a constant `toString`, and cannot be claimed twice.
- [x] Add concurrency tests using latches/barriers: lookup miss -> concurrent invalidation -> old fill must be rejected; unrelated stripe operations proceed; deliberate stripe collision may reject a safe fill but never permit stale data.
- [x] Run `./gradlew :bluetape4k-exposed-cache:test --tests '*SnapshotCacheStoreTest' --tests '*SnapshotLocalFenceRegistryTest'` and confirm red.
- [x] Implement the engine-neutral surface:

```kotlin
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

@InternalSnapshotCacheApi
fun interface ClaimedSnapshotMiss<ID : Any, V : Serializable> {
    fun prepare(snapshot: CacheSnapshot<V>): SnapshotCacheMutation.Put<ID, V>
}

@InternalSnapshotCacheApi
interface SnapshotCacheStore<ID : Any, V : Serializable> {
    val storeId: SnapshotStoreId
    val storeInstanceToken: Any
    val compatibilityFingerprint: String
    val limits: SnapshotCacheLimits
    @InternalSnapshotCacheApi
    fun claimMiss(miss: SnapshotCacheMiss<ID, V>): ClaimedSnapshotMiss<ID, V>
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

interface SnapshotCacheDeadline {
    fun remaining(): Duration
    val isExpired: Boolean
}

@InternalSnapshotCacheApi
class SnapshotLocalFence<ID : Any> internal constructor()
```

- [x] Keep the ID-bound `SnapshotLocalFence` as an opaque regular class with an internal constructor and no token getters, copy, component, serialization, or structural equality surface. The owning registry alone captures and validates it. Keep the mutation field carrying it behind `@InternalSnapshotCacheApi`; expose no public bare-ID `put` method.
- [x] Implement a weak-identity miss registry protected by an explicit lock and bounded by `maxOutstandingMissTokens`; after expunging stale weak entries, a full registry rejects `lookup` before any transaction or database read. Remove a token before mapper/preparer work and keep claimed preparers one-shot, so mapper failure requires a fresh lookup.
- [x] Implement a fixed power-of-two explicit-lock stripe registry using identity generation tokens. Replace the token and mutate the cache under the same lock.
- [x] Add `SnapshotStoreId`, `SnapshotCacheLimits`, measured invalidation, mutation, deadline, operation/outcome, and report models from the approved design.
- [x] Test bulk apply rather than per-entry SPI calls: each store is invoked at most once per phase, all phase inputs pass `SnapshotCacheApplyReport.requireReconciled(operation, expectedCount)` using overflow-safe `Long` accumulation and reconcile exactly to success/failure/rejected/not-attempted counts, and a shared monotonic deadline can expire between entries.
- [x] Re-run targeted tests, including 100 repeated controlled races.
- [x] Commit with Lore trailers:

```text
Make stale snapshot fills unrepresentable at the cache boundary

Constraint: A lookup capability is valid only for the observed local generation
Rejected: Public put by identifier | it permits stale read-fill races
Confidence: high
Scope-risk: moderate
Tested: opaque miss, bounded registry, and striped fence concurrency tests
```

## Task 3: Add failure reporting and transaction-wide staging coordinator

**Files:**
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheFailure.kt`
- Create: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotTransactionCoordinator.kt`
- Modify: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheFailureTest.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotTransactionCoordinatorTest.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheApiContractTest.kt`
- Create: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/SnapshotCacheCommonApiCompileTest.kt`

- [x] Write failing state-machine tests for root/current transaction checks, nested/captured/post-boundary rejection, single interceptor registration, last-mutation-wins order, rollback discard, before/after commit transfer, callback cleanup-before-cache-work, store/entry/weight limits, and observer failure accounting.
- [x] Add a regression test for an earlier third-party interceptor throwing before this interceptor's `afterCommit`: database completion remains independent, no cache mutation occurs, and the weak transaction entry can be reclaimed.
- [x] Run the two targeted test classes and confirm red.
- [x] Implement a classpath-safe common bridge:

```kotlin
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

@InternalSnapshotCacheApi
fun <TX : Transaction, ID : Any, V : Serializable> stageInvalidationMutation(
    transaction: TX,
    bridge: SnapshotTransactionBridge<TX>,
    store: SnapshotCacheStore<ID, V>,
    id: ID,
)
```

- [x] Declare `@RequiresOptIn(level = RequiresOptIn.Level.ERROR) annotation class InternalSnapshotCacheApi` exactly and add a cross-module compile-facing contract test proving the opt-in `SnapshotCacheLookup.hit/miss` factories are usable by adapters, implementation hooks require explicit opt-in, both local/async invalidation overloads resolve, and common public signatures leak neither `JdbcTransaction` nor `R2dbcTransaction`.

- [x] Store the registry under one private Exposed transaction user-data key and mirror only its state in a payload-free weak terminal guard protected by an explicit lock, with no strong transaction back-reference. Register participants atomically and use the strictest transaction-wide limits among participating stores.
- [x] Reject two facades with the same logical `SnapshotStoreId` unless their private instance token is identical by reference, their caller-supplied failure buffer is identical by reference, and their non-secret compatibility fingerprint matches; reject before buffer mutation.
- [x] In `beforeCommit`, detach the active buffer into interceptor-owned pending state. In `afterCommit`, remove registry/pending state before invoking cache work. In `afterRollback`, clear both states without cache work.
- [x] In non-throwing `beforeRollback`, mark the state terminal and clear active and pending payloads before later rollback interceptors can skip `afterRollback`; make `afterRollback` defensive/idempotent cleanup.
- [x] Preserve insertion order for distinct keys while replacing the effective mutation for the same `(store identity, id)`; apply replacement weight deltas before buffer mutation.
- [x] Drain in exact phases: attempt every distributed chunk admission/submission without awaiting; apply all local invalidations; then apply all local snapshot PUTs. Use one transaction-wide monotonic deadline derived from the smallest local budget, poll before each local entry, mark remaining entries `NOT_ATTEMPTED`, and report cooperative overrun rather than claiming hard preemption.
- [x] Isolate ordinary per-entry `Exception`, including post-commit `CancellationException`, and continue unrelated entries. Never convert fatal JVM `Error` into a cache health event. Reconcile every phase input exactly in the final report.
- [x] Implement bounded sanitized failure records and structured drain results. Retain only exception type and structural counts—never messages, causes, suppressed exceptions, stack traces, values, identifiers, credentials, SQL, URLs, endpoints, or serialized snapshots. Add malicious, Unicode, oversized, bidi-control, and identifier-ignorable exception fixtures. Observer callbacks run only during explicit caller-thread drain; a throwing observer consumes that event and increments `observerFailureCount`.
- [x] Implement and test `loggingSnapshotCacheFailureObserver()`. It logs only the sanitized failure object/type. Document that `storeId.namespace` is the only static low-cardinality tag candidate and `affectedCount` is a measurement, never a tag.
- [x] Implement this exact public failure API and compile source usage of `poll`, default/limited `drainTo`, the logging observer, and caller-supplied buffer identity:

```kotlin
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
- [x] Re-run targeted tests and the full `:bluetape4k-exposed-cache:test` task.
- [x] Commit with Lore trailers:

```text
Bind snapshot mutation visibility to one Exposed commit boundary

Constraint: Cache failure after commit must not affect database outcome
Rejected: Immediate cache mutation with rollback repair | readers could observe dirty state
Confidence: high
Scope-risk: moderate
Tested: coordinator lifecycle, limits, ordering, cleanup, and failure tests
```

Completion evidence: focused coordinator/failure tests 28/28, full
`:bluetape4k-exposed-cache:test` 141/141, and the `jdbc-caffeine` cross-module
API contract 2/2. Independent spec and code-quality reviews reported no
remaining Critical, Important, or Minor findings. Root `detekt` succeeds with
`NO-SOURCE`; the cache module has no module-specific detekt task.

## Task 4: Implement the JDBC Caffeine facade and transaction extensions

**Files:**
- Modify: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotCacheStore.kt`
- Modify: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/snapshot/SnapshotLocalFenceRegistry.kt`
- Create: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcCaffeineSnapshotCache.kt`
- Create: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotTransaction.kt`
- Create: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcCaffeineSnapshotCacheTest.kt`
- Create: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotTransactionTest.kt`
- Create: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/JdbcSnapshotCacheApiUsageTest.kt`
- Modify: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/snapshot/SnapshotCacheCommonApiCompileTest.kt`

- [x] Write failing tests for factory configuration, hit/miss, opaque claim, weighted/unweighted construction, commit PUT, commit invalidation, rollback discard, mapper execution inside the current root transaction, captured transaction rejection, and `maxAttempts > 1` rejection for snapshot fill only. Prove invalidation remains attempt-local and allowed under Exposed retry configuration.
- [x] Add an H2 test that counts SQL writes and proves the cache commit callback performs zero additional database writes.
- [x] Add the deterministic stale-fill race test at the public facade boundary.
- [x] Add engine-real lifecycle tests: preceding throwing `afterCommit`, `beforeRollback`, and `afterRollback` `StatementInterceptor` callbacks; callback-time staging; commit-then-stage; rollback-then-stage; interceptor non-accumulation; nested savepoint commit followed by outer rollback; and nested rollback followed by outer commit. Earlier callback failure must produce zero cache mutation and retain payload only until transaction GC. Every invalid receiver must fail before mapping or buffer mutation.
- [x] Add retry tests proving a failed invalidation attempt leaks nothing and a successful retried attempt publishes exactly once; outer snapshot-fill retry must reacquire a fresh miss before each database read.
- [x] Prove capacity/error timing at the public facade: retained miss tokens fill the registry and the next `lookup` fails before the SQL counter changes; mapper failure consumes the token and reusing it fails before a second mapping call.
- [x] Run `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --tests '*Jdbc*CaffeineSnapshotCacheTest' --tests '*JdbcSnapshotTransactionTest'` and confirm red.
- [x] Implement the exact factory and transaction extensions:

```kotlin
fun <ID : Any, V : Serializable> jdbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V>

inline fun <reified ID : Any, reified V : Serializable> jdbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcCaffeineSnapshotCache<ID, V> =
    jdbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

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

- [x] Keep facade constructors internal. Expose only `storeId`, the exact caller-supplied `failureBuffer` instance, and `lookup(id): SnapshotCacheLookup<ID, V>`; prove both explicit-token and reified factories preserve the supplied buffer identity without `@PublishedApi` constructor access.
- [x] Compile the README-equivalent JDBC usage in `JdbcSnapshotCacheApiUsageTest`; assert the exact receiver/signature surface by reflection so R2DBC engine types do not leak into this module.
- [x] Configure Caffeine weight/expiry exactly from `CaffeineSnapshotCacheConfig`; pass the exact non-negative `SnapshotValueSizer` estimate to Caffeine and enforce `maximumSize` independently after maintenance without synthetic weight inflation. Never silently ignore an optional setting.
- [x] Require a root current `JdbcTransaction`; require `maxAttempts == 1` only when consuming a miss for snapshot fill. Register one core `StatementInterceptor` via the common coordinator.
- [x] Keep cache callbacks cache-only and cooperatively bounded by `localDrainBudget`; after a completed operation crosses the deadline, record its normal one-count outcome followed by `OVERRUN(0)`, then mark remaining entries `NOT_ATTEMPTED`. Preserve report count reconciliation without claiming a hard latency bound or throwing into the completed transaction.
- [x] Re-run module tests and confirm all existing tests remain green.
- [x] Commit with Lore trailers:

```text
Expose commit-safe JDBC Caffeine snapshot caching

Constraint: Automatic Exposed retries cannot reuse attempt-local miss capabilities
Rejected: Reusing repository put methods | they can persist to the database again
Confidence: high
Scope-risk: moderate
Tested: JDBC Caffeine unit, transaction, H2, and concurrency tests
```

Completion evidence: cache 141/141; JDBC Caffeine 364 passed and 22 existing
environment-gated tests skipped, with zero failures/errors under the repository
Ryuk-disabled test contract. Controlled weighted-capacity and stale-fill races
passed 100 repetitions each. Independent spec and code-quality reviews reported
no remaining Critical, Important, or Minor findings. Root `detekt` succeeds
with `NO-SOURCE`; the module has no module-specific detekt task.

## Task 5: Review the JDBC vertical slice before duplicating it

**Files:**
- Modify only files from Tasks 1-4 when findings require changes.

- [x] Run `./gradlew :bluetape4k-exposed-cache:test :bluetape4k-exposed-jdbc-caffeine:test --no-daemon`.
- [x] Inspect the public API for accidental ID/fence exposure, direct PUT, strong transaction references, database access from callbacks, blocking primitives, and missing KDoc.
- [x] Run `git diff --check` and a Kotlin diagnostics/compile pass on touched modules.
- [x] Fix every P0/P1 finding before proceeding; fix P2/P3 findings unless a concrete deferral issue is created.
- [x] Commit only if the review changes code, using an intent-first Lore message.

Completion evidence: independent vertical review reported P0=P1=P2=P3=0 after
aligning the design SPI with the verified implementation. The exact two-module
gate passed with cache 141/141 and JDBC Caffeine 364 passed plus 22 existing
environment-gated skips. Forced Kotlin main/test compilation passed without
warnings or errors. One pre-existing non-snapshot H2 timing assertion flaked
during a forced full rerun and passed immediately in isolated rerun.

## Task 6: Implement the R2DBC Caffeine facade and transaction extensions

**Files:**
- Create: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcCaffeineSnapshotCache.kt`
- Create: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotTransaction.kt`
- Create: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcCaffeineSnapshotCacheTest.kt`
- Create: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotTransactionTest.kt`
- Create: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/snapshot/R2dbcSnapshotCacheApiUsageTest.kt`

- [x] Port the JDBC contract tests first, replacing only the transaction engine and preserving the same opaque miss/fence/coordinator assertions.
- [x] Add H2 R2DBC commit/rollback tests and an SQL-write counter proving post-commit cache work is cache-only.
- [x] Repeat the engine-real lifecycle—including preceding throwing `afterCommit`, `beforeRollback`, and `afterRollback` callbacks—nesting, callback-time staging, interceptor-ordering/non-accumulation, invalidation retry, and fresh-miss outer fill retry cases from Task 4 for R2DBC. Assert zero cache mutation and transaction-GC-bounded retention when an earlier callback skips ours.
- [x] Repeat the public capacity/error-timing tests: full registry fails at lookup before R2DBC work and mapper failure consumes the token.
- [x] Add a conditional unknown-physical-commit/cancellation proof seam. If Exposed offers no injectable commit seam, capture source/bytecode evidence plus a focused contract test showing no `afterCommit`/cache event and do not label the outcome rollback.
- [x] Run the two targeted R2DBC tests and confirm red.
- [x] Implement:

```kotlin
fun <ID : Any, V : Serializable> r2dbcCaffeineSnapshotCache(
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V>

inline fun <reified ID : Any, reified V : Serializable> r2dbcCaffeineSnapshotCache(
    config: CaffeineSnapshotCacheConfig,
    valueSizer: SnapshotValueSizer<V>? = null,
    validator: CacheSnapshotValueValidator<V> = rejectDirectEntitySnapshotValues(),
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): R2dbcCaffeineSnapshotCache<ID, V> =
    r2dbcCaffeineSnapshotCache(ID::class, V::class, config, valueSizer, validator, failureBuffer)

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

- [x] Keep constructors internal, prove explicit-token and reified factories preserve the caller-supplied failure-buffer identity, and compile the README-equivalent usage in `R2dbcSnapshotCacheApiUsageTest`. Assert JDBC engine types do not leak into this module.
- [x] Use the same common coordinator and cache-only implementation; do not introduce `runBlocking`, a scheduler, or a worker thread.
- [x] Run `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-daemon` and compare API behavior with the JDBC contract.
- [x] Commit with Lore trailers:

```text
Keep R2DBC snapshot visibility aligned with JDBC commits

Constraint: R2DBC cache callbacks must remain non-suspending and cache-only
Rejected: Awaiting cache work after commit | it couples database completion to cache health
Confidence: high
Scope-risk: moderate
Tested: R2DBC Caffeine unit, transaction, H2, and concurrency tests
```

Completion evidence: targeted R2DBC contracts 40/40, cache 141/141, and full
R2DBC Caffeine 106 passed plus one existing pending test. Main/test Kotlin
compilation completed with zero warnings or errors. The public injected commit
seam proves `BEFORE_COMMIT -> PHYSICAL_COMMIT_STARTED`, no `AFTER_COMMIT`, caller
cancellation propagation, and unchanged cache/failure state without calling the
outcome rollback. Controlled races and every coroutine wait are bounded.
Independent spec and code-quality reviews reported no remaining Critical,
Important, or Minor findings.

## Task 7: Define canonical Redisson identifiers, codec, configuration, and namespace fingerprint

**Files:**
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotIdentifierPolicy.kt`
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonCodec.kt`
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorConfig.kt`
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceFingerprint.kt`
- Modify: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/repository/ExposedRedissonCodecSafety.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonCodecTest.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceFingerprintTest.kt`
- Modify: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/repository/RedissonRepositoryCodecSafetyTest.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotRedissonApiUsageTest.kt`

- [x] Write failing golden-vector tests: signed Long uses exactly 8 big-endian bytes; UUID uses exactly 16 bytes, most-significant then least-significant bits, big-endian. Reject String and unsupported ID types.
- [x] Write configuration tests for all positive caps/timeouts and `trustedBinaryCache = false` default. In separate parameterized tests, allow only `SyncStrategy.INVALIDATE` (reject `UPDATE` and multi-node `NONE`) and only `ReconnectionStrategy.CLEAR` (reject every other enum value, including `NONE`/`LOAD`). Use existing `ExposedRedissonCodecSafety`: reject Fory, Kryo, and JDK object codecs by default; permit them only with explicit trusted-binary opt-in.
- [x] Write fingerprint tests covering only a canonical UTF-8, line-delimited, field-name-sorted allowlist: backend, namespace, key raw class, snapshot raw class, schema version, codec class/version, sync strategy, and canonical key-encoding ID. Prove endpoints, usernames, credentials, tuning values, and arbitrary `toString()` output are excluded.
- [x] Run targeted tests and confirm red.
- [x] Implement the exact public codec API and validate `codecVersion` with `[A-Za-z0-9._-]{1,64}`:

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

- [x] Add a direct `Codec` overload to `ExposedRedissonCodecSafety` and test it. Require the same wrapper instance in the existing repository's `RedissonCacheConfig.codec` and the invalidator; `AbstractJdbcRedissonRepository`/`AbstractSuspendedJdbcRedissonRepository` already pass `config.codec` to their map construction, so add source-usage tests proving their local-cached map receives the wrapper without changing those classes unless the test reveals a gap.
- [x] Route every delegate codec through `ExposedRedissonCodecSafety`. Document that trusted-binary opt-in is only for isolated data where all writers and payloads are trusted.
- [x] Implement `JdbcRedissonSnapshotInvalidatorConfig` exactly as approved, including encoded key/batch/commit caps and outstanding chunk/byte limits.
- [x] Use this exact public configuration declaration and defaults:

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

- [x] Document that identifiers must be non-secret, non-credential, non-PII surrogate keys.
- [x] Compile `longSnapshotIdentifierPolicy`, `uuidSnapshotIdentifierPolicy`, and `snapshotRedissonCodec` source usage; include an API contract assertion that no String identifier policy/factory is present.
- [x] Re-run targeted tests.
- [x] Commit with Lore trailers:

```text
Pin distributed invalidation to canonical identifier bytes

Constraint: Every node must derive identical Redis key material and namespace metadata
Rejected: String identifiers | they make normalization and sensitive-key leakage ambiguous
Confidence: high
Scope-risk: moderate
Tested: Redisson codec golden vectors, configuration, and fingerprint tests
```

Task 7 evidence: commits `2382cc3`, `f89141a`, `c3c7afd`, and `bd50cc8`
implement the exact three-argument public codec factory, canonical Long/UUID key
bytes, deterministic allowlisted fingerprinting, and consumer-owned binary-codec
trust. Redisson 4.6.1 repository-relevant delegate wrappers are traversed with
identity-cycle and 64-node bounds; supported-wrapper inspection drift fails
closed while reviewed unknown codecs remain usable. Targeted Task 7 tests passed
78/78, the full JDBC Redisson module passed 523 tests with one existing skip,
main/test compilation and root detekt passed, and independent spec and quality
reviews reported P0=0, P1=0, P2=0, P3=0.

## Task 8: Implement bounded non-blocking Redisson invalidation and failure handling

**Files:**
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/RedissonInvalidationQuotaRegistry.kt`
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidator.kt`
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotTransaction.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/RedissonInvalidationQuotaRegistryTest.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorTest.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotTransactionTest.kt`

- [x] Write failing tests for exact encoded-byte measurement at stage time, canonical re-encode/hash verification at submit time, batch/commit caps, an admission/submission attempt for every chunk before local work, pinned first-client quota configuration, mismatched factory rejection, and invalidation-only Redis commands. With quota smaller than total chunks, rejected chunks and an early synchronous failure must not suppress later stores or local phases.
- [x] Add completion-future tests for synchronous pre-future failure, normal completion, exceptional completion, duplicate completion notification, and never-completing future. Assert exactly-once lease release and bounded outstanding counts/bytes.
- [x] Add failure-buffer tests: successful completion records nothing; non-success uses non-blocking bounded offer; explicit drain calls the observer on the caller thread; observer exception consumes the event and appears in the structured drain result.
- [x] Run targeted tests and confirm red.
- [x] Implement the exact public construction/composition surface:

```kotlin
fun <ID : Any, V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    idType: KClass<ID>,
    valueType: KClass<V>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID>

inline fun <reified ID : Any, reified V : Serializable> jdbcRedissonSnapshotInvalidator(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    config: JdbcRedissonSnapshotInvalidatorConfig,
    failureBuffer: SnapshotCacheFailureBuffer = snapshotCacheFailureBuffer(),
): JdbcRedissonSnapshotInvalidator<ID> =
    jdbcRedissonSnapshotInvalidator(
        redissonClient,
        codec,
        ID::class,
        V::class,
        config,
        failureBuffer,
    )

data class SnapshotInvalidationQuotaHealth(
    val maxOutstandingChunks: Int,
    val outstandingChunks: Int,
    val maxOutstandingEncodedBytes: Long,
    val outstandingEncodedBytes: Long,
    val rejectedChunks: Long,
    val saturated: Boolean,
)

fun JdbcRedissonSnapshotInvalidator<*>.quotaHealth(): SnapshotInvalidationQuotaHealth

fun <ID : Any> JdbcTransaction.stageInvalidation(
    invalidator: JdbcRedissonSnapshotInvalidator<ID>,
    id: ID,
)
```

- [x] Keep invalidator constructors internal. Expose only `storeId`, the identical caller-supplied `failureBuffer`, `quotaHealth()`, and transaction invalidation; expose no read or snapshot PUT. Compile explicit-token and reified usage and prove both preserve supplied-buffer identity.
- [x] Add a narrow repository-plus-invalidator contract fixture: construct the existing repository `RedissonCacheConfig` and the invalidator with the identical `SnapshotRedissonCodec` object, versioned map namespace, value-type token, caller-owned `RedissonClient`, and identical supplied failure buffer. Before map use, reject every mismatch the exact factory can observe locally: unsupported ID/value tokens, codec safety, invalid configuration, and same-client quota-cap drift. Before mutating transaction state, reject same-transaction store-token, compatibility-fingerprint, and failure-buffer collisions. The exact public factory intentionally accepts no repository contract object, so cross-transaction repository/invalidator namespace, codec, value-token, schema, and configuration mismatch is rejected by Task 9's remote namespace marker before accepting mutations rather than by a partial process-local registry. Keep example-application work for #326.
- [x] Implement a per-Redisson-client weak-identity quota registry. The first valid factory pins caps; later mismatches fail before constructing a facade.
- [x] Encode and measure every staged ID without retaining raw sensitive IDs in public health/failure reports. Re-encode and verify bytes/hash before submission.
- [x] For every chunk in sequence, re-encode, attempt admission, submit only when admitted, structurally record rejection/failure, and continue. Begin local phases only after all chunks/stores received an attempt; all chunks need not be admitted. Attach completion callbacks; never call `await`, `get`, `join`, `runBlocking`, cancellation, an executor, a scheduler, or a worker thread.
- [x] Release quota immediately on synchronous submission failure, otherwise in completion callback `finally`. A never-completing future intentionally retains its bounded lease until client replacement.
- [x] Expose only the exact structural quota health and the supplied `failureBuffer`; callers drain via `failureBuffer.drainTo(observer)`. Test every health counter transition and saturation/recovery state.
- [x] Re-run targeted tests and the full JDBC Redisson unit suite.
- [x] Commit with Lore trailers:

```text
Bound distributed invalidation without blocking commit callbacks

Constraint: Post-commit Redis health cannot control the committed database result
Rejected: Awaiting or cancelling Redis futures | both violate callback latency and ownership
Confidence: high
Scope-risk: broad
Tested: quota, submission, completion, failure buffer, and invalidation command tests
```

Task 8 evidence: weak client-identity quota and namespace-composition reservations
pin caps and exact local facade contracts before map access, roll back failed
construction without retaining the client, and share one transaction identity for
exact matches. Staging measures canonical bytes and enforces the transaction-wide
commit cap; submission re-encodes, verifies, chunks, admits, and invokes only
`fastRemoveAsync` before local phases. Completion paths release leases exactly
once, retain no measured list or identifier in pending callbacks, keep ordinary
failures structural, and rethrow synchronous fatal `Error` values unchanged.
Focused Task 8 tests passed 37/37, `exposed/cache` passed 142/142, and the full
JDBC Redisson module passed 560 tests with one existing skip. Main/test
compilation, root detekt, diff/forbidden-call audits, independent spec review,
and independent quality review all passed with P0=0, P1=0, P2=0, P3=0.

## Task 9: Add namespace administration, recovery, and two-client Redis integration

**Files:**
- Modify: `exposed/jdbc-redisson/build.gradle.kts` (test-only Toxiproxy API compile dependency)
- Create: `exposed/jdbc-redisson/src/main/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdmin.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/SnapshotNamespaceAdminTest.kt`
- Create: `exposed/jdbc-redisson/src/test/kotlin/io/bluetape4k/exposed/redisson/snapshot/JdbcRedissonSnapshotInvalidatorIntegrationTest.kt`

- [x] Write unit tests for atomic marker claim/compare, mismatch rejection before cache use, map-before-marker cleanup order, bounded asynchronous unlink, ACL failure reporting, and quiescence requirement. Require an exact `expectedFingerprint`; marker-absent/map-present fails closed; map-absent/marker-present safely resumes marker deletion.
- [x] Write a sequential Testcontainers test with two Redisson clients: populate client B near-cache, commit invalidation from client A, and poll with a bounded monotonic deadline plus deterministic timeout diagnostics until B no longer serves the stale local value. Also verify rollback sends no invalidation.
- [x] Add a construction-options test proving positive local-cache size, `SyncStrategy.INVALIDATE`, and `ReconnectionStrategy.CLEAR` reach `RLocalCachedMap`. Add a deterministic peer-only reconnect test that primes client B's stale local state, disconnects only B through repo-owned Toxiproxy while A remains directly connected and invalidates the remote key, observes B transport reconnect plus invalidation-topic resubscription without a cache `get`, and proves CLEAR removed the cached key before exactly one first post-reconnect `mapB[3]` hit under bounded monotonic deadlines with deterministic timeout diagnostics.
- [x] Add namespace marker script timeout and connection-failure tests. Facade creation must fail closed before map access, registration, or mutation on timeout, connection failure, or mismatch.
- [x] Add incompatible fingerprint and never-completing-future recovery tests. Recovery must quiesce, close the old client, prove old quota zero after close/completion under one bounded monotonic deadline with deterministic timeout diagnostics, and drain failures before replacement. Then create facades with a distinct new `RedissonClient` identity and fresh quota registry; prove the closed old client cannot be reused. Expiry fails recovery closed.
- [x] Run `./gradlew :bluetape4k-exposed-jdbc-redisson:test --tests '*SnapshotNamespaceAdminTest' --tests '*JdbcRedissonSnapshotInvalidatorIntegrationTest' --no-daemon` sequentially and confirm red before implementation.
- [x] Implement the exact guarded cleanup surface and an `@RequiresOptIn(ERROR)` delicate admin annotation:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class DelicateSnapshotCacheAdminApi

enum class SnapshotNamespaceCleanupOutcome {
    COMPLETED,
    ALREADY_COMPLETE,
    MARKER_RETAINED,
    TIMED_OUT_ACCEPTED_UNKNOWN,
    FAILED,
}

data class SnapshotNamespaceCleanupResult(
    val outcome: SnapshotNamespaceCleanupOutcome,
    val mapAbsent: Boolean,
    val markerPresent: Boolean,
    val exceptionType: String? = null,
)

@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearSnapshotNamespace(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult

@DelicateSnapshotCacheAdminApi
fun <ID : Any> clearMapRetainingMarker(
    redissonClient: RedissonClient,
    codec: SnapshotRedissonCodec<ID>,
    namespace: String,
    expectedFingerprint: String,
    timeout: Duration = Duration.ofSeconds(2),
): SnapshotNamespaceCleanupResult
```

- [x] Compile source usage of the delicate opt-in and every cleanup result outcome. Use one shared monotonic timeout across marker verification, asynchronous map unlink, local clear, and absence verification; accepted server cleanup cannot be cancelled, and a rerun resumes from observed partial state.
- [x] Before any Redisson/map/script interaction, both admin helpers validate namespace against `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`, `expectedFingerprint` against lowercase SHA-256 `[0-9a-f]{64}`, and a positive bounded timeout. Add zero-client-interaction tests for every invalid input.
- [x] Delete map entries before marker and fail closed on the partial-state matrix. Mark both APIs delicate and document dedicated namespace-scoped Redis ACL credentials, network isolation, quiescence, and the prohibition on request-facing exposure. Treat the fingerprint as an accident guard, not authorization.
- [x] Implement remote namespace marker verification within `namespaceVerificationTimeout`. Reject incompatible namespace reuse before accepting mutations.
- [x] Verify integration fixtures use finite Redisson command timeout and retry policy no greater than five seconds before outage/recovery cases.
- [x] Add a configuration-contract test for the exact v1-to-v2 state machine. Rollout: deploy v2 readers/writers, warm or naturally repopulate v2, cut every node over, stop all v1 writers, drain in-flight requests, then clear v1 remote map, every node's v1 local view, and v1 marker. Rollback: stop v2 writers, quiesce traffic, clear the retained v1 remote map and every node's v1 local view while retaining/revalidating its marker, switch every node to an empty v1, rebuild and verify reads from the database, and only then clean v2. Reject mixed-version nodes sharing an unversioned namespace.
- [x] Re-run the targeted integration tests sequentially, then the full module test task sequentially.
- [x] Commit with Lore trailers:

```text
Make Redisson namespace compatibility and recovery explicit

Constraint: Operators need bounded cleanup without silently mixing incompatible nodes
Rejected: Automatic destructive cleanup on mismatch | it is unsafe without quiescence and ACL authority
Confidence: high
Scope-risk: broad
Tested: namespace admin unit tests and sequential two-client Redis integration tests
```

Task 9 evidence: the sequential live fixture uses `RedisServer.Launcher.redis`, independently owned Redisson
application/operator clients, 2-second command/connect timeouts, one retry, a 250-millisecond retry delay, and a
500-millisecond heartbeat. Every random namespace is tracked; teardown restores paused transports, closes clients,
deletes the Toxiproxy proxy, deletes both map and marker keys, and verifies no tracked Redis key remains. Real Redis Lua
claim persisted across clients, exact markers matched, mismatches failed closed, guarded rollback cleanup retained and
revalidated the marker, and destructive cleanup removed map before marker. Two-client commit invalidation removed client
B's primed stale local value under a bounded monotonic poll; rollback retained it after a later committed barrier
invalidation, without a timing sleep. The reconnect proof routes only client B through the repo-owned `ToxiproxyServer`
while A stays directly connected. It observes B's Redisson 4.6.1 connection-listener disconnect, allows A to remove the
key while B is isolated, restores the proxy, awaits B transport reconnect and the exact `{$namespace}:topic` local-cache
invalidation-topic resubscription, and observes the public local-cache view clear before exactly one first `mapB[3]` read.
Recovery committed an invalidation during the outage, observed one outstanding quota lease, bounded old-client shutdown,
then proved quota zero plus one drainable failure before creating a distinct replacement with fresh caps; old-client reuse
and an expired verification failed closed. The live v1/v2 state machine uses separate operator and application clients,
asserts quota quiescence and actual client shutdown before each cleanup, creates a fresh empty v1 client after retained-marker
cleanup, rebuilds from the database, closes the rebuilt v1 client, and only then cleans v2; its trace is appended only after
each real operation succeeds. The single-read reconnect regression failed RED with stale on the first post-reconnect hit;
the peer-only listener/subscription/CLEAR implementation then passed three consecutive isolated reruns. The exact targeted
suite passed 41/41. The final full JDBC Redisson module run executed 607 tests with 606 passing, one existing skip, zero
failures, and zero errors. Root `detekt` completed successfully with the root task reported as `NO-SOURCE`.

## Task 10: Document the public contract in English and Korean

**Files:**
- Modify: `exposed/cache/README.md`
- Modify: `exposed/cache/README.ko.md`
- Modify: `exposed/jdbc-caffeine/README.md`
- Modify: `exposed/jdbc-caffeine/README.ko.md`
- Modify: `exposed/r2dbc-caffeine/README.md`
- Modify: `exposed/r2dbc-caffeine/README.ko.md`
- Modify: `exposed/jdbc-redisson/README.md`
- Modify: `exposed/jdbc-redisson/README.ko.md`
- Do not modify: `docs/manual/en/**`, `docs/manual/ko/**`, `docs/manual/manifest.yaml`

- [x] Add paired sections covering detached immutable DTOs, mapper timing, `Entity` prohibition, root transaction/current transaction requirements, `maxAttempts = 1`, outer retry shape, commit/rollback semantics, last-mutation-wins, local fence behavior, limits, post-commit failure observability, and no database writes in callbacks.
- [x] Add Redisson guidance covering invalidation-only behavior, Long/UUID key policy, key sensitivity restrictions, canonical codec/fingerprint compatibility, quota saturation, failure drain, quiescent cleanup, and client replacement.
- [x] State that namespace is a static operator-owned versioned name matching `[a-z][a-z0-9._-]{0,62}:v[1-9][0-9]*`, never a tenant/request/entity identifier; unsafe binary codecs require explicit trusted isolated-cache opt-in; cleanup APIs require dedicated ACLs and must not be request-facing.
- [x] Document that public failure/health surfaces retain only bounded structural data and exception type, never exception text, stack traces, payloads, identifiers, SQL, URLs, endpoints, or credentials.
- [x] Add a paired behavior table distinguishing Exposed transaction-local `EntityCache` from this application near-cache. State explicitly that commit-safe is not database/cache atomicity, is not crash durability, and does not replace an application-owned outbox/repair path after post-commit cache failure.
- [x] State that the feature is opt-in with no migration of existing repository caches. Document lookup-capacity failure before database work, one-shot token consumption on mapper/staging failure, callback-order stale-cache behavior, savepoint rejection, invalidation retry support, and fresh lookup for each outer snapshot-fill retry.
- [x] Add exact bilingual v1-to-v2 rollout and rollback runbooks matching Task 9, including the mixed-version prohibition, verified database rebuild before v2 cleanup, shared cleanup timeout semantics, alerts/rate controls for repeated invalidations, and database load shedding for miss amplification.
- [x] Include equivalent runnable snippets in both languages. Mark the JDBC, R2DBC, and Redisson blocks as canonical and keep library coordinates versionless because consumers own the BOM version.
- [x] Extract and normalize each canonical fenced block from both English/Korean READMEs in tests (or assert exact literal equality with the compiled fixture source), then compile the canonical fixture through the source-usage tests created in Tasks 4, 6, 7, and 8. Keep API-name parity and use API reflection/ABI assertions for negative wrong-engine and no-String-policy cases without adding a compiler-testing dependency.
- [x] Audit all new public declarations for English KDoc and `@InternalSnapshotCacheApi` opt-in where appropriate.
- [x] Run a literal parity check for public type/function names across every README pair.
- [x] Confirm `git diff -- docs/manual docs/manual/manifest.yaml` is empty.
- [x] Commit with Lore trailers:

```text
Explain safe snapshot caching at transaction and operator boundaries

Constraint: Stable manuals remain pinned to released 1.11.0 APIs
Rejected: Publishing issue 321 APIs in stable manuals | the feature is not released yet
Confidence: high
Scope-risk: narrow
Tested: bilingual API-name parity and stable-manual diff check
```

**Evidence:** Forced fresh targeted verification passed 57/57 tests across cache, JDBC Caffeine, R2DBC Caffeine, and
JDBC Redisson source-usage/admin contracts. The canonical typed JSON codec round-trips the documented immutable DTO,
and the README gate rejects explicit Maven artifact versions. The 61 new top-level public declarations have English
KDoc, internal SPIs retain the required opt-in boundary, and the stable manual diff is empty.

## Task 11: Extend the existing benchmark module

**Files:**
- Create: `benchmark/exposed-benchmark/src/benchmark/kotlin/io/bluetape4k/exposed/benchmark/cache/SnapshotCacheBenchmark.kt`
- Modify: `benchmark/exposed-benchmark/build.gradle.kts`

- [x] Add benchmarks for local hit, miss capability creation/claim, one-key and maximum count/weight buffers, repeated-key coalescing, multi-store phase partitioning, peak drain allocation, commit drain, striped lookup/fence contention, Redisson key encoding/chunk submission through a fake async-store seam, failure-buffer saturation, and timeout/outage connection-hold behavior. Use bounded in-memory fixtures; do not require Redis for the default benchmark task.
- [x] Extend the existing `cacheBenchmark` include pattern to compile and optionally execute the new class. Do not add a module or dependency.
- [x] Run `./gradlew :benchmark-exposed-benchmark:benchmarkClasses --no-daemon`.
- [x] Run `./gradlew :benchmark-exposed-benchmark:smokeBenchmark --no-daemon`; the existing smoke configuration provides one warmup, one measurement, and 100 ms iterations. Treat numbers as diagnostic, not a release gate.
- [x] Inspect allocations/latency for accidental per-entry thread, scheduler, unbounded collection, reflection traversal, repeated serialization, lock hot spots, and retained connection/future state. Fix structural regressions; record environment-sensitive numbers only in the PR body. Keep deterministic tests as the gate for retained count/weight, submission counts, and proof that callbacks never await Redis.

Task 11 evidence: the existing benchmark module declares `src/benchmark/kotlin` as its source set, so the planned
`src/jmh/kotlin` path was corrected to the repository's established source-set convention. The compile-oriented RED
failed on the intentionally absent coverage seam before implementation. `benchmarkClasses` then compiled the new
class, and the Redis-free smoke run discovered and executed all 12 snapshot-cache methods with one bounded warmup and
measurement. The fixtures retain a fixed store set, one H2 pool connection, bounded buffers/chunks, no worker threads
or schedulers, and at most one never-completing future. Fast paths have no class-wide invocation setup or teardown;
fixed-memory monotonic counters use before/after deltas, while failure saturation and outage cleanup are isolated to
the two benchmark methods whose lifecycle costs are intentionally measured. The four-thread fence benchmark therefore
does not race shared cleanup. A review RED made the old one-encode/fixed-count path fail its structural assertion. The
replacement fake seam partitions by measured encoded bytes, re-encodes and verifies every identifier digest per chunk,
materializes and consumes reflective boxed-Long arrays, and accounts for bounded chunks and submitted identifiers.
Canonical hex conversion uses a fixed-size character array rather than formatter traversal. The outage benchmark also
requires the H2/Hikari active-connection count to be zero while its one bounded future remains incomplete, then releases
that future in method-local cleanup. Forced-fresh benchmark compilation and the Redis-free smoke run pass with all 12
methods and no JMH exception output. Smoke throughput remains diagnostic only; deterministic adapter tests remain the
correctness gate for count/weight retention, submission accounting, and non-awaiting callbacks.
- [x] Commit with Lore trailers:

```text
Make snapshot cache coordination costs observable

Constraint: Benchmarks must compile in the existing benchmark module without Redis
Rejected: A new benchmark project | it adds registration and publication surface without value
Confidence: medium
Scope-risk: narrow
Tested: benchmark class compilation and bounded local smoke run
```

## Task 12: Run final verification, independent review, and PR delivery

**Files:**
- Modify only files needed to resolve verified findings.

- [x] Run fast deterministic gates:

```bash
./gradlew :bluetape4k-exposed-cache:test \
  :bluetape4k-exposed-jdbc-caffeine:test \
  :bluetape4k-exposed-r2dbc-caffeine:test \
  :benchmark-exposed-benchmark:benchmarkClasses \
  :benchmark-exposed-benchmark:smokeBenchmark \
  --no-daemon --no-parallel --rerun-tasks -Pkotlin.incremental=false
```

- [x] Run the Testcontainers-backed Redisson gate by itself:

```bash
TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :bluetape4k-exposed-jdbc-redisson:test \
  --no-daemon --no-parallel --rerun-tasks -Pkotlin.incremental=false
```

- [x] Validate the stable manual inventory without changing pinned manual content:

```bash
./gradlew exportManualModuleInventory --no-daemon
ruby -Itest scripts/manual/release_inventory_test.rb
ruby scripts/manual/release_inventory.rb \
  1.11.0 0b494a5fd1e083006046764757342b68a397e4c5 \
  build/manual/module-inventory.json build/manual/module-inventory-1.11.0.json 40
ruby scripts/manual/validate_manuals.rb build/manual/module-inventory-1.11.0.json docs/manual/manifest.yaml
```

- [x] Run repository static gates:

```bash
./gradlew detekt --no-daemon
git diff --check
```

- [x] Perform independent reviews for performance, stability/concurrency, security, operator/Ops, developer/API, and user/caller behavior. Integrate findings in the main session. Require `P0=0` and `P1=0`; resolve P2/P3 or create a clearly justified follow-up issue.
- [x] Re-run every gate affected by review fixes and record exact results.
- [x] Verify the final diff contains no `settings.gradle.kts`, stable manual, dependency catalog, Spring Boot, Ktor, Lettuce, or issue #322 schema-drift changes.
- [ ] Push `feat/issue-321-transaction-aware-snapshot-cache` and open an English PR against `develop` referencing `Closes #321`. Include design decisions, test evidence, benchmark environment caveat, distributed failure semantics, and known non-goals.
- [ ] Wait for GitHub checks and current review/thread status. Report the exact PR/head as merge-ready and stop for fresh user merge approval.

**Final evidence (rebased on `origin/develop` `0907513a4dfb358a39f2b79002ec6ccd049635c6`):**

- Cache: 149 tests, 0 failures/errors/skips.
- JDBC Caffeine: 387 tests, 0 failures/errors, 22 skips.
- R2DBC Caffeine: 108 tests, 0 failures/errors, 1 skip.
- JDBC Redisson: 612 tests, 0 failures/errors, 1 skip, run separately with Ryuk disabled.
- Benchmark: `benchmarkClasses` plus 32 smoke benchmarks; all 12 `SnapshotCacheBenchmark` methods emitted finite scores with no structural exception.
- Stable manuals: the current inventory was filtered against immutable release `1.11.0` at commit `0b494a5fd1e083006046764757342b68a397e4c5`; 40 projects aligned.
- Static/scope: `detekt` succeeded (`:detekt NO-SOURCE`), `git diff --check` passed, and forbidden surfaces were absent.
- Independent re-review after fixes: performance/stability, security/Ops, and developer/API/user perspectives each reported `P0=0`, `P1=0`, `P2=0`, `P3=0`, `COMPLETE=YES`.

## Completion Checklist

- [x] Every acceptance criterion maps to passing evidence.
- [x] No cache callback can write the database.
- [x] Rollback and retry behavior are explicit and tested for JDBC and R2DBC.
- [x] Public miss tokens expose no identifier or generation state.
- [x] Caffeine ordering fences reject stale fills under controlled concurrency.
- [x] Redisson is invalidation-only, bounded, non-blocking, and namespace-compatible.
- [x] Failure buffers and recovery remain bounded even for never-completing futures.
- [x] English/Korean README APIs match and public KDoc is complete.
- [x] Stable manuals remain unchanged and validate against the pinned release inventory.
- [x] Benchmark sources compile and the bounded smoke run has no structural regression.
- [x] Independent review reports `P0=0`, `P1=0`.
- [ ] PR is open against `develop`; merge waits for fresh user approval.
