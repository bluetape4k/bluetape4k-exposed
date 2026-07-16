# Issue #325 Ktor Cache Health and Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add sanitized, bounded cache readiness and Micrometer metrics to the existing Exposed Ktor readiness route while replacing the unreleased Boolean cache-worker health field with an explicit lifecycle state and preserving all existing Ktor JVM descriptors.

**Architecture:** Keep repository lifecycle state in the JDBC/R2DBC Caffeine repositories, adapt it through backend-neutral Ktor contributors, and aggregate contributors sequentially under one shared monotonic cache-phase deadline. Register a fixed meter set once per route, publish immutable samples through atomic holders, and keep Ktor HTTP details limited to `cache.<component> -> UP|DOWN|timeout`. Reconcile Spring Actuator with the same worker-state contract without importing Spring types into the Ktor module.

**Tech Stack:** Kotlin 2.3+, JetBrains Exposed, kotlinx-coroutines, Ktor 3, Micrometer, Spring Boot 4 Actuator, Caffeine, JUnit 5, MockK, bluetape4k assertions, Gradle 9.6.

---

## Delivery Contract

- Repository: `bluetape4k-exposed`
- Issue: `#325`
- Base branch: `develop`
- Head branch: `feat/issue-325-cache-health-metrics`
- Approved design: `docs/superpowers/specs/2026-07-16-issue-325-ktor-cache-health-design.md`
- Pull request: create after implementation, local verification, and independent code review pass; target `develop`, assign `debop`, mirror issue milestone/labels, and include `Closes #325`.
- Merge: stop after reporting the exact PR/head, green required CI, current approvals, and zero unresolved review threads; obtain fresh user approval before rebase merge.
- Dependency rule: add no production dependency outside existing repository modules and catalog/BOM-managed libraries. Add `api(project(":bluetape4k-exposed-cache"))` because public Ktor factories expose cache types. If Ktor authentication is not already on the test compile classpath, add only `testImplementation("io.ktor:ktor-server-auth")`; its version remains governed by the existing Ktor BOM.
- Version rule: do not modify dependency catalogs or perform issue #322's Exposed 1.3.1 upgrade in this branch.
- Module rule: no new Gradle module, artifact, workflow, or `settings.gradle.kts` change.
- Manual rule: update module READMEs only; stable `docs/manual/**` content remains unchanged.
- Public documentation rule: new KDoc and PR/commit text are English. Update English and Korean READMEs together and run the `bluetape-writer` parity/naturalness gate.
- Diagram rule: N/A. The approved design requires API, tables, and runbook prose; no new relationship is clearer as a generated diagram.

## Acceptance Mapping

| ID | Acceptance criterion | Tasks | Proof |
|---|---|---|---|
| AC-1 | Existing config, installer, eight-parameter route, and `$default` descriptors remain intact | 0, 7, 9 | captured baseline and `javap` compatibility test |
| AC-2 | Cache worker lifecycle distinguishes not-applicable, idle, running, draining, failed, and stopped | 1-3 | cache/JDBC/R2DBC state and close-race tests |
| AC-3 | Spring JDBC/R2DBC Actuator mappings and details use the finite worker state | 4 | state-matrix auto-configuration tests |
| AC-4 | Ktor contributors are typed, sanitized, bounded, unique, and cache-only capable | 5, 7 | validation, factory, installer, and route tests |
| AC-5 | Cache readiness is sequential and the full JDBC/R2DBC/cache endpoint follows the conservative supported budget | 6 | virtual-time orchestration plus saturated-dispatcher/DataSource smoke test |
| AC-6 | Request cancellation produces one cancelled outcome and is rethrown; supplier-thrown cancellation is sanitized | 6 | structured request/supplier cancellation tests |
| AC-7 | HTTP bodies and metric tags never expose secrets or arbitrary caller data | 5-7 | redaction and exact tag-set tests |
| AC-8 | Meter cardinality is fixed, collision-safe, and concurrency-safe | 5, 6 | 128-meter-ID bound, atomic registration rollback, repeat/concurrency tests |
| AC-9 | Snapshot gauges are read-only measurements and historical counts do not force readiness down | 5, 6 | snapshot sampling/recovery/concurrency tests |
| AC-10 | Bilingual operator guidance covers security, timing, metrics, runbooks, migration, and Actuator | 8 | parity, API-name, fenced-snippet, and prose review |

## Fixed JVM Compatibility Baseline

The branch was compiled before implementation with `./gradlew :bluetape4k-exposed-ktor:classes --no-configuration-cache`. Preserve these descriptors exactly:

```text
Bluetape4kExposedKtorConfig.<init>:
(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;)V

Bluetape4kExposedKtorConfig.<init>$default-marker:
(Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;ZZLjava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;ILkotlin/jvm/internal/DefaultConstructorMarker;)V

installBluetape4kExposedKtor:
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;)V

installBluetape4kExposedKtor$default:
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;ILjava/lang/Object;)V

bluetape4kExposedHealthRoutes-021xcDE:
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;)V

bluetape4kExposedHealthRoutes-021xcDE$default:
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;ILjava/lang/Object;)V
```

Do not append a defaulted cache parameter to either existing declaration. Add separate overloads and delegate both old and new entry points to internal implementations.

## Risk Prediction and Rerun Triggers

| Risk | Preventive design | Rerun trigger |
|---|---|---|
| Late worker completion overwrites `FAILED` after close timeout | completion uses compare-and-set from `DRAINING`; timeout publishes `FAILED` before cancellation | lifecycle/completion-handler changes |
| Lazy worker appears unhealthy before first write | initialize write-behind as `IDLE`; CAS to `RUNNING` only after first accepted queue write and never overwrite draining/terminal state | queue admission/start changes |
| R2DBC fast consumer corrupts queue depth | linearize suspend admission and accounting so decrement cannot precede accepted-entry increment | R2DBC queue or admission changes |
| Recoverable flush error becomes terminal accidentally | retain `lastFlushError`; successful flush clears it; only uncaught completion/failed drain changes lifecycle to `FAILED` | flush exception handling changes |
| Parent cancellation is misclassified as cache timeout | use `withTimeoutOrNull` only as the local deadline boundary, return sealed results outside it, and rethrow parent cancellation | coroutine timeout wrapper changes |
| Concurrent requests publish stale gauge samples | claim generation per contributor only at invocation/synthetic timeout; publish only if still newest | readiness ordering/state-holder changes |
| Meter collision partially registers or cross-binds a route | serialize library preflight/registration with an installation-only `ReentrantLock`, track created meters, and roll back the current attempt | meter names/tags/registration changes |
| Blocking caller probe exceeds deadline | public contract forbids blocking/backend I/O; tests document unsupported behavior; library creates no isolation thread | contributor factory/dispatcher changes |
| Cardinality grows with requests or failures | pre-register 4 gauges plus 4 finite timer outcomes per contributor | tags, outcome vocabulary, contributor limit changes |
| Secret reaches HTTP, exception, log, or metric boundary | sanitize at factory/result boundary; validation reports only index/length/reason | validation, detail, logging, tag changes |
| Stable manuals advertise unreleased APIs | restrict docs to module READMEs and assert stable-manual diff is empty | any `docs/manual` diff |
| Testcontainers contention obscures failures | run JDBC, R2DBC, and Spring module gates sequentially with `--no-parallel` | database fixture or CI topology changes |

## Repository Hazard Check

- CodeGraph returned an empty graph for this worktree, so direct `rg`, context-mode indexing, source inspection, and compiled `javap` output are the current evidence sources.
- Existing Ktor baseline: 8 tests passed before the design commit; `:bluetape4k-exposed-ktor:classes` also passes on the design-only head.
- Existing `CacheHealthReport` is unreleased and has only JDBC Caffeine, R2DBC Caffeine, Spring JDBC, and Spring R2DBC production consumers.
- Existing Ktor route runs JDBC then R2DBC sequentially and returns only allowlisted `HealthResponse` details. Cache aggregation extends that ordering after databases.
- Existing snapshot failure buffer already exposes bounded local `size`, `droppedCount`, and `observerFailureCount`; no new snapshot-buffer API is required.
- `ktor-server-auth` has no existing local alias. A test-only coordinate may rely on the already imported Ktor BOM; do not add a catalog version.

## Task 0: Lock baseline behavior and TDD seams

**Files:**
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorAbiCompatibilityTest.kt`
- Do not modify yet: production Kotlin sources

- [ ] **Action:** Load `test-driven-development`, `kotlin-coroutines-skill`, and the applicable Kotlin testing references. Add an ABI test that derives the production class directory/JAR from `Bluetape4kExposedKtorConfig::class.java.protectionDomain.codeSource.location`, invokes JDK `javap -s -p`, and asserts every descriptor in the fixed baseline above, including both `$default` methods. If the runtime cannot execute `javap`, use reflection plus `MethodType.toMethodDescriptorString()` for methods/constructors instead of relying on Gradle's worker-bootstrap `java.class.path`.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorAbiCompatibilityTest' --no-configuration-cache` passes before production changes.
  **Failure:** Stop production work if `javap` is unavailable or the captured descriptors differ; resolve the baseline or replace it with an equivalent compiled-consumer test first.
- [ ] **Action:** Record the current database-only Ktor test count and response bodies, and keep the existing tests unchanged as regression fixtures.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --rerun-tasks` reports the baseline suite green.
  **Failure:** Diagnose any baseline failure before adding RED tests; do not attribute it to issue #325.
- [ ] **Action:** Commit the compatibility fixture with Lore trailers.
  **Evidence:** The commit contains only the ABI fixture and records the passing baseline command in `Tested:`.
  **Failure:** Do not mix production changes into the baseline commit.

```text
Lock the Ktor binary contract before adding cache readiness

Constraint: Existing database-only callers must retain exact JVM descriptors
Rejected: Appending a defaulted cache parameter | it changes generated default bridges and risks binary callers
Confidence: high
Scope-risk: narrow
Tested: Ktor baseline tests and javap descriptor assertions
```

## Task 1: Replace the unreleased Boolean health field with a worker state

**Files:**
- Modify: `exposed/cache/src/main/kotlin/io/bluetape4k/exposed/cache/CacheHealthReport.kt`
- Create: `exposed/cache/src/test/kotlin/io/bluetape4k/exposed/cache/CacheHealthReportTest.kt`

- [ ] **Action:** Write RED tests for enum exhaustiveness/order and report serialization with `NOT_APPLICABLE`, `IDLE`, `RUNNING`, `DRAINING`, `FAILED`, and `STOPPED`; assert the Boolean property no longer exists through reflection. Because the serializable shape is intentionally incompatible before first release, assert the new fixed `serialVersionUID = -1428853048381429257L` through `ObjectStreamClass` and round-trip only the new shape.
  **Evidence:** The targeted test initially fails because `CacheWorkerState` and `workerState` do not exist.
  **Failure:** If tests pass before implementation, strengthen them to prove the new public contract rather than merely compiling the old one.
- [ ] **Action:** Implement the public contract with English KDoc:

```kotlin
enum class CacheWorkerState {
    NOT_APPLICABLE,
    IDLE,
    RUNNING,
    DRAINING,
    FAILED,
    STOPPED,
}

data class CacheHealthReport(
    val mode: CacheWriteMode,
    val queueDepth: Int,
    val workerState: CacheWorkerState,
    val lastFlushError: Throwable?,
) : Serializable
```

  **Evidence:** `./gradlew :bluetape4k-exposed-cache:test --tests '*CacheHealthReportTest' --no-configuration-cache` passes and public KDoc covers each state's meaning.
  **Failure:** Do not retain the old UID or add a compatibility alias for `isFlushJobRunning`; both would create misleading legacy-deserialization or semantic compatibility.
- [ ] **Action:** Commit with Lore trailers.
  **Evidence:** The commit contains the finite report contract, KDoc, and its passing targeted test.
  **Failure:** Do not commit a Boolean compatibility alias or unrelated cache changes.

```text
Make cache worker health explicit before its first release

Constraint: A lazy healthy worker must differ from a failed or closed worker
Rejected: Keeping isFlushJobRunning as an alias | it preserves the ambiguity this issue must remove
Confidence: high
Scope-risk: moderate
Tested: CacheHealthReport contract and serialization tests
```

## Task 2: Drive the JDBC Caffeine lifecycle through finite states

**Files:**
- Modify: `exposed/jdbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/AbstractJdbcCaffeineRepository.kt`
- Modify: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/JdbcCaffeineRepositoryExtraTest.kt`
- Modify: `exposed/jdbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/jdbc/caffeine/repository/JdbcCaffeinePersistedHookTest.kt`

- [ ] **Action:** Add RED tests for `NOT_APPLICABLE` in non-write-behind modes; fresh `IDLE`; first accepted write `RUNNING`; close-visible `DRAINING`; successful drain `STOPPED`; recoverable flush error while `RUNNING`; later successful flush clearing the error; uncaught failure/cancellation `FAILED`; actual deadline-expired close `FAILED`; separately interrupted close `FAILED`; late completion unable to replace `FAILED` with `STOPPED`; and latch-controlled put-versus-close admission races.
  **Evidence:** Targeted tests fail on the old Boolean report and missing transitions. Add a module-internal close-wait duration seam used by public `close()` with the unchanged 30-second production value and by tests with a short deterministic duration. Keep interruption as a distinct dedicated-close-thread test.
  **Failure:** Do not reduce the production timeout or add a public timeout knob only for tests. If raw thread use is necessary, confine it to the synchronous `close()` interruption fixture and document why coroutine test helpers cannot model `InterruptedException`.
- [ ] **Action:** Add one authoritative `AtomicReference<CacheWorkerState>` initialized from write mode. After queue admission succeeds, use only CAS `IDLE -> RUNNING`; a concurrent `DRAINING`, `FAILED`, or `STOPPED` state wins and is never overwritten. Before closing the channel, CAS `IDLE|RUNNING -> DRAINING`. On job completion, publish `STOPPED` only when the current state is `DRAINING`, the completion cause is null, `lastFlushError` is null, and queue depth is zero; otherwise publish `FAILED` without allowing a late overwrite. Preserve recoverable flush-error clearing.
  **Evidence:** State transition tests pass repeatedly and `validateConsistency()` remains O(1), read-only, and free of job start/close side effects.
  **Failure:** A probe that accesses the lazy job, starts the worker, performs I/O, or derives state from `Job.isActive` fails the contract.
- [ ] **Action:** Replace the touched production `terminalError!!` with an explicit non-null local/validation path and keep exception types stable.
  **Evidence:** Source scan of touched files reports no `!!`; repository failure tests retain their current exception type.
  **Failure:** Do not broaden the cleanup into unrelated refactoring.
- [ ] **Action:** Run `./gradlew :bluetape4k-exposed-jdbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks`.
  **Evidence:** All JDBC Caffeine tests pass, including normal close and persisted-hook cancellation.
  **Failure:** Investigate any first-fail/retry-pass lifecycle behavior; do not label it flaky without cause.
- [ ] **Action:** Commit with Lore trailers.
  **Evidence:** The commit contains only JDBC lifecycle changes/tests and records the full module result.
  **Failure:** Do not commit if deadline, interruption, admission-close, or late-completion coverage is red.

```text
Expose the real JDBC write-behind lifecycle

Constraint: Readiness probes may only observe O(1) in-memory state
Rejected: Deriving health from Job.isActive | it conflates idle, draining, failed, and stopped states
Confidence: high
Scope-risk: moderate
Tested: JDBC Caffeine lifecycle, recovery, cancellation, close, and late-completion tests
```

## Task 3: Mirror the lifecycle contract in R2DBC Caffeine

**Files:**
- Modify: `exposed/r2dbc-caffeine/src/main/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/repository/AbstractR2dbcCaffeineRepository.kt`
- Modify: `exposed/r2dbc-caffeine/src/test/kotlin/io/bluetape4k/exposed/r2dbc/caffeine/repository/WriteBehindCacheTest.kt`

- [ ] **Action:** Add the same RED state matrix, real deadline-expiry seam, interruption case, and put-versus-close race coverage as JDBC using `runTest` for suspend work and a bounded raw thread only for the synchronous `close()` interruption path. Add deterministic fast-consumer tests that repeat the admission/drain interleaving and assert queue depth never underflows, reaches zero after drain, and cannot change terminal classification after completion.
  **Evidence:** Tests fail against the Boolean report, missing terminal state, and current send-then-increment accounting race.
  **Failure:** Do not use `runBlocking` in production or `runCatching` around suspend probes.
- [ ] **Action:** Implement the same CAS-only lifecycle rules. Linearize suspend admission with queue-depth accounting so the consumer cannot decrement before an accepted send is counted, rejected/cancelled sends leave no phantom depth, and close/terminal completion cannot race a late increment. Track terminal completion explicitly, close the queue on exceptional worker completion so later sends cannot be accepted without a consumer, preserve `NonCancellable` final drain, and classify a failed final flush or nonzero terminal queue depth as `FAILED`.
  **Evidence:** Tests prove later writes are rejected after terminal failure, normal drain becomes `STOPPED`, real timeout/interruption becomes `FAILED`, depth remains non-negative/zero after drain, and late admission/completion cannot overwrite terminal state.
  **Failure:** Expected scope cancellation after `STOPPED` must not downgrade the state to `FAILED`.
- [ ] **Action:** Run `./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks`.
  **Evidence:** All R2DBC Caffeine tests pass with zero lifecycle timing flakes.
  **Failure:** Keep Testcontainers/database-backed module execution sequential.
- [ ] **Action:** Commit with Lore trailers.
  **Evidence:** The commit contains only R2DBC lifecycle/accounting changes/tests and records the full module result.
  **Failure:** Do not commit with queue-depth underflow, terminal admission, or timing instability.

```text
Keep R2DBC write-behind health aligned with its lifecycle

Constraint: Suspend admission must not succeed after the consumer terminates
Rejected: Reporting only the coroutine active flag | it hides terminal and shutdown states
Confidence: high
Scope-risk: moderate
Tested: R2DBC Caffeine lifecycle, terminal admission, close, and race tests
```

## Task 4: Reconcile Spring Actuator status and details

**Files:**
- Modify: `spring-boot/jdbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedJdbcCacheHealthAutoConfiguration.kt`
- Modify: `spring-boot/jdbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/jdbc/config/ExposedJdbcCacheHealthAutoConfigurationTest.kt`
- Modify: `spring-boot/r2dbc/src/main/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcCacheHealthAutoConfiguration.kt`
- Modify: `spring-boot/r2dbc/src/test/kotlin/io/bluetape4k/spring/data/exposed/r2dbc/config/ExposedR2dbcCacheHealthAutoConfigurationTest.kt`

- [ ] **Action:** Add RED table-driven tests for both indicators: `NOT_APPLICABLE|IDLE|RUNNING -> UP`, `DRAINING|STOPPED -> OUT_OF_SERVICE`, `FAILED -> DOWN`, and any non-null `lastFlushError -> DOWN(error)`. Assert `FAILED` without an error uses `Health.down()`.
  **Evidence:** Both targeted test classes fail on the old stalled-queue heuristic.
  **Failure:** Do not infer status from queue depth; it remains a measurement.
- [ ] **Action:** Add mixed-repository precedence cases in both modules and both repository orders: `lastFlushError > FAILED > DRAINING|STOPPED > UP`.
  **Evidence:** Aggregate status and selected throwable are identical regardless repository order; any error wins globally.
  **Failure:** Do not let an earlier `OUT_OF_SERVICE` report mask a later `DOWN` report.
- [ ] **Action:** Replace `flushJobRunning` details with `workerState`, retaining `repositoryCount`, `mode`, `queueDepth`, and optional `lastFlushError` message. Keep JDBC and R2DBC mappings source-equivalent.
  **Evidence:** Detail-key and status tests pass for single and multiple repositories.
  **Failure:** Ktor redaction rules must not be weakened by Spring's separate management-endpoint detail policy.
- [ ] **Action:** Run sequentially:

```bash
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-configuration-cache --no-parallel --rerun-tasks
```

  **Evidence:** Both Spring module suites pass.
  **Failure:** Preserve all existing conditional auto-configuration and bean names.
- [ ] **Action:** Commit with Lore trailers.
  **Evidence:** The commit contains source-equivalent JDBC/R2DBC Actuator mappings and passing module suites.
  **Failure:** Do not commit if status/detail matrices or existing bean conditions regress.

```text
Align Actuator cache health with finite worker states

Constraint: Spring management details remain separate from Ktor response redaction
Rejected: Queue-depth stall inference | queue depth alone is not a failure state
Confidence: high
Scope-risk: narrow
Tested: JDBC and R2DBC Actuator state matrices and detail contracts
```

## Task 5: Add typed Ktor contributors and fixed meter registration

**Files:**
- Modify: `ktor/exposed/build.gradle.kts`
- Create: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheReadiness.kt`
- Create: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheMetrics.kt`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheContributorTest.kt`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheMetricsTest.kt`

- [ ] **Action:** Add `api(project(":bluetape4k-exposed-cache"))`, then write RED compile/runtime tests for the four approved factories, immutable defensive-copy config, non-empty/max-16/unique validation, exact component regex, raw-name redaction, non-negative sample validation, repository state mapping, and read-only snapshot sampling.
  **Evidence:** Tests fail because the contributor/config/status types do not exist.
  **Failure:** Standalone factory exceptions may include only input length plus stable reason code; config construction may additionally include list index and duplicate positions. Neither may include raw input, cause, control characters, URL, key, namespace, or secret-bearing substrings.
- [ ] **Action:** Implement these public signatures with English KDoc and private/internal constructors and kinds:

```kotlin
enum class ExposedKtorCacheStatus { UP, DOWN }

class ExposedKtorCacheContributor private constructor(/* sanitized finite kind and probe */) {
    companion object {
        fun jdbcRepository(component: String, report: () -> CacheHealthReport): ExposedKtorCacheContributor
        fun r2dbcRepository(component: String, report: suspend () -> CacheHealthReport): ExposedKtorCacheContributor
        fun snapshot(component: String, buffer: SnapshotCacheFailureBuffer): ExposedKtorCacheContributor
        fun custom(component: String, probe: suspend () -> ExposedKtorCacheStatus): ExposedKtorCacheContributor
    }
}

class ExposedKtorCacheReadinessConfig(
    contributors: List<ExposedKtorCacheContributor>,
)
```

  **Evidence:** Factory tests prove repository suppliers only map in-memory reports, snapshot reads each public measurement once without drain/mutation, and custom probes expose status only. A KDoc source review asserts the config/factory docs state `[a-z][a-z0-9_-]{0,62}`, forbid tenant/key/URL/namespace/data-bearing names, require O(1) in-memory side-effect-free suppliers, require suspend cancellation cooperation, forbid blocking/backend I/O, and state that the library creates no isolation thread/dispatcher/scope.
  **Failure:** Do not expose arbitrary detail/tag maps, throwable fields, caller-selected kinds, or a serializable internal sample.
- [ ] **Action:** Implement four gauges and four pre-registered timer outcome meter IDs per contributor with exact names, tags, base units, and `NaN` semantics from the design. Hold one immutable sample in `AtomicReference` and one monotonic generation per contributor. Retain direct meter/holder references plus immutable tag sets in installed route state; perform no per-request registry lookup, builder call, tag construction, or registration.
  **Evidence:** Metrics tests assert exact meter IDs/descriptions/base units; non-applicable fields are `NaN`; repeated probes do not grow meter count; 16 contributors create exactly 128 Micrometer meter IDs. Tests/docs state that exported backend time-series count depends on registry/distribution configuration.
  **Failure:** No measurement, write mode, exception type/message, URL, key, namespace, tenant, or request data may become a tag.
- [ ] **Action:** Serialize library-owned preflight and registration with one installation-only `ReentrantLock` that stores no registry reference after the operation and is never used by readiness requests. Reject any existing library meter name with the same `component` and `kind`, including extra tags or incompatible meter types. Track only meters created by the current attempt, remove them on later registration failure, and return a stable sanitized error without an arbitrary registry cause.
  **Evidence:** A pre-populated collision adds no IDs; an injected failure after the Nth registration leaves zero residual current-attempt IDs; two simultaneous identical installs produce exactly one winner, one sanitized loser, 128 IDs, and gauges bound only to the winner; distinct route identities follow `128 * route-count` meter IDs.
  **Failure:** Do not silently reuse another route's gauge state holder, retain the registry in a lock map, expose registry exceptions, or use a request-path lock/global registry.
- [ ] **Action:** Run the two targeted Ktor test classes and commit with Lore trailers.
  **Evidence:** Contributor and metrics tests pass with exact meter-ID, collision, rollback, and concurrent-install assertions.
  **Failure:** Do not commit if any installation can leave partial meters or bind to another route's holder.

```text
Bound cache readiness inputs and metric identities at installation

Constraint: Caller-controlled data must not escape into HTTP details or unbounded tags
Rejected: Arbitrary detail and tag maps | they cannot enforce redaction or cardinality
Confidence: high
Scope-risk: moderate
Tested: contributor validation, snapshot sampling, fixed meters, and collision rollback
```

## Task 6: Aggregate cache readiness under one shared deadline

**Files:**
- Modify: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorCacheHealthRoutesTest.kt`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadinessBudgetTest.kt`

- [ ] **Action:** Write RED tests proving installation-order execution after JDBC/R2DBC, ordinary `DOWN`/exception continuation, shared monotonic cache budget, active timeout, skipped probes not invoked, deterministic details, and aggregate 503 behavior. Assert `/healthz/exposed` never invokes cache suppliers.
  **Evidence:** Tests fail because the route has no cache overload or phase.
  **Failure:** Do not run contributors concurrently or grant each contributor a fresh timeout.
- [ ] **Action:** Add the new route overload with the required final `cacheReadiness` parameter and delegate both overloads to one internal implementation. Give the internal orchestrator injectable backend probe lambdas and `TimeSource` with production defaults; tests use `TestCoroutineScheduler.timeSource`. Use one cache-phase monotonic deadline and `withTimeoutOrNull(remaining)` around a sealed probe result; handle metrics and HTTP details exactly once outside the timed block.
  **Evidence:** Virtual-time tests prove a slow active contributor gets `timeout`, remaining contributors get synthetic timeout without invocation, and the supported cache phase consumes one readiness timeout independent of contributor count. Retain one bounded real-time smoke assertion.
  **Failure:** Parent/request cancellation must not be converted to an HTTP timeout or ordinary failure.
- [ ] **Action:** Add English KDoc to the new public route overload covering cache-only usage, caller-owned authentication/lifecycle/concurrency, the shared cache deadline, unsupported blocking/backend-I/O probes, and the fact that the helper creates or closes no resources.
  **Evidence:** Public-KDoc review finds every ownership, timeout, security, and unsupported-probe clause on the overload itself.
  **Failure:** Do not rely only on README text for public API contracts.
- [ ] **Action:** Implement and test the exception hierarchy explicitly. A local `withTimeoutOrNull` expiry becomes `timeout`. When catching `CancellationException`, rethrow and record `cancelled` only if the current request context is inactive; if a supplier throws it while the request remains active, discard message/cause, map one sanitized `error`, and continue. Catch only ordinary `Exception` after cancellation handling; never catch/retain arbitrary `Throwable` or convert fatal JVM `Error` to `DOWN`.
  **Evidence:** Parent cancellation records one `cancelled`, clears only the active newest generation's gauges to `NaN`, rethrows, emits no HTTP detail, and leaks no job. A secret-bearing supplier cancellation records one `error`, leaks nothing, and later contributors run. A fatal-error fixture propagates.
  **Failure:** Any double `timeout+cancelled`, swallowed request cancellation, rethrown supplier cancellation while the context remains active, or caught fatal error blocks the task.
- [ ] **Action:** Add concurrency/generation tests: late older success cannot overwrite newer data; older active cancellation after newer success cannot replace the newer sample with `NaN`; a newer request cancelled before contributor B does not suppress an older in-flight B; repeated concurrent requests keep meter count constant and gauge reads safe.
  **Evidence:** Controlled deferred/latch tests loop the critical interleavings a bounded number of times without sleeps or probabilistic ordering.
  **Failure:** Do not add a cross-request mutex, queue, worker, scheduler, dispatcher, or scope.
- [ ] **Action:** Add security tests with exception messages and malicious values containing cache keys, SQL, URLs, namespaces, control characters, and credentials. Assert response bodies, validation exceptions/causes, tags, and library logs contain none of them.
  **Evidence:** Only `cache.<validated-component>` and `UP|DOWN|timeout` appear in HTTP details; exact finite tags appear in meters.
  **Failure:** Do not log supplier exceptions in the Ktor layer; caller/repository telemetry owns diagnostic details.
- [ ] **Action:** Add unsupported-supplier contract tests showing intentionally blocking/cancellation-insensitive probes may outlive a coroutine deadline while the library creates no compensating thread/scope. Keep them bounded with explicit release latches.
  **Evidence:** Tests document the limitation and finish deterministically after releasing fixtures.
  **Failure:** Unsupported-behavior tests must not hang the suite or weaken the supported-path deadline assertion.
- [ ] **Action:** Add a bounded snapshot contention test with producer, drainer, and readiness sampler operating concurrently. Prove sampling never calls `poll`/`drainTo`, never blocks or leaks work, publishes only non-negative measurements, and does not grow meter IDs.
  **Evidence:** The latch-controlled test completes repeatedly with constant meter count and the buffer remains caller-drainable.
  **Failure:** Historical dropped/observer counts must never become readiness failure by themselves.
- [ ] **Action:** Add an all-backend budget test with JDBC, R2DBC, and cache enabled. Use the internal probe/time seam for deterministic additive orchestration, then a constrained single-thread JDBC dispatcher plus controllable statement/DataSource fixture so JDBC begins near `R` and consumes `J_effective`; clean up executor/DataSource explicitly.
  **Evidence:** Virtual-time assertions prove `I_jdbc*(R+J_effective)+I_r2dbc*R+I_cache*R`; the bounded real-time smoke stays within the formula plus declared margin and leaves no executor/connection work.
  **Failure:** Documentation-only math, wall-clock sleeps without control latches, or leaked executor/DataSource resources do not satisfy this gate.
- [ ] **Action:** Run `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorCacheHealthRoutesTest' --tests '*ExposedKtorReadinessBudgetTest' --no-configuration-cache --rerun-tasks` and commit.
  **Evidence:** Both cache-route and all-backend budget suites pass with deterministic repeated interleavings.
  **Failure:** Any first-fail/retry-pass result requires root-cause investigation before commit.

```text
Aggregate cache readiness within one deterministic budget

Constraint: Cache probes are caller-owned, non-blocking, and cancellation-cooperative
Rejected: Parallel probes or one timeout per contributor | both increase pressure and destroy the route bound
Confidence: high
Scope-risk: moderate
Tested: ordering, timeout, cancellation, generation, concurrency, and redaction tests
```

## Task 7: Add installer/cache-only/authentication paths and prove ABI

**Files:**
- Modify: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt`
- Modify: `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt`
- Modify: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorTest.kt`
- Modify: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorAbiCompatibilityTest.kt`
- Modify: `ktor/exposed/build.gradle.kts` only if the auth test needs `testImplementation("io.ktor:ktor-server-auth")`

- [ ] **Action:** Write RED tests for the new installer overload, cache-only installation with null databases, old database-only behavior, `installHealthRoutes=false`, and rejection when neither database nor cache exists.
  **Evidence:** New-overload tests fail to compile before implementation while existing tests remain green.
  **Failure:** Do not add a cache property to the existing config constructor or change any current default.
- [ ] **Action:** Change only internal validation to accept an explicit `hasCacheContributors` flag, keep the exact config primary constructor, and add:

```kotlin
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)
```

  **Evidence:** Both installers delegate to shared internal installation; default installer remains a no-op; cache-only route returns readiness.
  **Failure:** The new overload must require a non-default cache config and must not create overload ambiguity.
- [ ] **Action:** Add English KDoc to the new installer overload covering caller-owned security/resource lifecycle, cache-only behavior, `installHealthRoutes`, the shared deadline, and unsupported blocking/backend-I/O probes.
  **Evidence:** Public-KDoc review finds the complete ownership and safety contract on the overload.
  **Failure:** Do not imply that the installer owns authentication, repositories, dispatchers, registries, or shutdown.
- [ ] **Action:** Add a Ktor authentication fixture using `authenticate("ops")` around the direct route overload with `installHealthRoutes=false`. Assert unauthenticated access returns the configured 401/403, contains no readiness details, and invokes zero contributors; then assert authenticated access invokes the contributor exactly once and returns readiness. Add only the test-scoped auth coordinate if compilation requires it.
  **Evidence:** Authentication assertions pass and no second unprotected route exists in the fixture.
  **Failure:** The helper must not install authentication or claim to secure root-installed routes by itself.
- [ ] **Action:** Re-run the ABI test and inspect `javap -s -p` output. Assert the six fixed descriptors remain and pin these added descriptors (the Duration-mangled route method suffix may be discovered, but exactly one added method must match each descriptor):

```text
(Lio/ktor/server/application/Application;Lio/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;)V
(Lio/ktor/server/routing/Route;Lorg/jetbrains/exposed/v1/jdbc/Database;Lkotlinx/coroutines/CoroutineDispatcher;Lorg/jetbrains/exposed/v1/r2dbc/R2dbcDatabase;Ljava/lang/String;Ljava/lang/String;JJLio/micrometer/core/instrument/MeterRegistry;Lio/bluetape4k/exposed/ktor/ExposedKtorCacheReadinessConfig;ILjava/lang/Object;)V
```

  Add compile fixtures for old positional/default calls and new named `cacheReadiness` calls.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --rerun-tasks` passes; exact old/new descriptors and both source-usage shapes are asserted.
  **Failure:** Any changed old method name, descriptor, constructor, or `$default` bridge blocks continuation.
- [ ] **Action:** Commit with Lore trailers.
  **Evidence:** The commit contains installer/auth/ABI changes and records the complete Ktor suite plus descriptor proof.
  **Failure:** Do not commit if any old descriptor changes or unauthenticated access invokes a contributor.

```text
Add cache-only Ktor installation without breaking existing callers

Constraint: Existing config and route default bridges are binary contracts
Rejected: Extending the existing config constructor | it changes default-constructor ABI
Confidence: high
Scope-risk: moderate
Tested: installer, cache-only, authenticated route, database regression, and javap ABI tests
```

## Task 8: Document safe deployment, Actuator mapping, and migration

**Files:**
- Modify: `exposed/cache/README.md`
- Modify: `exposed/cache/README.ko.md`
- Modify: `ktor/exposed/README.md`
- Modify: `ktor/exposed/README.ko.md`
- Modify: `spring-boot/jdbc/README.md`
- Modify: `spring-boot/jdbc/README.ko.md`
- Modify: `spring-boot/r2dbc/README.md`
- Modify: `spring-boot/r2dbc/README.ko.md`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeFixture.kt`
- Create: `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorReadmeParityTest.kt`
- Create: `docs/lessons/2026-07-16-issue-325-cache-readiness.md`
- Do not modify: `docs/manual/**`

- [ ] **Action:** Load `bluetape-writer` before editing. Add source-equivalent English/Korean Ktor sections with compile-checked examples for JDBC report supplier, R2DBC suspend supplier, snapshot buffer, custom status, cache-only installer, ingress/network-policy root route, and manually authenticated direct route. Put canonical marked examples in `ExposedKtorReadmeFixture.kt`; make `ExposedKtorReadmeParityTest` extract the corresponding marked README fences, compare normalized content to the compiled fixture, and assert required headings/API names across both locales.
  **Evidence:** `./gradlew :bluetape4k-exposed-ktor:test --tests '*ExposedKtorReadmeParityTest' --no-configuration-cache` passes; every public type/factory/meter name and canonical example appears in both locales and matches compiled fixture source.
  **Failure:** Do not literally translate Kotlin identifiers or accidentally show both protected and unprotected route installation in one example.
- [ ] **Action:** Document the component regex/limit, forbidden data, side-effect-free O(1) supplier rule, unsupported blocking/cancellation-insensitive behavior, sequential order, shared deadline, response details, exact meter names/tags/base units, `NaN`, collision behavior, 128-meter-ID bound, registry/configuration-dependent exported time-series, one-route-per-registry recommendation, and caller-owned concurrency/rate limiting.
  **Evidence:** The README parity test reports no missing names, headings, canonical fences, or semantic sections.
  **Failure:** Do not claim the timeout can terminate blocking threads or processes.
- [ ] **Action:** Add the planning formula `T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead`, explain whole-second/minimum-one-second JDBC conversion, and provide an orchestrator example whose `timeoutSeconds` exceeds the rounded-up budget plus margin, `periodSeconds > timeoutSeconds`, and `failureThreshold >= 3`.
  **Evidence:** English/Korean examples use identical numeric assumptions and produce the same bound.
  **Failure:** Do not present the formula as a hard guarantee for saturated drivers or unsupported probes.
- [ ] **Action:** Add a Ktor mapping/operations table: `/healthz/exposed` is probe-free liveness; `/readyz/exposed` is traffic readiness; repository `NOT_APPLICABLE|IDLE|RUNNING` without flush error maps `UP`; `DRAINING|FAILED|STOPPED` or a flush error maps `DOWN`; snapshot counters are measurements only. Contrast expected shutdown with worker failure and the Spring Actuator `OUT_OF_SERVICE` mapping.
  **Evidence:** The parity test finds identical state/path/status coverage in both locales.
  **Failure:** Do not imply that liveness owns cache recovery or that Ktor emits `OUT_OF_SERVICE`.
- [ ] **Action:** Add runbook rows for repository `DOWN`, cache timeout, snapshot cumulative counters, `NaN` gauges, invalid configuration, unsupported custom probes, and meter collision. Label dotted names as Micrometer meter IDs, avoid fixed Prometheus/OTel suffix claims, and require operators to inspect actual exported series before queries. Never treat missing/omitted/`NaN` as zero; correlate it with readiness/timer outcome. Explain rate/increase with process-restart/reset awareness for cumulative dropped/observer counters.
  **Evidence:** Runbook headings, diagnostic actions, exporter caveats, and reset semantics are source-equivalent in both locales.
  **Failure:** Do not expose exception messages through Ktor guidance.
- [ ] **Action:** Document collision/shutdown ownership: meter identities live for the registry lifetime; use one route per registry or a fresh registry, and never remove colliding meters while an older route may still serve. Route probes are observers; the caller withdraws traffic, drains/closes repositories, stops the application, and closes the registry. `DRAINING`/`STOPPED` intentionally fail readiness.
  **Evidence:** Both locale runbooks contain the same safe reinstall and shutdown sequence.
  **Failure:** Do not make the helper own repository, application, or registry shutdown.
- [ ] **Action:** Update Spring JDBC/R2DBC Actuator tables and migration notes: replace `flushJobRunning` with `workerState`; list exact UP/OUT_OF_SERVICE/DOWN mappings; contrast automatic Actuator discovery with explicit Ktor contributors.
  **Evidence:** Both Spring README pairs match implementation and tests.
  **Failure:** Keep Actuator's management-endpoint policy distinct from Ktor's stricter response redaction.
- [ ] **Action:** Update `exposed/cache` English/Korean READMEs with the direct Kotlin migration: `isFlushJobRunning` is removed before stable release; use `workerState`, treat fresh `IDLE` as healthy, distinguish `RUNNING`, `DRAINING`, `FAILED`, `STOPPED`, and `NOT_APPLICABLE`, and explain why no ambiguous compatibility alias remains.
  **Evidence:** `ExposedKtorReadmeParityTest` reads both cache locale files and finds every state plus the direct migration expression.
  **Failure:** Do not describe the change only as an Actuator detail-key rename.
- [ ] **Action:** Add a short lesson covering why lifecycle state, shared deadline, fixed meter registration, and overload-based ABI preservation were necessary.
  **Evidence:** Lesson records context, decision, outcome, verification expectations, and future-agent guidance without duplicating the full spec.
  **Failure:** Do not add a lesson before implementation evidence is known; fill the outcome only after tests pass.
- [ ] **Action:** Verify `git diff -- docs/manual` is empty, run `git diff --check`, and commit with Lore trailers.
  **Evidence:** The commit contains paired module README updates and the completed lesson; parity, compiled snippets, and stable-manual checks pass.
  **Failure:** Do not commit source-asymmetric docs, literal Korean translation artifacts, or stable-manual changes.

```text
Explain safe cache readiness at application and operator boundaries

Constraint: Ktor routes are caller-secured and stable manuals remain release-pinned
Rejected: A dedicated cache endpoint | operators need one deterministic readiness decision
Confidence: high
Scope-risk: narrow
Tested: bilingual API parity, compiled examples, runbook review, and stable-manual diff
```

## Task 9: Run diagnostics, performance scan, independent review, and PR delivery

**Files:**
- Modify only files required to resolve verified findings.

- [ ] **Action:** Update CodeGraph after source changes if available; otherwise record the empty/stale graph gap and use direct impact scans. Run Kotlin diagnostics/IDE inspection on every touched `.kt` file, optimize imports, and resolve all touched-code deprecations.
  **Evidence:** Touched Kotlin files have zero diagnostics/errors/unresolved deprecations.
  **Failure:** Do not claim IDE evidence when the Kotlin diagnostic backend is unavailable; use compile/test/static gates and report the gap.
- [ ] **Action:** Run deterministic affected-module gates sequentially:

```bash
./gradlew :bluetape4k-exposed-cache:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-jdbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-r2dbc-caffeine:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-jdbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-spring-boot-r2dbc:test --no-configuration-cache --no-parallel --rerun-tasks
./gradlew :bluetape4k-exposed-ktor:test --no-configuration-cache --no-parallel --rerun-tasks
```

  **Evidence:** Record test counts and zero failures/errors; investigate every retry-sensitive result.
  **Failure:** No parallel Testcontainers/database-backed execution.
- [ ] **Action:** Run static and compatibility gates:

```bash
./gradlew detekt --no-configuration-cache
./gradlew :bluetape4k-exposed-ktor:classes --no-configuration-cache
javap -classpath ktor/exposed/build/classes/kotlin/main -s -p \
  io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig \
  io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorKt \
  io.bluetape4k.exposed.ktor.ExposedKtorHealthRoutesKt
git diff --check
git diff --exit-code origin/develop -- settings.gradle.kts gradle docs/manual
```

  **Evidence:** Detekt/compile/diff checks pass, old descriptors match Task 0, and forbidden surfaces are unchanged.
  **Failure:** A catalog, settings, stable-manual, or issue #322 change is out of scope and must be removed from this branch.
- [ ] **Action:** Load and run the performance-review gate before final review. Inspect the route and metrics paths for per-request `MeterRegistry.find`, meter builders, tag construction, registration, unbounded collections, extra dispatcher/scope/thread creation, blocking calls, per-contributor fresh deadlines, tag growth, quadratic validation, and leaked request state. Confirm installed route state retains direct meter/holder references.
  **Evidence:** Performance verdict reports no P0/P1 and confirms O(contributors), max 16, fixed 128 Micrometer meter IDs, registry-dependent exported time-series, and one shared cache timeout.
  **Failure:** Any structural hot-path regression blocks PR creation even when functional tests pass.
- [ ] **Action:** Run fresh independent code reviews for performance, stability/concurrency, security, operator/Ops, developer/API, and user/caller behavior. Integrate findings in the main session; require `P0=0` and `P1=0`, and resolve P2/P3 or create a justified follow-up issue.
  **Evidence:** Final review table records all six lenses plus main integration, exact findings, fixes, rerun commands, and final severity counts.
  **Failure:** A reviewer timeout or incomplete verdict is not a pass; rerun a bounded fresh reviewer.
- [ ] **Action:** Load `verification-before-completion`, rerun every gate affected by review fixes, update the lesson outcome, and ensure the worktree is clean except intentional commits.
  **Evidence:** Required checks summary is complete: `Required checks: X/X; N/A: N; Blocked: 0`.
  **Failure:** Missing, stale, `UNKNOWN`, or skipped evidence blocks delivery.
- [ ] **Action:** Inspect live issue #325 metadata, push the exact head, create an English PR against `develop`, assign `debop`, mirror issue milestone/labels, and verify live PR metadata. Include design summary, ABI proof, test counts, metric bound, security boundary, operator timing, and `Closes #325`.
  **Evidence:** `gh pr view` reports the expected base/head/assignee/milestone/labels and exact pushed SHA.
  **Failure:** Do not create an unassigned or mis-milestoned PR silently.
- [ ] **Action:** Monitor required CI on the exact head. Re-read reviews and unresolved threads after CI turns green.
  **Evidence:** Report the exact PR number, head SHA, required checks, reviews, and thread count as merge-ready.
  **Failure:** Stop before merge and obtain fresh user approval; auto-merge is forbidden.

## Completion Checklist

- [ ] Existing database-only Ktor source and JVM compatibility is preserved.
- [ ] Every cache worker state and close race has deterministic JDBC/R2DBC coverage.
- [ ] Spring JDBC/R2DBC status and detail mappings match the design.
- [ ] Ktor contributors expose only sanitized finite status and measurement fields.
- [ ] Cache readiness is sequential and bounded by one shared supported-path deadline.
- [ ] Parent cancellation is rethrown and never double-counted.
- [ ] Four gauges and four timer outcomes are registered once per contributor.
- [ ] Registry collisions fail before any partial meter registration.
- [ ] HTTP bodies, exceptions, logs, and tags pass secret-redaction tests.
- [ ] Authenticated manual route installation is proven; helper-owned auth remains a non-goal.
- [ ] English/Korean READMEs and Spring Actuator docs are source-equivalent.
- [ ] Stable manuals, catalogs, settings, and issue #322 remain untouched.
- [ ] All targeted tests, diagnostics, detekt, ABI, diff, performance, and seven-lens review gates pass.
- [ ] PR is open against `develop`; merge waits for fresh exact-head approval.
