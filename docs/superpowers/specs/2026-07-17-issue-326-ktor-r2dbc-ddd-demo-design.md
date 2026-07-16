# Issue #326 Ktor R2DBC Cache and DDD Demo Design

Date: 2026-07-17
Issue: #326
Milestone: 1.12.0
Branch: `feat/issue-326-ktor-r2dbc-ddd-demo`

## Problem

`examples/ktor-exposed-demo` currently demonstrates caller-owned H2 JDBC and
H2 R2DBC resources, Exposed health/readiness, and one JDBC transaction route.
It does not show the R2DBC transaction helper, a cache-backed repository, the
Spring-neutral DDD contracts, or an explicit non-Spring event handoff.

Issue #326 asks for a runnable Ktor example that connects those capabilities.
The example must be easy to follow as an application scenario rather than a
catalog of unrelated APIs.

## Goal

Expand the demo around an **Order Confirmation** scenario:

1. an HTTP caller confirms an order identified by a client-generated UUID;
2. a Ktor route maps HTTP input to `OrderCommandService`;
3. the service loads or creates a pending `DemoOrder` aggregate;
4. the aggregate records an `OrderConfirmed` domain event;
5. an R2DBC Caffeine repository writes the confirmed `OrderRecord`;
6. only after the repository operation returns successfully, the service hands
   the event snapshot to an application-owned, Spring-free publisher;
7. a cached GET route loads the confirmed order by read-through;
8. readiness reports PostgreSQL R2DBC connectivity and the repository's
   sanitized in-memory consistency state.

The result must be understandable from the bilingual README pair, a static
Architecture Diagram, a time-ordered Sequence Diagram, copy-pasteable local
commands, and focused tests.

## Scope Boundaries

### Included

- Extend only `examples/ktor-exposed-demo` production/test/docs surfaces and
  the canonical README diagram directory. Workflow evidence may additionally
  update the issue-specific spec, plan, checklist, review, and lesson files
  under `docs/superpowers/`, `docs/review/`, and `docs/lessons/`.
- Keep the existing H2 JDBC resource and `/transactions/jdbc-count` route.
- Replace the demo's H2 R2DBC runtime with PostgreSQL R2DBC.
- Add a PostgreSQL-backed R2DBC count route.
- Add a UUID-backed R2DBC Caffeine order repository using deterministic
  `WRITE_THROUGH` mode.
- Add the direct project dependency on
  `:bluetape4k-exposed-r2dbc-caffeine`; the example must not rely on a
  transitive dependency for its primary repository abstraction.
- Apply the repository's existing
  `alias(bt4k.plugins.kotlin.serialization)` plugin so Ktor response DTO
  serializers are generated; this adds no dependency version authority.
- Add a Spring-neutral aggregate, domain event, command service, and
  application-owned event publisher port.
- Add cache readiness with component name `orders`.
- Add a module-local `compose.yaml` for local PostgreSQL.
- Add fast non-Docker service tests and sequential PostgreSQL Testcontainers
  application/integration tests through a dedicated Gradle task.
- Update `README.md` and `README.ko.md` in semantic parity.
- Add matching canonical SVG/PNG Architecture and Sequence Diagram pairs.

### Excluded

- No production library API changes.
- No new Gradle module or published artifact.
- No Spring Boot, Spring `ApplicationEventPublisher`, Spring Modulith, or
  JaVers type in the Ktor demo event path.
- No write-behind cache mode.
- No durable outbox, event store, replay queue, retry worker, or exactly-once
  guarantee.
- No cache/database atomicity claim.
- No production authentication/authorization design; the demo binds to
  loopback, installs no permissive CORS, and applies a small browser-origin
  guard described below. External binding requires application-owned
  authentication, authorization, and TLS outside this example.
- No stable `1.11.0` manual edit because those manuals are release-pinned.
- No publishing aggregation, BOM constraint, catalog upgrade, Exposed 1.3.1
  migration, or issue #322 work.
- No CI/nightly workflow edit. The ordinary `test` task remains fast and
  non-Docker; required PostgreSQL evidence uses a dedicated local
  `postgresIntegrationTest` invocation with `--no-parallel`.

## Current Evidence

### Ktor demo

- `KtorExposedDemoResources` creates and closes caller-owned H2 JDBC and H2
  R2DBC resources, but initializes only the JDBC `DemoItems` table.
- `installKtorExposedDemo` installs the Ktor core/Exposed integrations and
  exposes only `/transactions/jdbc-count` beyond health/readiness.
- The current test proves health, readiness, and the JDBC count only.
- The example is already excluded from publishing aggregation.
- The examples CI job runs `:examples-ktor-exposed-demo:test`; the dedicated
  `postgresIntegrationTest` source set/task must remain outside that task's
  discovery/dependency graph so the existing invocation stays fast and
  non-Docker.

### Reusable R2DBC/cache contracts

- `ApplicationCall.exposedR2dbcTransaction` already preserves cancellation and
  maps ordinary transaction failures through the Ktor integration.
- `AbstractR2dbcCaffeineRepository` supplies read-through `get`,
  write-through `put`, `invalidate`, `validateConsistency`, and explicit
  `close` lifecycle.
- `TimebasedUUIDTable` and the existing credential test repository demonstrate
  a client-generated UUID table compatible with new-row `put`.
- `ExposedKtorCacheContributor.r2dbcRepository("orders")` accepts the
  repository's side-effect-free O(1) in-memory consistency report.

### Reusable DDD contracts

- `AbstractAggregateRoot<UUID>` records ordered Spring-neutral events.
- `domainEvents()` returns an ordered snapshot without clearing, while
  `clearDomainEvents()` is an explicit caller-owned discard operation.
- `drainDomainEvents` is reserved for transfer to a durable owner and is not
  used by this intentionally non-durable in-memory demo publisher.
- `DomainEvent` requires immutable, minimal, non-sensitive payloads and does
  not provide durable publication.

### Documentation and diagram conventions

- Example documentation keeps `README.md` and `README.ko.md` paired with mutual
  links and semantically equivalent commands, paths, and limitations.
- Canonical README visual assets live under `docs/images/readme-diagrams/` as
  matching SVG/PNG pairs; README files embed PNGs.
- The closest visual references are the R2DBC Caffeine architecture and
  sequence pairs:
  `docs/images/readme-diagrams/exposed-r2dbc-caffeine-diagram-01.png` and
  `docs/images/readme-diagrams/exposed-r2dbc-caffeine-sequence-01.png` (with
  their matching SVG sources).
- PostgreSQL Testcontainers examples use `postgres:16-alpine` and explicit
  start/stop ownership.

## Chosen Architecture

The user selected **Architecture B — Command Boundary**.

```text
HTTP Client
    |
    v
Ktor Routes (HTTP mapping only)
    |
    v
OrderCommandService
    |-- load/create DemoOrder aggregate
    |-- repository.put() must return successfully
    |-- snapshot events, publish, then clear only on success
    |
    +--> OrderR2dbcCaffeineRepository
    |       |--> Caffeine local cache
    |       `--> PostgreSQL R2DBC source of truth
    |
    `--> application-owned OrderEventPublisher

Exposed readiness
    |-- PostgreSQL connectivity via the existing R2DBC probe
    `-- cache.orders via repository.validateConsistency()
```

### Responsibility boundaries

| Component | Responsibility | Must not do |
|---|---|---|
| Ktor routes | Parse UUID path parameters, call application services, map stable responses/statuses | Open Exposed transactions around repository methods, publish events, own pools |
| `OrderCommandService` | Load/create the aggregate, confirm it, persist the record, compensate a failed write, then hand off events | Claim durable/atomic delivery |
| `DemoOrder` | Enforce the pending-to-confirmed transition and record `OrderConfirmed` once | Persist itself, know Ktor/cache/Spring |
| `OrderRecord` | Serializable PostgreSQL/Caffeine representation | Carry the aggregate's pending-event buffer |
| `OrderR2dbcCaffeineRepository` | Read-through/write-through storage and consistency report | Publish application events |
| `OrderEventPublisher` | Synchronous, Spring-free application handoff | Clear aggregate events itself or claim replay |
| `KtorExposedDemoResources` | Create, initialize, expose, and close application resources in order | Hide ownership in the Ktor library |

## Rejected Alternatives

### Alternative A: Routes call the repository and publisher directly

Rejected because the important ordering and compensation rules would be spread
across HTTP handlers and be harder to test independently.

### Alternative C: Repository decorator publishes events automatically

Rejected because the cache repository persists `OrderRecord`, not the
event-bearing aggregate. Hidden publication would also obscure the non-atomic
boundary and couple a general persistence abstraction to this demo's event
policy.

### `WRITE_BEHIND` cache mode

Rejected because asynchronous flush, drain, retry, and shutdown semantics would
dominate the example. Order confirmation needs the deterministic rule that
event handoff begins only after `repository.put()` returns successfully.

### H2 PostgreSQL compatibility mode

Rejected for the order path because the user selected a real PostgreSQL R2DBC
example. H2 remains only for the independent JDBC smoke path.

### Automatic Testcontainers startup from `main`

Rejected because application runtime must not depend on test libraries. Local
runtime uses Docker Compose; tests own Testcontainers explicitly.

## Domain Model

### `DemoOrder`

```kotlin
class DemoOrder private constructor(
    override val id: UUID,
    status: OrderStatus,
    updatedAt: Instant,
) : AbstractAggregateRoot<UUID>() {
    var status: OrderStatus = status
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun confirm(occurredAt: Instant): Boolean

    companion object {
        fun pending(id: UUID, createdAt: Instant): DemoOrder
        fun rehydrate(record: OrderRecord): DemoOrder
    }
}
```

- Status values are `PENDING` and `CONFIRMED`.
- `status` and `updatedAt` have private setters, so callers cannot bypass the
  aggregate transition.
- `confirm(occurredAt)` changes `PENDING` to `CONFIRMED`, sets `updatedAt` to
  the same instant, records exactly one `OrderConfirmed`, and returns `true`.
- Calling `confirm()` on an already confirmed aggregate is a sequentially
  idempotent no-op returning `false`; it records no duplicate event.
- Rehydration never recreates historical/published events.

### `OrderConfirmed`

```kotlin
data class OrderConfirmed(
    override val aggregateId: UUID,
    override val occurredAt: Instant,
) : DomainEvent<UUID>, Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

The payload contains only the order identifier and occurrence time. It is
immutable and contains no customer, tenant, credential, or payment data.

### `OrderRecord`

```kotlin
data class OrderRecord(
    val id: UUID,
    val status: OrderStatus,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

`OrderRecord` is the only object stored in PostgreSQL and Caffeine. It does not
inherit `AggregateRoot` and has no domain-event buffer.
`OrderCommandService` owns one injected `Clock`; a successful transition uses
one `clock.instant()` value for both `OrderConfirmed.occurredAt` and the stored
`OrderRecord.updatedAt`.

## Persistence and Cache Design

- `DemoOrders : TimebasedUUIDTable("ktor_demo_orders")` uses a client-generated
  UUID so a new record is inserted by the generic repository.
- `OrderR2dbcCaffeineRepository` extends
  `AbstractR2dbcCaffeineRepository<UUID, OrderRecord>` and binds the explicit
  demo `R2dbcDatabase` through
  `org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager` as the
  process-wide R2DBC default before repository use.
- `LocalCacheConfig(writeMode = CacheWriteMode.WRITE_THROUGH)` provides
  deterministic completion for the example.
- `get(id)` demonstrates read-through: cache miss → PostgreSQL load → cache
  fill.
- `put(id, record)` demonstrates write-through, but the existing implementation
  updates Caffeine before attempting the database write. Cache and database are
  therefore not atomic.
- A concurrent reader can observe the newly cached value during the interval
  between the cache update and a PostgreSQL failure/invalidation. The demo
  accepts and documents this transient dirty-read window; it guarantees only
  best-effort post-failure invalidation, not per-key read/write serialization.
- For a missing ID, the generic teaching path performs a read-through SELECT,
  then an UPDATE, then an INSERT when zero rows were updated. The demo accepts
  these three statements to reuse the real repository abstraction. It does not
  add a demo-local upsert or make exact SQL count a public regression contract.

### Failed-write compensation

If `repository.put()` throws:

1. `OrderCommandService` calls `repository.invalidate(order.id)` to remove a
   possibly dirty unpersisted cache value;
2. the publisher is not called;
3. the aggregate's event buffer remains intact;
4. the service throws a constant-message `OrderPersistenceException` whose
   cause is the original persistence exception; if invalidation also fails,
   that failure is attached as suppressed evidence to the original cause.

The compensation is process-local and best-effort. It does not create an
atomic database/cache transaction.

Cancellation is handled separately from ordinary persistence failure. If
`put()` throws `CancellationException`, the service performs only the local
Caffeine invalidation in a short `NonCancellable` cleanup boundary and then
rethrows the same cancellation instance. It does not retry PostgreSQL, publish
an event, convert cancellation to an application error, or catch fatal JVM
`Error` values. An invalidation failure is suppressed onto the original
ordinary failure or cancellation rather than replacing it.

Cancellation can leave an ambiguous commit result: PostgreSQL may have
committed just before cancellation prevented `put()` from returning. In that
case the cache key is invalidated, no event is published, and the request fails
while PostgreSQL may contain the confirmed record. The demo does not retry or
claim exactly-once recovery for this boundary; callers must reconcile from the
database or use a durable outbox in production.

## Event Handoff Design

`OrderEventPublisher` is a synchronous application port:

```kotlin
fun interface OrderEventPublisher {
    fun publish(events: List<DomainEvent<UUID>>)
}
```

The demo runtime implementation keeps only the latest immutable event snapshot
in memory without Spring. The publisher contract is prompt, synchronous, and
non-blocking: no database, network, file, blocking logger, sleep, or dispatcher
switch is allowed. A production publisher that performs I/O owns an explicit
dispatcher/queue/outbox outside this demo. Tests use recording and failing
publishers.

The route-facing command API is fixed as:

```kotlin
suspend fun confirm(orderId: UUID): OrderConfirmationResult
```

`OrderConfirmationResult` contains the stored `OrderRecord` and an
`eventPublished: Boolean`. The service loads the record with
`repository.get(orderId)`, rehydrates it when present, or creates a pending
aggregate when absent. An internal aggregate-taking seam may exist only for
focused failure tests; routes never load or mutate aggregates themselves.

Ordinary repository and publisher failures cross the service boundary as
constant-message `OrderPersistenceException` and
`OrderEventHandoffException`, each retaining the original throwable as its
cause. The route uses those types to select the stable `503` code. It maps any
other non-cancellation `Exception` to `ORDER_CONFIRMATION_FAILED` without
exposing the cause. Cancellation and fatal JVM `Error` values are never
wrapped.

`OrderCommandService.confirm(orderId)` follows this fixed order:

1. load/rehydrate or create the aggregate, then obtain the transition instant
   from the injected `Clock`;
2. call `order.confirm(instant)`;
3. if it was already confirmed, return the current record without another
   write or event;
4. convert to `OrderRecord`;
5. call `currentCoroutineContext().ensureActive()` as the explicit pre-write
   cancellation gate;
6. call `repository.put(order.id, record)`;
7. call `currentCoroutineContext().ensureActive()` as the explicit
   post-persistence cancellation gate;
8. only after successful return and the cancellation gate, take
   `order.domainEvents()` and call
   `publisher.publish(events)`;
9. after `publish` returns successfully, call `order.clearDomainEvents()`;
10. return the stored record with `eventPublished = events.isNotEmpty()`.

If the coroutine was cancelled before or by the repository return,
`ensureActive()` deterministically prevents publication, the cache key is
invalidated, and the same cancellation is rethrown. Once the gate succeeds,
the synchronous non-suspending publisher and event clear intentionally finish
as one prompt in-process step; there is no later cancellation checkpoint in
that handoff.

A publisher failure occurs after persistence. The service throws
`OrderEventHandoffException` with the publisher failure as its cause, the
confirmed record remains in PostgreSQL/cache, and the service does not call
`clearDomainEvents()`, so the ordered buffer remains while the request owns
that aggregate.
The order route returns a constant sanitized failure and does not expose an
HTTP retry/republication endpoint. When the request ends, that in-memory
aggregate is not retained by the application; a later confirmation reloads the
already confirmed record and emits no event. Buffer retention is demonstrated
at the service seam, not advertised as durable or HTTP-level recovery.
Production atomic delivery requires an outbox or another durable publication
design outside this example.

Sequential repeated confirmation is idempotent. Concurrent confirmation is
outside the guarantee: two requests can both read `PENDING`, write, and publish
because the example adds no per-ID lock or conditional database transition.
Production callers need an optimistic version/conditional update, idempotency
key, or serialized command boundary.

## HTTP Routes

| Method | Path | Behavior | Success |
|---|---|---|---|
| `GET` | `/healthz/exposed` | Probe-free process liveness; does not query PostgreSQL or cache | `200` health JSON while the application is serving |
| `GET` | `/readyz/exposed` | JDBC, PostgreSQL R2DBC, and `cache.orders` readiness | `200` when all UP; existing `503` contract otherwise |
| `GET` | `/transactions/jdbc-count` | Existing H2 JDBC helper | `200 text/plain` count |
| `GET` | `/transactions/r2dbc-count` | Existing Ktor R2DBC helper counts `DemoOrders` | `200 text/plain` count |
| `POST` | `/orders/{orderId}/confirm` | Bodyless command; validate the demo header, load or create pending aggregate, confirm, persist, publish | `200 application/json` `OrderConfirmationResponse` |
| `GET` | `/orders/{orderId}` | Repository read-through lookup | `200 application/json` `OrderResponse`; `404` if absent |

`orderId` must be exactly the canonical lowercase, hyphenated, 36-character
representation returned by `UUID.toString()` and must not be the nil UUID.
All non-nil UUID versions are accepted because the value is bound as a typed
UUID through Exposed; route input is never concatenated into SQL. Oversized,
noncanonical, nil, and unparsable values return a constant `400` response
without echoing input and without touching repository or publisher.

The mutating route additionally requires
`X-Demo-Command: confirm-order`. This non-simple request header makes a
malicious browser origin perform a CORS preflight; the application installs no
permissive CORS policy, so browser-origin state mutation is not allowed by
default. A missing or wrong header returns a constant `403` response without
dependency access. This is a local demo guard, not authentication.
Validation precedence is deterministic: POST checks the command header first,
then parses/validates `orderId`. A request with both a missing/wrong header and
an invalid ID therefore returns `403 DEMO_COMMAND_REQUIRED`; only a valid
header allows the `400 INVALID_ORDER_ID` path.

The example uses the Ktor core JSON composition already installed by the
application and small response DTOs; it does not expose persistence entities
directly. `updatedAt` is an ISO-8601 UTC `Instant` string.

Example response for `POST /orders/{orderId}/confirm`:

```json
{
  "orderId": "018f6f95-7f4a-7a20-8b52-70ad30c30f36",
  "status": "CONFIRMED",
  "updatedAt": "2026-07-17T00:00:00Z",
  "eventPublished": true
}
```

For a sequential repeated confirmation, `eventPublished` is `false`. The GET
response uses the first three fields only.

The exact DTO types are:

```kotlin
@kotlinx.serialization.Serializable
data class OrderResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
)

@kotlinx.serialization.Serializable
data class OrderConfirmationResponse(
    val orderId: String,
    val status: String,
    val updatedAt: String,
    val eventPublished: Boolean,
)

@kotlinx.serialization.Serializable
data class DemoErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
)
```

Exact JSON error contracts:

| Status | Code | Message | Correlation ID |
|---|---|---|---|
| `400` | `INVALID_ORDER_ID` | `Order id must be a canonical non-nil UUID.` | absent |
| `403` | `DEMO_COMMAND_REQUIRED` | `Required demo command header is missing or invalid.` | absent |
| `404` | `ORDER_NOT_FOUND` | `Order was not found.` | absent |
| `503` | `ORDER_PERSISTENCE_FAILED` | `Order could not be stored.` | present |
| `503` | `ORDER_EVENT_HANDOFF_FAILED` | `Order was stored but its event was not handed off.` | present |
| `503` | `ORDER_CONFIRMATION_FAILED` | `Order confirmation failed.` | present |
| `503` | `ORDER_READ_FAILED` | `Order could not be loaded.` | present |

Every order-route success and error response uses `application/json`. Error
JSON contains only `code`, `message`, and the optional UUID `correlationId`
named above. Health/readiness and count routes retain the media types defined
by their existing Ktor integration and the route table above; the two count
routes remain `text/plain`.

The GET route maps an ordinary repository failure to `ORDER_READ_FAILED`.
Order-route persistence/publication/unexpected failures otherwise map to the
constant allowlisted `503` variants above with a random correlation ID.
The demo emits one sanitized structured diagnostic containing only correlation
ID, fixed component, allowlisted operation, and fixed outcome fields. POST uses
`operation=confirm`; GET uses `operation=read`. The exact runtime shape is
`code=<allowlisted code> correlationId=<UUID> component=order-command operation=<confirm|read> outcome=failed`.
Responses and application logs must not include raw primary or suppressed exception text, SQL, R2DBC URL,
database/user/password values, stack traces, or submitted input. Causes remain
available only to direct service/startup tests and in-process callers that
already own the thrown exception; the demo logger never renders them.
`CancellationException` is always rethrown and is not mapped to a response.

Startup failures use the same diagnostic allowlist and exit without printing an
uncaught throwable/stack trace. The startup wrapper retains the original cause
and suppressed cleanup failures for direct tests, but `main` reports only the
stable record
`code=DEMO_STARTUP_FAILED correlationId=<UUID> component=ktor-exposed-demo phase=startup outcome=failed`.
The testable runner returns exit status `0` after a clean normal stop, `1`
after startup failure, and `2` when startup succeeded but application-resource
shutdown was degraded. Startup failure remains status `1` even when cleanup
also fails. `main` passes that status to `exitProcess`, so automation never
mistakes a sanitized failure for success. The throwable is never
passed as a logger argument. README
directs operators to `docker compose ps`, PostgreSQL health/logs, configured
environment names, and the correlation ID; it never asks them to expose a raw
application exception.

POST calls only `OrderCommandService`; GET may call the repository directly as
a read-only cache demonstration. Neither route wraps the cache repository in
`exposedR2dbcTransaction`, because the repository already opens its own
`suspendTransaction`. The dedicated count route exists to teach the Ktor R2DBC
transaction helper without demonstrating nested transactions.

## PostgreSQL Runtime Configuration

`KtorExposedDemoConfig` reads these local defaults and permits explicit test
construction:

| Environment variable | Default |
|---|---|
| `DEMO_POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5432/ktor_exposed_demo` |
| `DEMO_POSTGRES_USER` | `demo` |
| `DEMO_POSTGRES_PASSWORD` | `demo` |

`examples/ktor-exposed-demo/compose.yaml` defines one
`postgres:16-alpine` service with matching database/user/password defaults and
a PostgreSQL health check. Its published port binds to `127.0.0.1`, not all
network interfaces. These credentials are local demo values only.
Neither credentials nor the R2DBC URL may appear in HTTP responses, event
payloads, readiness details, or logs.

The embedded Ktor server binds to `127.0.0.1` by default. README limitations
state that changing the host or exposing PostgreSQL beyond loopback requires
application-owned authentication, authorization, TLS, secret management, and
network policy.

The copy-paste startup sequence is run from the repository root and waits for
PostgreSQL before Gradle starts:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  up -d --wait --wait-timeout 60 postgres
./gradlew :examples-ktor-exposed-demo:run
```

If the wait fails, README instructs the user to run
`docker compose -f examples/ktor-exposed-demo/compose.yaml ps` and
`docker compose -f examples/ktor-exposed-demo/compose.yaml logs postgres`,
repair the port/container problem, and rerun the same bounded wait. The Compose port is
`127.0.0.1:${DEMO_POSTGRES_PORT:-5432}:5432`; users selecting another port must
set the matching `DEMO_POSTGRES_R2DBC_URL`.

Compose uses a named PostgreSQL data volume. Normal stop is
`docker compose -f examples/ktor-exposed-demo/compose.yaml down`, which retains
that volume. A deliberate clean reset is
`docker compose -f examples/ktor-exposed-demo/compose.yaml down -v --remove-orphans`,
which destroys local demo data.
README includes both commands, calls out the destructive reset, and explains
port-conflict/restart recovery in both locales.

The R2DBC runtime dependency changes from H2 to the repository's existing
`libs.r2dbc.postgresql` alias. Testcontainers uses the existing
`libs.testcontainers.postgresql` alias. No dependency version or catalog
authority changes in this issue.

## Resource Ownership and Lifecycle

The application creates and owns:

- Hikari-backed H2 JDBC `Database`;
- JDBC blocking dispatcher;
- PostgreSQL R2DBC `ConnectionPool`;
- `R2dbcDatabase`;
- `OrderR2dbcCaffeineRepository`;
- `OrderEventPublisher` implementation.

Because the generic cache repository resolves Exposed's process-wide default
R2DBC database, the demo permits only one active `KtorExposedDemoResources`
lifecycle per JVM. A process-local atomic lease is acquired before mutating the
default, rejects an overlapping second demo lifecycle with a stable startup
failure, and is released after default restoration on both normal and partial
startup cleanup. Code outside this demo can still mutate Exposed's global
default without taking that lease, so the README explicitly requires the demo
to be the sole default-database owner while it runs; production integrations
should use an application-wide ownership policy or explicit database binding.

Startup order:

1. create JDBC resources;
2. capture the R2DBC `TransactionManager.defaultDatabase`;
3. create PostgreSQL connection factory/pool and `R2dbcDatabase`, register it,
   and make it the explicit R2DBC default used by parameterless repository
   transactions;
4. create the R2DBC schema;
5. create the Caffeine repository and event publisher;
6. install Ktor integrations/routes and create/start the embedded server.

`main` delegates to a testable top-level runner that owns both the embedded
server and `KtorExposedDemoResources`. The runner registers the normal
`ApplicationStopped` close hook, then wraps engine creation, bind, and
`start(wait = true)` in `try/finally`. The `finally` path stops a partially
created/started engine when needed and calls the same idempotent resource
close as a fallback. Startup is therefore failure-atomic for acquired
resources even when route installation, engine creation, bind, or start fails.
The original startup failure remains primary and each cleanup failure is
attached as suppressed evidence for direct diagnostics without being rendered
or logged by the demo HTTP surface.

Shutdown order:

1. close `OrderR2dbcCaffeineRepository` so its cache/scope stop before the
   connection pool disappears;
2. call the R2DBC
   `TransactionManager.closeAndUnregister(r2dbcDatabase)`, then restore the
   previously captured default R2DBC database only when the current default is
   null after unregistering the demo, without closing or unregistering that
   prior caller-owned database; never overwrite a different non-null default
   installed by external code;
3. dispose the R2DBC pool with the existing bounded wait;
4. close Hikari;
5. close the JDBC dispatcher.

Resource close remains idempotent/best-effort across resources and attempts
later cleanup even when an earlier close fails. Its internal close report
retains failures from repository close, R2DBC unregister/default restoration,
pool disposal, Hikari close, and dispatcher close so the top-level runner can
classify the final exit without logging throwable text.

This loopback teaching demo exposes no readiness-drain state. On normal
shutdown the embedded engine first stops accepting new traffic with a
one-second grace period and a five-second stop timeout. Ktor force-completes
engine shutdown after that timeout; `ApplicationStopped` then closes
application resources in the order above. Engine stop failure or timeout does
not skip repository/default-database/pool/JDBC cleanup. Any application-resource
cleanup failure observed by the demo is aggregated into one
`code=DEMO_SHUTDOWN_FAILED correlationId=<UUID> component=ktor-exposed-demo phase=shutdown outcome=degraded`.
The runner returns status `2` after emitting that single sanitized record.
The top-level `finally` is an idempotent fallback for startup/engine failures.
Ktor 3.5's `EmbeddedServer.stop()` catches and framework-logs an internal engine
stop exception instead of exposing it to the caller, so this example does not
claim to classify that framework-owned failure as exit `2`; it proves the real
one-second/five-second configuration and continued `ApplicationStopped`
resource cleanup instead. Production deployments own framework-log policy and
engine-level shutdown observability.
Production orchestrators own traffic withdrawal, longer in-flight request
budgets, and readiness drain before process termination.

The pool keeps the existing local-demo bounds (`initialSize = 1`,
`maxSize = 2`) and adds a five-second maximum acquire time. README states that
this is a small teaching configuration, not production capacity guidance.

## Readiness Semantics

The installer uses:

```kotlin
ExposedKtorCacheReadinessConfig(
    listOf(
        ExposedKtorCacheContributor.r2dbcRepository("orders") {
            repository.validateConsistency()
        }
    )
)
```

`/readyz/exposed` includes `jdbc`, `r2dbc`, and `cache.orders` in stable
installation order. The R2DBC probe checks PostgreSQL connectivity. The cache
contributor reads only the repository's O(1), side-effect-free, in-memory
consistency state; it is not a Caffeine backend probe and performs no database,
cache, network, or file I/O.

In `WRITE_THROUGH`, the cache worker state is `NOT_APPLICABLE` and maps to UP
unless another consistency failure is reported. README text must not describe
this as proof that every cache value equals PostgreSQL.
When PostgreSQL becomes unavailable, `/healthz/exposed` remains probe-free
liveness while `/readyz/exposed` returns the existing sanitized `503` within
the configured bounded R2DBC timeout.

## Failure Modes

### 1. PostgreSQL write fails after Caffeine was updated

- Signal: `repository.put()` throws.
- Behavior: invalidate the order key, do not call the publisher, preserve the
  aggregate event buffer, and throw `OrderPersistenceException` with the
  original persistence failure retained as its cause.
- Proof: service test with a repository double plus a PostgreSQL integration
  assertion that no unpersisted cache value is returned after failure.

### 2. Event publisher fails after persistence

- Signal: synchronous publisher throws.
- Behavior: the service propagates failure; the route emits
  `503 ORDER_EVENT_HANDOFF_FAILED`; the confirmed record remains persisted;
  aggregate events remain only while the caller retains that request-local
  aggregate.
- Proof: service test retains the aggregate reference and verifies buffer/order,
  plus repository state verification.

### 3. Invalid or repeated confirmation request

- Invalid/noncanonical/oversized/nil UUID: return constant `400` without
  invoking service dependencies.
- Missing/wrong `X-Demo-Command`: return constant `403` without invoking
  service dependencies.
- Already confirmed order: sequentially return its current representation
  without another repository write or duplicate event.
- Concurrent confirmation is explicitly not idempotent and requires a stronger
  production command boundary.
- Proof: route tests, sequential idempotency test, and a concurrency test that
  records the unsupported duplicate-publication risk.

### 4. PostgreSQL unavailable at startup or readiness time

- Startup schema initialization failure prevents the demo server from starting
  and preserves the original cause for local diagnostics.
- Runtime readiness uses the existing sanitized R2DBC DOWN/timeout response and
  never exposes URL, credentials, SQL, or exception text.
- Proof: after a successful integration start, stop PostgreSQL and assert that
  probe-free liveness remains `200` while readiness completes within the
  bounded timeout with sanitized `503` details.

### 5. Resource shutdown after partial failure

- The repository closes before the pool.
- The demo R2DBC database is unregistered and the prior Exposed default is
  restored before pool disposal.
- Later resources are still closed if an earlier step fails.
- Repeated close does not double-use closed resources or throw to Ktor's
  application shutdown hook.
- Concurrent close calls return the same report and execute each closer once.
- An overlapping second demo lifecycle is rejected; a sequential second
  lifecycle succeeds after the first releases its ownership lease.
- Proof: focused lifecycle barriers/doubles and exact observable close ordering.

Startup tests inject failures at R2DBC acquisition, schema initialization, and
embedded-engine create/bind/start. They verify reverse cleanup, R2DBC default
restoration/unregistration, engine/resource fallback cleanup, preservation of
the primary cause, startup exit status `1`, useful allowlisted diagnostic
fields, and exclusion of
primary/suppressed secret-bearing text from captured output.
An actual loopback `EmbeddedServer` test proves the configured one-second/five-
second values and that `ApplicationStopped` cleanup completes before the
blocking start returns. Separate repository/pool/JDBC cleanup-failure doubles
prove later cleanup still runs and uses the same aggregated record/status `2`.

### 6. Testcontainers or Docker unavailable

- Fast domain/service tests remain runnable without Docker.
- PostgreSQL integration evidence is run sequentially when Docker is available
  locally through `postgresIntegrationTest`; Docker unavailability fails that
  explicitly requested task rather than silently falling back to H2.
- Existing CI continues to run the fast non-Docker `test` task. Wiring the
  dedicated Docker task into CI is deliberately outside this issue, so local
  serialized Testcontainers evidence is required before PR creation.

## Test Strategy

### Fast tests

- `DemoOrder` records one event and is idempotent after confirmation.
- rehydration creates no historical events.
- successful service confirmation writes before publication and clears events.
- persistence failure invalidates the key, skips publication, retains events,
  and exposes the original throwable as the typed failure's cause.
- publisher failure leaves the stored record and retains ordered events.
- cancellation during persistence invalidates the local key in a bounded
  non-cancellable cleanup and rethrows the same cancellation instance.
- cancellation before write, during an ambiguous commit, and after successful
  repository return is gated by `ensureActive()`: cancellation observed at the
  gate invalidates and does not publish; after the gate, prompt synchronous
  publication intentionally completes without another suspension point.
- already confirmed input performs no write and no publication for sequential
  calls; a concurrency test documents that the example does not serialize two
  simultaneous pending confirmations.
- invalid, noncanonical, oversized, and nil UUID input returns constant `400`;
  missing/wrong command header returns constant `403`; neither accesses
  dependencies.
- POST validates the command header before the ID, including the combined
  invalid-header/invalid-ID case.
- primary/suppressed secret-bearing failures produce constant `503` bodies and
  no secret, SQL, URL, credential, stack trace, or submitted-input text in
  captured application logs.
- startup acquisition/schema failures close acquired resources in reverse order
  and preserve the original cause.
- engine create/bind/start failure invokes the idempotent top-level fallback,
  closes resources, and emits only the allowlisted startup diagnostic fields.

The exact Docker-free command is:

```bash
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
```

Focused event-handoff proof is:

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests "*OrderCommandServiceTest" --no-daemon --console=plain
```

### Sequential PostgreSQL Testcontainers tests

- reuse one suite-level `postgres:16-alpine` container sequentially for the
  normal scenario, readiness, and default-restoration cases, injecting its
  host, mapped port, database, user, and password into
  `KtorExposedDemoConfig`;
- let only the destructive outage case own a second independent container that
  it can stop; each test still creates/closes fresh application resources and
  every resource closes before its owning container;
- verify `/transactions/jdbc-count` and `/transactions/r2dbc-count`;
- confirm a client-generated UUID order and verify PostgreSQL persistence;
- invalidate the Caffeine key, GET the order, and prove read-through repopulates
  it from PostgreSQL;
- inspect the repository's public Caffeine cache before and after that GET,
  then perform a second direct repository GET and prove it returns the same
  cached `OrderRecord` instance while the cache entry remains unchanged; this
  is the demo-level warm-cache proof without turning driver SQL text/count into
  a public contract;
- confirm the same order again and prove no duplicate event;
- verify readiness details contain `jdbc`, `r2dbc`, and `cache.orders` as UP;
- stop PostgreSQL after startup and verify liveness `200`, readiness sanitized
  `503`, and bounded completion;
- close resources and container sequentially.
- run a second create/use/close lifecycle sequentially to prove the process-wide
  R2DBC default is replaced and no closed-pool state contaminates the next
  lifecycle.

Testcontainers/real database commands must not run in parallel with another
heavy backend test in this repository workflow.

`build.gradle.kts` defines a separate `postgresIntegrationTest` source set/task
that owns `src/postgresIntegrationTest/kotlin`. The ordinary `test` task neither
depends on nor discovers those classes. The exact Docker-required command is:

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

Every container/resource lifecycle uses `try/finally`; repository/resources
close before the container. The integration test class owns one active
PostgreSQL lifecycle at a time and disables parallel execution because the
generic repository uses Exposed's process-wide default R2DBC database.

## Documentation Design

Both README locales use this order:

1. Overview / 개요
2. Example Scenario / 예제 시나리오
3. Architecture / 아키텍처
4. Order Confirmation Sequence / 주문 확인 시퀀스
5. Project Structure / 프로젝트 구조
6. Resource Ownership / Resource 소유권
7. Routes
8. Run with PostgreSQL / PostgreSQL로 실행
9. Testing / 테스트
10. Behavior and Limitations / 동작 및 제한
11. See Also / 같이 보기

The two files keep route paths, environment variable names, Compose/Gradle/curl
commands, response examples, diagram paths, and limitations semantically and
technically identical. Korean prose is natural rather than mechanically
translated; code identifiers remain unchanged.

Both READMEs provide this exact repository-root walkthrough, labeled by
terminal:

```bash
# Terminal 1: start PostgreSQL, then run the demo
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  up -d --wait --wait-timeout 60 postgres
./gradlew :examples-ktor-exposed-demo:run

# Terminal 2: inspect the running demo
BASE_URL=http://127.0.0.1:8080
ORDER_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

curl -i "$BASE_URL/healthz/exposed"
curl -i "$BASE_URL/readyz/exposed"
curl -i "$BASE_URL/transactions/jdbc-count"
curl -i "$BASE_URL/transactions/r2dbc-count"

curl -i -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
curl -i "$BASE_URL/orders/$ORDER_ID"
curl -i "$BASE_URL/transactions/r2dbc-count"

# Sequential retry: the response stays CONFIRMED and eventPublished is false
curl -i -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
```

Expected evidence is explicit: health/readiness/count requests return `200`;
readiness contains `jdbc`, `r2dbc`, and `cache.orders`; the first POST returns
`200` with `CONFIRMED` and `eventPublished: true`; GET returns the same ID,
status, and timestamp; R2DBC count increases for the new random ID; the second
POST returns `200` with `eventPublished: false`. The READMEs then instruct the
user to stop Gradle with Ctrl-C and choose one Compose command:

```bash
# Normal stop: keep the named PostgreSQL volume
docker compose -f examples/ktor-exposed-demo/compose.yaml down

# Destructive clean reset: delete demo data and remove orphans
docker compose -f examples/ktor-exposed-demo/compose.yaml \
  down -v --remove-orphans
```

The limitation section explicitly covers loopback-only defaults, the required
demo command header, no permissive CORS, production auth/TLS ownership,
cache-first transient dirty reads, sequential-only idempotency, ambiguous
commit cancellation, request-local event retention, the generic three-SQL
missing-order path, and the small two-connection pool.

It also gives caller recovery guidance: after any order-command `503`, GET the
order to reconcile observed database state. Repeating POST is safe for the
sequential confirmed state but does **not** recover or republish a lost event.
In particular, `ORDER_EVENT_HANDOFF_FAILED` means the record was stored while
the non-durable event handoff failed; production recovery requires an outbox or
another durable delivery boundary.

The startup schema DDL is explicitly demo-only, requires a PostgreSQL role with
DDL permission, and is not a substitute for versioned production migrations or
rolling-deployment compatibility. If a retained local volume contains an
incompatible demo schema, the README points to the clearly destructive
`down -v --remove-orphans` reset before rerunning Compose.

Both README files link to the service and publisher source plus the focused
`OrderCommandServiceTest` command. They state that HTTP exposes only the
per-request `eventPublished` outcome and no event history/replay endpoint.

## Diagram Design

Canonical pairs:

```text
docs/images/readme-diagrams/
  examples-ktor-exposed-demo-architecture-01.svg
  examples-ktor-exposed-demo-architecture-01.png
  examples-ktor-exposed-demo-sequence-01.svg
  examples-ktor-exposed-demo-sequence-01.png
```

### Architecture Diagram

Shows static responsibilities and ownership only:

- HTTP Client;
- Ktor Routes;
- `OrderCommandService`;
- `DemoOrder` aggregate;
- `OrderR2dbcCaffeineRepository`;
- Caffeine local cache;
- PostgreSQL R2DBC source of truth;
- application-owned Spring-free event publisher;
- cache readiness contributor;
- application ownership boundary and repository-before-pool shutdown note.

### Sequence Diagram

Shows time-ordered order confirmation:

1. request reaches Ktor route;
2. service loads through repository;
3. `alt` cache hit versus miss → PostgreSQL → cache fill;
4. aggregate records `OrderConfirmed`;
5. repository write-through updates Caffeine then attempts PostgreSQL;
6. `alt` persistence success versus failure compensation/invalidate;
7. only after successful repository return, publisher handoff begins;
8. `alt` publisher success clears events versus publisher failure retains them;
9. route returns success or sanitized failure.

It is a true sequence visual rather than a generic flowchart: every participant
has a labeled lifeline, active work uses activation bars, the main teaching
steps have visible numbered pills, and cache/persistence/publisher alternatives
use transparent branch frames whose labels do not obscure connectors.

The diagram must explicitly avoid an atomic cache/database visual. It uses the
approved dark visual family, approved fonts, fixed marker units, readable
arrowheads, and the repository's full SVG/XML, CairoSVG `-s 2`, connector,
geometry, endpoint, mixed-corner, sequence-style, and full-size PNG inspection
gates. The sequence SVG must pass
`~/.codex/skills/bluetape-diagram/scripts/diagram-sequence-style-audit.py`.

Each locale embeds the PNG with natural, semantically equivalent alt text and
links the canonical SVG source immediately below it. Adjacent prose serves as
the accessible legend: it explains solid request/data flow, dashed readiness
or compensation flow, every `alt` branch, success/failure colors, and the
repository-before-pool shutdown boundary. The same prose explicitly states
that Caffeine is updated before PostgreSQL in write-through mode and that the
two stores are not atomic, so no critical sequence or failure meaning exists
only in color or pixels.

## Compatibility and Migration

- This is an unpublished example module, so no binary compatibility surface is
  added.
- Existing JDBC route/behavior remains available.
- The R2DBC runtime now requires PostgreSQL instead of an in-memory H2 database;
  Compose and environment configuration are therefore part of the same change.
- No existing production library or catalog version changes.
- Stable manuals remain pinned to the prior release behavior and are not
  rewritten from `develop`.
- Rollback is local: restore the example's H2 R2DBC dependency/resources,
  remove order/cache/event routes and assets, and retain the unchanged Ktor
  library modules.

## Design Review Record

The final 2026-07-17 review pass converged after every finding was repaired:

| Lens | P0 | P1 | P2 | P3 | Result |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | READY |
| Stability/concurrency | 0 | 0 | 0 | 0 | READY |
| Security/privacy | 0 | 0 | 0 | 0 | READY |
| Ops/operator | 0 | 0 | 0 | 0 | READY |
| Developer/API | 0 | 0 | 0 | 0 | READY |
| User/caller, bilingual docs, diagrams | 0 | 0 | 0 | 0 | READY |
| Main-session integration | 0 | 0 | 0 | 0 | READY |

Main integration rechecked the actual aggregate/event signatures, R2DBC
`TransactionManager` lifecycle, Caffeine repository suspend/public-cache
contracts, Ktor kotlinx serialization/plugin surface, exact route media types,
Compose commands, Markdown fence balance, and scope exclusions. The design is
ready for the executable TDD plan, which has now received the same review
treatment; implementation may begin after the decision artifacts are committed.

## Acceptance Criteria

- The Ktor demo presents a runnable Order Confirmation scenario using a Ktor
  route, `OrderCommandService`, a Spring-neutral aggregate, an R2DBC Caffeine
  repository, and an application-owned event publisher.
- The order path uses PostgreSQL through R2DBC, while the existing JDBC smoke
  path remains H2-backed and independently owned.
- The module declares the R2DBC Caffeine project dependency directly and keeps
  PostgreSQL/Testcontainers versions under the existing catalog authority.
- The example applies the existing Kotlin serialization plugin and every HTTP
  DTO has a generated kotlinx serializer used by Ktor ContentNegotiation.
- `/transactions/r2dbc-count` exercises the existing Ktor R2DBC transaction
  helper without wrapping cache repository operations in a nested transaction.
- Order reads demonstrate read-through, and order confirmation uses
  deterministic write-through.
- The command service hands domain events to the publisher only after the
  repository operation succeeds.
- Successful handoff clears the event buffer; publisher failure preserves the
  ordered events on the caller-owned aggregate.
- Persistence failure performs best-effort cache invalidation, no event handoff,
  and retains the original persistence failure and aggregate events.
- Repeated sequential confirmation is idempotent and emits no duplicate event;
  concurrent confirmation is documented as unsupported without a stronger
  production boundary.
- `/readyz/exposed` reports `jdbc`, `r2dbc`, and sanitized `cache.orders`
  readiness with their documented meanings.
- The application owns resources, closes the repository, unregisters the demo
  R2DBC database/restores the prior default, and only then disposes the pool.
- Engine create/bind/start failure closes already-acquired resources and exits
  non-zero with only a stable sanitized startup diagnostic.
- Local execution is documented with module-local Docker Compose, environment
  configuration, Gradle run, and a copy-pasteable curl sequence.
- Ktor/PostgreSQL bind to loopback by default; the mutating route requires the
  exact demo command header, exposes no permissive CORS, and returns only
  constant secret-free failures.
- Fast tests remain available without Docker; PostgreSQL behavior is covered by
  a sequential Testcontainers integration test.
- PostgreSQL outage evidence distinguishes probe-free liveness `200` from
  bounded sanitized readiness `503`.
- README locales contain equivalent scenario, diagrams, ownership, routes, run,
  test, and limitation sections.
- Matching canonical SVG/PNG architecture and sequence assets pass all diagram
  audits and full-size PNG inspection.
- No Spring, Spring Modulith, JaVers, production API, module, publishing,
  catalog, or issue #322 change is introduced.

## Definition of Done

- Spec and plan reviews converge at P0=0/P1=0 across performance, stability,
  security, Ops, developer/API, user/caller, and main integration.
- TDD evidence covers success, persistence failure compensation, publisher
  failure retention, idempotency, invalid input, read-through, readiness, and
  resource lifecycle.
- Targeted fast and sequential PostgreSQL Testcontainers tests pass freshly.
- After the final implementation/docs commit, rerun
  `postgresIntegrationTest --no-parallel` on a clean worktree and record the
  exact `git rev-parse HEAD`, command, and result in the PR's final DoD evidence;
  any later head change invalidates that local PostgreSQL proof and requires a
  rerun.
- README parity, Kotlin diagnostics, Gradle compilation/tests, `detekt` for the
  affected scope when available, and `git diff --check` pass.
- Both SVG/PNG pairs pass XML/render/connector/geometry/endpoint/mixed-corner
  audits; the sequence SVG additionally passes
  `diagram-sequence-style-audit.py`; both PNGs are inspected at full size after
  their final render.
- A durable lesson captures the write-through compensation and event-handoff
  boundary before PR creation.
- The PR targets `develop`, mirrors issue #326 metadata, ends with final
  `## DoD Status`, and reaches green required CI on the exact head before a
  fresh merge approval is requested.
