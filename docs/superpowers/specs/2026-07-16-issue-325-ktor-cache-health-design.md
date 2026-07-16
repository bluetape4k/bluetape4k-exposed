# Issue #325 Ktor Cache Health and Metrics Design

## Problem

`bluetape4k-exposed-ktor` exposes explicit, caller-owned database health and readiness checks, but it has no equivalent operational contract for cache repositories or the transaction-aware snapshot cache introduced by issue #321. Ktor applications therefore cannot report cache consistency failures or bounded cache metrics without inventing application-specific endpoints.

The new surface must preserve Ktor's explicit opt-in model and must never expose cache keys, entity identifiers, values, SQL, URLs, exception messages, causes, or stack traces.

## Current Evidence

- `bluetape4kExposedHealthRoutes` already aggregates JDBC and R2DBC readiness into `/readyz/exposed`, applies a bounded timeout, rethrows `CancellationException`, and emits Micrometer timers with finite tags.
- `CacheHealthReport` was introduced on `develop` by issue #321 and has not shipped in a stable release. Its current `isFlushJobRunning` Boolean cannot distinguish a healthy, lazy-started worker from a failed or closed worker, so issue #325 will correct that contract before its first release.
- `SnapshotCacheFailureBuffer` exposes bounded counters (`size`, `droppedCount`, `observerFailureCount`) without exposing cached values or identifiers.
- `SnapshotStoreId.namespace` is documented as a permitted metrics tag only when the application controls it as a static, low-cardinality value. The Ktor API will apply a narrower component-name contract instead of accepting arbitrary cache names.
- The Ktor module already depends on Exposed JDBC and R2DBC modules. Adding the existing `:bluetape4k-exposed-cache` project dependency does not introduce an external dependency or a new module.

## Chosen Approach

Add a backend-neutral, sanitized cache readiness contributor contract to `bluetape4k-exposed-ktor` and aggregate configured contributors into the existing `/readyz/exposed` response.

### Public contract

- `ExposedKtorCacheContributor` is an immutable class with a private constructor. Its public companion factories pin kind and sanitization instead of accepting a caller-selected kind:

```kotlin
fun jdbcRepository(
    component: String,
    report: () -> CacheHealthReport,
): ExposedKtorCacheContributor

fun r2dbcRepository(
    component: String,
    report: suspend () -> CacheHealthReport,
): ExposedKtorCacheContributor

fun snapshot(
    component: String,
    buffer: SnapshotCacheFailureBuffer,
): ExposedKtorCacheContributor

fun custom(
    component: String,
    probe: suspend () -> ExposedKtorCacheStatus,
): ExposedKtorCacheContributor
```

- `ExposedKtorCacheStatus` is the finite public enum `UP|DOWN`. Custom contributors return status only; they cannot create metric tags or measurement fields.
- Repository and snapshot factories build an internal, non-serializable sanitized sample. That sample validates every available count as non-negative and enforces repository-only versus snapshot-only field combinations. No new Java serialization contract is introduced.
- `ExposedKtorCacheReadinessConfig(contributors: List<ExposedKtorCacheContributor>)` defensively copies a non-empty list and validates names, uniqueness, and size at construction.
- Because these public declarations expose `CacheHealthReport` and `SnapshotCacheFailureBuffer`, `bluetape4k-exposed-ktor` adds `api(project(":bluetape4k-exposed-cache"))`, not an `implementation` dependency.

Contributor names must match the lowercase ASCII pattern `[a-z][a-z0-9_-]{0,62}`, configured names must be byte-for-byte unique, and at most 16 contributors may be installed per cache-readiness config and route. A component name is an operational label, not a tenant, key, namespace, URL, endpoint, or other data-bearing identifier. These validated names are the only caller-controlled metric tag values.

Every caller-provided probe is side-effect-free and bounded. A JDBC report supplier may only read the repository's existing in-memory atomic consistency state in O(1); it must not perform database, cache, network, file, or other blocking I/O. An R2DBC report supplier has the same in-memory-only rule and must be non-blocking and cooperative with coroutine cancellation if it suspends. A custom probe must likewise be non-blocking and cancellation-cooperative. Snapshot sampling is a bounded local read and performs no backend I/O. A caller that needs blocking or backend work owns dispatcher offload and a backend-native timeout in a different operational surface; placing it in any readiness supplier is unsupported. The library does not create a dispatcher, executor, scope, or worker to isolate caller code.

### Source and JVM compatibility

The existing `Bluetape4kExposedKtorConfig` primary constructor, `Application.installBluetape4kExposedKtor(config)`, and eight-parameter `Route.bluetape4kExposedHealthRoutes(...)` declarations keep their exact JVM descriptors and existing `$default` methods. They delegate to a new internal aggregate implementation with no cache contributors.

Cache support uses new overloads; it does not append a default parameter to an existing public declaration:

```kotlin
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)

fun Route.bluetape4kExposedHealthRoutes(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    healthPath: String = Bluetape4kExposedKtorConfig.DEFAULT_HEALTH_PATH,
    readinessPath: String = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PATH,
    readinessProbeTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PROBE_TIMEOUT,
    jdbcQueryTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_JDBC_QUERY_TIMEOUT,
    meterRegistry: MeterRegistry? = null,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
)
```

The new overload parameters have no defaults, preventing overload ambiguity. Cache-only installation supplies null database arguments plus a non-empty cache config. The installer overload honors `installHealthRoutes`; its validation accepts at least one configured database or cache contributor. English and Korean README examples must compile-check the JDBC supplier, R2DBC suspend supplier, snapshot buffer, custom status probe, and cache-only forms.

### Readiness aggregation

- The existing database-only behavior remains source- and binary-compatible by preserving the old public declarations and JVM descriptors.
- `installHealthRoutes` remains the only installation switch. At least one database or cache contributor is required.
- `/healthz/exposed` remains a liveness response and does not execute cache probes.
- `/readyz/exposed` runs configured cache probes sequentially in installation order. This avoids unstructured concurrency, avoids simultaneous backend pressure, and keeps deterministic metric emission.
- The existing `readinessProbeTimeout` remains the per-JDBC and per-R2DBC probe timeout and is also one shared cache-phase deadline, not a fresh timeout per cache contributor. Every cache probe receives only the remaining monotonic-time budget.
- An ordinary `DOWN` or error does not short-circuit the phase; later contributors still run while budget remains. When the deadline expires, the active contributor and every remaining contributor receive an HTTP `timeout` detail in installation order without invoking the remaining probes.
- For supported non-blocking, cancellation-cooperative probes, the cache phase therefore adds at most one `readinessProbeTimeout` interval to the existing database readiness work, independent of the number of configured cache contributors. Any unsupported blocking or cancellation-insensitive JDBC, R2DBC, or custom supplier can exceed that bound because the library does not own a thread or process boundary capable of terminating it.
- Response details use only `cache.<component> -> UP|DOWN|timeout`; no measurements or failure metadata are returned.
- Any required cache contributor that is `DOWN` or times out makes the aggregate response `503 Service Unavailable`.
- Every invoked probe produces exactly one internal sealed terminal result before HTTP details, timers, or gauges are updated. The actively timed-out invocation produces one `timeout` result and timer. Contributors skipped because the shared budget is exhausted receive a synthetic HTTP `timeout` detail and `NaN` gauges but no probe-duration timer. A cancellation signal observed while the current request context is inactive produces exactly one `cancelled` timer for the active invocation, is rethrown, and never produces an HTTP failure detail. A supplier-thrown `CancellationException` while the current request context remains active is a sanitized ordinary `error`: its message and cause are discarded, later contributors continue while budget remains, and it is never rethrown as request cancellation. No code path records two outcomes for one invocation, and fatal JVM `Error` values are never converted to cache `DOWN`.

With `R = readinessProbeTimeout`, `J_effective` equal to the JDBC statement timeout after the current whole-second/minimum-one-second conversion, and indicator variables for configured JDBC, R2DBC, and cache probes, the conservative supported planning budget is:

`T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + routing/dispatcher overhead`.

The additive JDBC term covers a blocking query that starts near the coroutine deadline and then consumes its driver timeout. JDBC driver timeout enforcement, dispatcher saturation, and unsupported blocking/cancellation-insensitive suppliers remain caller/backend constraints, so operators add deployment margin rather than treating the formula as a process-kill guarantee. README examples set the orchestrator `timeoutSeconds` above the rounded-up budget plus margin, keep `periodSeconds` above `timeoutSeconds`, and use `failureThreshold >= 3` to avoid transient eviction.

### Built-in health mapping

For `CacheHealthReport`:

- Replace the ambiguous `isFlushJobRunning` Boolean, before its first stable release, with a finite `CacheWorkerState`: `NOT_APPLICABLE`, `IDLE`, `RUNNING`, `DRAINING`, `FAILED`, or `STOPPED`. Because the serializable shape is intentionally incompatible, assign a new fixed `serialVersionUID` and test it explicitly rather than retaining the old UID and risking a legacy stream with a null non-null state. Repository implementations own one authoritative atomic lifecycle state; a health probe only observes it and never starts, restarts, closes, or otherwise owns the worker.
- `READ_ONLY` and `WRITE_THROUGH` report `NOT_APPLICABLE` and are `UP` unless the supplier itself fails.
- A fresh lazy `WRITE_BEHIND` repository reports `IDLE`; `IDLE` and `RUNNING` are `UP` when no last flush error is present.
- First accepted work attempts the one-way compare-and-set `IDLE -> RUNNING`; it never overwrites `DRAINING`, `FAILED`, or `STOPPED` when admission races with close. R2DBC admission and queue-depth accounting are linearized so a fast consumer cannot decrement before the producer records acceptance, a rejected or cancelled send cannot leave phantom depth, and terminal classification cannot race a late increment. Expected `close()` transitions `IDLE|RUNNING -> DRAINING` before the channel closes. Confirmed normal drain completion transitions `DRAINING -> STOPPED`; expected scope cancellation after `STOPPED` does not change it. An uncaught terminal failure or cancellation before or during draining transitions to `FAILED`. If the 30-second production close wait expires, `close()` sets `FAILED` before cancelling the scope and never leaves the returned repository indefinitely in `DRAINING`; a late completion cannot overwrite `FAILED` with `STOPPED`. Tests use a module-internal wait-duration seam to exercise the real deadline-expiry path without changing the public timeout and cover thread interruption separately.
- `DRAINING`, `FAILED`, `STOPPED`, or a last flush error makes `WRITE_BEHIND` `DOWN`. A later successful flush clears a recoverable flush error; terminal failure and expected close remain distinct.
- Queue depth is a measurement, not an automatic failure threshold.

For `SnapshotCacheFailureBuffer`:

- Retained, dropped, and observer-failure counts are measurements and do not by themselves make application readiness fail, because snapshot-cache mutations are best-effort, database correctness remains authoritative, and the latter two counters are cumulative without reset semantics.
- The built-in snapshot contributor is `UP` when it can take one sanitized read-only snapshot of the buffer state. It is `DOWN` only when that snapshot operation throws an ordinary exception. Applications that need a windowed threshold or acknowledgement policy express it through a custom contributor.
- A probe reads the buffer state once and never drains, acknowledges, resets, or mutates it. Concurrent offer/drain operations may change the next sample but cannot block readiness beyond the shared deadline.

For custom contributors, the caller owns the safe state mapping but cannot add response fields or metric tag keys.

### Metrics

Reuse the existing Ktor Micrometer naming and outcome vocabulary:

- `bluetape4k.exposed.ktor.cache.readiness`: probe duration with finite `component`, `kind`, `operation=readiness`, and `outcome=success|error|timeout|cancelled` tags.
- Outcome mapping is fixed: returned `UP -> success`; returned `DOWN`, repository `DRAINING|FAILED|STOPPED`, a recorded flush error, or any ordinary supplier/snapshot/custom exception `-> error`; active shared-deadline expiry `-> timeout`; parent cancellation `-> cancelled`. Skipped contributors do not record a timer.
- Four gauges use only `component` and `kind` tags:
  - `bluetape4k.exposed.ktor.cache.queue.depth`, base unit `entries`: accepted write-behind entries not yet observed as flushed;
  - `bluetape4k.exposed.ktor.cache.snapshot.pending`, base unit `events`: currently retained snapshot failure events;
  - `bluetape4k.exposed.ktor.cache.snapshot.dropped`, base unit `events`: cumulative events dropped by the bounded buffer;
  - `bluetape4k.exposed.ktor.cache.snapshot.observer.failures`, base unit `events`: cumulative observer callback failures.
  Each meter description states that `NaN` means unavailable, not zero. Measurement values are never tags.
- `CacheWriteMode` is a response-internal finite value and is not a metric tag. Cache keys, namespaces, URLs, SQL, exception types, and messages are forbidden as tags.

Meters, immutable tag sets, direct timer references, and stable thread-safe gauge holders are created once when routes are installed and reused for every request; the request path performs no `MeterRegistry.find`, meter builder, tag construction, or meter registration. Four gauges per contributor hold one immutable sample behind an atomic reference; fields that do not apply, have not succeeded yet, or belong to an error/timeout result publish `NaN` rather than a stale value. A monotonic generation is claimed per contributor only when its probe begins or it receives a synthetic budget-exhausted timeout. Only the newest claimed generation for that contributor may publish, so a late completion or cancellation from an older attempt cannot overwrite a newer sample; a newer request cancelled before reaching that contributor does not suppress the older in-flight result. Parent cancellation sets the active contributor's gauges to `NaN` only when its generation is still newest; contributors not yet invoked retain their last completed sample. A successful probe publishes all four fields from its one sanitized snapshot. Timers use four finite outcome meter IDs. The upper bound is eight Micrometer meter IDs per contributor, or 128 cache meter IDs per route installation at the 16-contributor limit, and repeated or concurrent requests cannot register more meters. Exported backend time-series count is registry and distribution-configuration dependent because one timer meter may expand into count, sum, maximum, histogram buckets, or percentiles.

Library-owned route installation serializes preflight and registration in one installation-only `ReentrantLock` critical section that is never used by the request path and retains no registry reference after the operation. Before registering any cache meter, installation rejects an existing library meter name with the same `component` and `kind` identity, including incompatible meter types or identities with extra tags. It tracks only meters created by the current attempt and removes them if a later registration fails, then throws a stable sanitized error without retaining the registry exception as its cause. Concurrent identical library installations therefore allow exactly one winner; the loser neither adds meters nor binds gauges to the winner's state holder. Multiple route installations with distinct identities add at most 128 meter IDs each, so the application-wide Micrometer ID bound is `128 * cache-route-installation-count`; exported backend time-series remain registry/configuration dependent. Documentation recommends one Exposed readiness route per application/registry. The library does not add a global registry or silently share a state holder across routes. The caller must not concurrently mutate these library-owned identities outside the library installation API.

Sequential execution applies within one readiness request. The caller owns authentication, request concurrency, and rate limiting for the route; simultaneous requests may probe the same backend concurrently. Shared gauge state must tolerate those updates without exceptions or meter growth, and the library creates no cross-request mutex, queue, or rate limiter that could become application lifecycle work.

The application continues to own the `MeterRegistry` and its lifecycle. The library starts no meter-maintenance jobs and retains no lifecycle work after the caller closes the application and registry.

## Rejected Alternatives

### Dedicated cache endpoint

Adding `/readyz/exposed/cache` would isolate cache output, but it would force operators to compose multiple readiness endpoints and could let database and cache readiness disagree. Aggregating into the existing endpoint preserves one operational decision point.

### Backend-specific repository parameters

Accepting Caffeine, Redisson, Lettuce, or snapshot-store implementations directly would couple the Ktor module to optional backend modules, expand its dependency graph, and make backend capability differences part of the public API. A sanitized contributor boundary keeps the module backend-neutral.

### Caller-provided detail maps and tag maps

Arbitrary maps are flexible but cannot enforce redaction or bounded cardinality. Typed finite fields are deliberately less extensible and safer.

## Failure Modes and Mitigations

1. **Probe timeout or hung backend**: enforce the shared monotonic cache-phase deadline for supported probes, record one timeout timer for the active invocation, give skipped contributors synthetic HTTP timeout details without timers, and avoid exposing the exception. Blocking or cancellation-insensitive supplier code is rejected by contract rather than hidden on a library-owned dispatcher and may outlive or exceed the deadline.
2. **Probe throws an ordinary exception**: catch only `Exception`, return `DOWN`, emit `error`, and discard message/cause/stack details at the HTTP and metric boundaries. Do not catch or retain arbitrary `Throwable`; fatal JVM errors propagate.
3. **Coroutine cancellation**: distinguish parent/request cancellation from the cache-phase timeout and from a supplier-thrown `CancellationException` while the request context remains active. Emit exactly one `cancelled` outcome only for inactive request context and rethrow without an HTTP failure detail; map the active-context supplier signal to sanitized `error` and continue.
4. **Duplicate or unsafe component names**: reject configuration before routes are installed without echoing the raw name. Validation errors contain only list index, input length, and a stable reason code; duplicate errors identify positions, and neither message nor cause contains the rejected value.
5. **Metric cardinality growth**: cap contributors at 16 per route installation, validate component names with the exact lowercase ASCII contract, reject registry identity collisions, register meters once, and allow only finite library-owned tag keys and values.
6. **Write-behind lifecycle ambiguity**: expose `IDLE`, `RUNNING`, `DRAINING`, `FAILED`, and `STOPPED` explicitly; treat a fresh idle repository as healthy without letting the probe start the worker.
7. **Historical snapshot failures**: retain cumulative dropped/observer counters as measurements only, so one recovered event cannot hold readiness `DOWN` until restart.

## Compatibility and Ownership

- Existing callers that configure only JDBC/R2DBC databases keep the same routes and response shape.
- Cache support is opt-in through configured contributors; the library creates no repository, cache, dispatcher, scope, or registry.
- No Spring Boot or Actuator types enter the Ktor module. The Kotlin API change is reconciled in `spring-boot/jdbc` and `spring-boot/r2dbc`: the automatically discovered `exposedJdbcCacheHealthIndicator` and `exposedR2dbcCacheHealthIndicator` map `NOT_APPLICABLE|IDLE|RUNNING` to `Status.UP`, `DRAINING|STOPPED` to `Status.OUT_OF_SERVICE`, and `FAILED` or any `lastFlushError` to `Status.DOWN`. A non-null flush error remains the throwable passed to `Health.down(error)`; a `FAILED` state without that error uses `Health.down()`. Actuator retains `repositoryCount` and per-report `mode`, `queueDepth`, and `lastFlushError` message details, replaces only `flushJobRunning` with finite `workerState`, and keeps its existing management-endpoint disclosure policy separate from the stricter Ktor redaction boundary. Tests and bilingual Spring READMEs assert those exact statuses and details. Ktor documentation links those modules and contrasts automatic Actuator discovery with explicit Ktor contributor installation.
- Cache repository lifecycle behavior is unchanged; only the previously unreleased health report distinguishes its observable states.
- Snapshot/develop consumers replace `report.isFlushJobRunning` checks with `report.workerState == CacheWorkerState.RUNNING` or the appropriate finite-state mapping. Released database-only Ktor callers require no migration.
- The README safe-deployment examples cover two supported shapes: installer-owned root routes protected by ingress/network policy, and `installHealthRoutes = false` plus the direct route overload nested inside caller-owned `authenticate("ops")`. The helper itself provides no authentication, the route must not be exposed directly to the public Internet, and callers must not accidentally install a second unprotected route.
- The library discards contributor exception details at the Ktor boundary and does not log their message, cause, or stack trace. The caller owns safe custom-probe logging and backend telemetry; repository worker logs remain repository-owned.

## Verification

- Preserve the existing eight Ktor tests as the baseline.
- Add RED/GREEN tests for fresh-idle, running, draining, recoverable-error, failed, and stopped repository states; normal close, drain failure, close-timeout cancellation, late-completion races, and unexpected cancellation; snapshot measurements and recovery after historical failures; timeout; ordinary exception redaction; cancellation propagation; duplicate/unsafe names; and the 16-contributor limit.
- Prove the cache phase stays within one timeout budget, exhausted-budget contributors are not invoked, ordinary errors continue to later contributors, and results remain in installation order.
- Prove one active cache timeout emits one `timeout` and zero `cancelled` outcomes, skipped contributors emit no timer and expose `NaN` gauges, parent cancellation emits one `cancelled`, rethrows, and emits no HTTP failure detail, and a secret-bearing supplier-thrown `CancellationException` while the request remains active becomes one sanitized `error` without stopping later contributors. Verify fatal JVM errors propagate.
- Exercise documented bounded JDBC/R2DBC repository suppliers and a non-blocking cancellation-cooperative custom probe. Use intentionally blocking JDBC/custom and cancellation-insensitive R2DBC/custom test doubles to document that unsupported suppliers may exceed and outlive the coroutine deadline without causing the library to create compensating threads or scopes.
- Verify response bodies do not contain secrets, exception messages, SQL, URLs, cache keys, or namespaces.
- Verify malicious component names are rejected and KDoc/README forbid tenant, key, namespace, URL, and endpoint material in component names. Exception messages and causes must not contain the raw value, control characters, or secret-bearing substrings.
- Verify timer tags and measurement meters use only the allowed finite fields, the exact meter names/base units/descriptions, `CacheWriteMode` is not a tag, repeated and concurrent probes keep meter count constant, per-contributor generation ordering handles a newer request that never reaches a contributor and an older cancellation after a newer success, thread-safe gauge updates do not throw, error/timeout gauges become `NaN`, cancellation clears only the active newest contributor generation's gauges, and the maximum is 128 cache meter IDs per route installation. Report exported backend time-series as registry/configuration dependent.
- Verify duplicate meter identity installation fails before any meter is added; a filter/registry failure after the Nth registration removes only the current attempt's meters; concurrent identical installs produce exactly one winner; distinct route installations follow the application-wide meter-ID formula; and no route silently binds gauges to another route's state holder.
- Verify snapshot sampling remains read-only and bounded while producers and drainers run concurrently.
- Verify the all-backend planning budget with JDBC, R2DBC, and cache contributors configured, including a constrained blocking dispatcher and controllable JDBC statement/DataSource fixture where a JDBC query starts near `R` and then consumes `J_effective`, plus an orchestrator timeout example with deployment margin. Use an internal time/probe seam for deterministic virtual-time orchestration and retain one bounded real-time smoke test with explicit executor/DataSource cleanup.
- Preserve the old config, installer, route, and `$default` JVM descriptors with `javap` or an equivalent compiled-consumer compatibility check.
- Compile and test `:bluetape4k-exposed-cache`, `:bluetape4k-exposed-jdbc-caffeine`, `:bluetape4k-exposed-r2dbc-caffeine`, `:bluetape4k-exposed-spring-boot-jdbc`, `:bluetape4k-exposed-spring-boot-r2dbc`, and `:bluetape4k-exposed-ktor`, then run Kotlin diagnostics and `git diff --check`.
- Add a Ktor authentication test proving unauthenticated denial returns the expected 401/403 without readiness details or contributor invocation, then authenticated access invokes the contributor exactly once and returns readiness.
- Add bilingual runbook rows for repository `DOWN`, cache timeout, snapshot cumulative counters, `NaN` gauges, invalid contributor configuration, and unsupported custom probes. The runbook directs operators to caller-owned logs, backend telemetry, worker state, queue depth, and the fixed meters; it explains that dropped/observer counters are cumulative and should be queried with rate/increase rather than treated as readiness failures.
- README parity review covers headings, compile-checked code, API names, supported supplier constraints, route/status examples, meter names/tags, registry-installation limits, runbook rows, security warnings, orchestrator timing, and Actuator links in both locales. After factual parity passes, perform a natural Korean technical-prose review without translating identifiers or changing semantics.

## Acceptance Criteria

- Ktor applications can add cache repository and snapshot-cache readiness to the existing Exposed readiness route.
- Timeout and `DOWN` behavior are deterministic and test-covered.
- HTTP details and Micrometer tags are sanitized and bounded.
- The feature remains explicit opt-in and caller-owned.
- Existing database-only Ktor behavior remains compatible.

## Definition of Done

- Spec and implementation plan pass performance, stability, security, Ops, developer/API, user/caller, and integration review with P0=0/P1=0.
- All targeted tests, diagnostics, documentation parity checks, and final scoped review pass.
- The issue-linked PR targets `develop`, mirrors issue metadata, and reaches green CI on the exact head before merge approval is requested.
