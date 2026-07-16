# Ktor Exposed Demo

[한국어](README.ko.md)

## Overview

This runnable Ktor example combines:

- H2 JDBC for a small transaction-count route;
- PostgreSQL R2DBC for the Order Confirmation scenario;
- `OrderR2dbcCaffeineRepository` in `WRITE_THROUGH` mode;
- a Spring-neutral aggregate and application-owned event publisher;
- JDBC, R2DBC, and cache readiness contributors;
- explicit resource acquisition, Exposed default-database restoration, and bounded pool disposal.

The example is intentionally small enough to trace from HTTP to PostgreSQL. It
also makes the places that are *not* production guarantees visible.

## Example Scenario

A client creates a canonical lowercase UUID and confirms that order.

1. `POST /orders/{orderId}/confirm` validates the teaching header and UUID.
2. `OrderCommandService` loads or creates a pending `DemoOrder`.
3. The aggregate moves from `PENDING` to `CONFIRMED` and records
   `OrderConfirmed`.
4. The R2DBC Caffeine repository updates the local cache, then writes
   PostgreSQL.
5. Only after persistence returns does the application-owned publisher receive
   the event; successful handoff clears the aggregate buffer.
6. A repeated sequential confirmation returns `eventPublished=false` and does
   not create another row or event.

## Architecture

![Architecture of the Ktor order confirmation example](../../docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.png)

[Open the canonical architecture SVG](../../docs/images/readme-diagrams/examples-ktor-exposed-demo-architecture-01.svg)

Legend: blue arrows are request/repository calls, purple arrows are readiness
or event handoff, amber arrows show shutdown order, green is the aggregate,
amber is cache/database state, and purple is the non-durable publisher. The
application owns every component inside the dashed boundary. Caffeine changes
before PostgreSQL, so that path is deliberately not drawn as one atomic box.
Shutdown closes the repository before unregistering the Exposed database and
disposing its pool.

## Order Confirmation Sequence

![Sequence of cache lookup, persistence, compensation, and event handoff](../../docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.png)

[Open the canonical sequence SVG](../../docs/images/readme-diagrams/examples-ktor-exposed-demo-sequence-01.svg)

Legend: solid blue lines are calls, dashed amber lines are returns, green frames
are successful branches, blue frames are cache-miss work, and orange frames are
failure branches. Numbered pills show execution order. Step 9 mutates Caffeine
before step 10 writes PostgreSQL. A persistence failure invalidates the cache;
a publisher failure retains the aggregate event because no durable owner took
it.

## Project Structure

```text
examples/ktor-exposed-demo/
├── compose.yaml
├── build.gradle.kts
├── src/main/kotlin/io/bluetape4k/examples/exposed/ktor/
│   ├── KtorExposedDemoApplication.kt
│   ├── KtorExposedDemoResources.kt
│   └── order/
│       ├── OrderDomain.kt
│       ├── OrderRepository.kt
│       ├── OrderCommandService.kt
│       └── OrderRoutes.kt
├── src/test/                       # Docker-free contract tests
└── src/postgresIntegrationTest/    # Sequential Testcontainers proof
```

## Resource Ownership

`KtorExposedDemoResources` owns the Hikari data source, JDBC dispatcher,
PostgreSQL R2DBC pool/database, concrete repository, publisher, and command
service. One process may hold only one demo lifecycle lease because repository
transactions resolve Exposed's process-wide default R2DBC database.

Construction is failure-atomic: completed steps unwind in reverse order and
cleanup failures are suppressed on the original failure. Normal shutdown runs
once, even when `ApplicationStopped` and the runner race:

```text
repository.close
  -> TransactionManager.closeAndUnregister
  -> restore the captured default only when the current default is null
  -> dispose the R2DBC pool (bounded to five seconds)
  -> close Hikari and the JDBC dispatcher
  -> release the lifecycle lease
```

## Routes

| Method | Path | Request | Success media type | Purpose |
|---|---|---|---|---|
| `GET` | `/healthz/exposed` | none | `application/json` | Liveness; does not probe PostgreSQL |
| `GET` | `/readyz/exposed` | none | `application/json` | JDBC, R2DBC, and `cache.orders` readiness |
| `GET` | `/transactions/jdbc-count` | none | `text/plain` | H2 JDBC sample count (`2`) |
| `GET` | `/transactions/r2dbc-count` | none | `text/plain` | PostgreSQL order count |
| `POST` | `/orders/{orderId}/confirm` | **bodyless**, header `X-Demo-Command: confirm-order` | `application/json` | Confirm an order |
| `GET` | `/orders/{orderId}` | none | `application/json` | Reconcile/read stored order state |

First confirmation:

```json
{
  "orderId": "018f6f95-7f4a-7a20-8b52-70ad30c30f36",
  "status": "CONFIRMED",
  "updatedAt": "2026-07-17T00:01:00Z",
  "eventPublished": true
}
```

Read response:

```json
{
  "orderId": "018f6f95-7f4a-7a20-8b52-70ad30c30f36",
  "status": "CONFIRMED",
  "updatedAt": "2026-07-17T00:01:00Z"
}
```

| Status | Code | Message |
|---|---|---|
| `400` | `INVALID_ORDER_ID` | `Order id must be a canonical non-nil UUID.` |
| `403` | `DEMO_COMMAND_REQUIRED` | `Required demo command header is missing or invalid.` |
| `404` | `ORDER_NOT_FOUND` | `Order was not found.` |
| `503` | `ORDER_PERSISTENCE_FAILED` | `Order could not be stored.` |
| `503` | `ORDER_EVENT_HANDOFF_FAILED` | `Order was stored but its event was not handed off.` |
| `503` | `ORDER_CONFIRMATION_FAILED` | `Order confirmation failed.` |
| `503` | `ORDER_READ_FAILED` | `Order could not be loaded.` |

Only `503` responses carry a generated UUID `correlationId`. It links the
sanitized response to one allowlisted stderr diagnostic record. It is neither a
retry token nor an event-republication token.

## Run with PostgreSQL

Run all commands from the repository root.

Start PostgreSQL:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml up -d --wait
```

Terminal 1 — start Ktor:

```bash
./gradlew :examples-ktor-exposed-demo:run
```

Terminal 2 — create an order ID and inspect the example:

```bash
BASE_URL=http://127.0.0.1:8080
ORDER_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')

curl -fsS "$BASE_URL/healthz/exposed"
curl -fsS "$BASE_URL/readyz/exposed"
curl -fsS "$BASE_URL/transactions/jdbc-count"
curl -fsS "$BASE_URL/transactions/r2dbc-count"

curl -fsS -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
curl -fsS "$BASE_URL/orders/$ORDER_ID"
curl -fsS -X POST \
  -H 'X-Demo-Command: confirm-order' \
  "$BASE_URL/orders/$ORDER_ID/confirm"
curl -fsS "$BASE_URL/transactions/r2dbc-count"
```

Expected: readiness lists `jdbc`, `r2dbc`, and `cache.orders`; JDBC count is
`2`; the first POST returns `eventPublished=true`; GET returns the same ID,
status, and timestamp; the repeated POST returns `eventPublished=false`; and
the R2DBC count increases by one.

Stop while retaining the named PostgreSQL volume:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml down
```

Destructively remove the local data volume and orphans:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml down -v --remove-orphans
```

If port `5432` is busy, inspect it and run both Compose and Ktor with an
alternate loopback port:

```bash
lsof -nP -iTCP:5432 -sTCP:LISTEN
DEMO_POSTGRES_PORT=55432 docker compose -f examples/ktor-exposed-demo/compose.yaml up -d --wait
DEMO_POSTGRES_R2DBC_URL=r2dbc:postgresql://localhost:55432/ktor_exposed_demo \
  ./gradlew :examples-ktor-exposed-demo:run
```

## Testing

The normal suite is Docker-free. PostgreSQL verification is explicit and
serialized.

```bash
./gradlew :examples-ktor-exposed-demo:test \
  --tests '*OrderCommandServiceTest' --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:test --no-daemon --console=plain
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest \
  --no-parallel --no-daemon --console=plain
```

The service/publisher boundary is implemented in
[`OrderCommandService.kt`](src/main/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandService.kt)
and locked by
[`OrderCommandServiceTest.kt`](src/test/kotlin/io/bluetape4k/examples/exposed/ktor/order/OrderCommandServiceTest.kt).

## Behavior and Limitations

- Loopback binding and `X-Demo-Command` are teaching guards, not production authentication.
- The application installs no permissive CORS policy; the demo header is only a browser-origin teaching guard.
- Compose credentials `demo/demo` are local-only and must never be reused as deployment secrets.
- Any external binding requires application-owned authentication, authorization, TLS, secret management, and network policy.
- Startup DDL requires DDL permission and is not a migration system.
- Caffeine/PostgreSQL write-through is not atomic and has a transient dirty-read window.
- Confirmation is idempotent only for sequential calls.
- Cancellation can leave an ambiguous PostgreSQL commit.
- Event handoff is request-local and non-durable.
- After any confirmation-command `503`, GET reconciles stored state, but repeating POST does not recover an event; `ORDER_READ_FAILED` means that reconciliation endpoint is itself temporarily unavailable.
- `ORDER_EVENT_HANDOFF_FAILED` requires an outbox or another durable production boundary.
- The missing-order path may perform `SELECT`, `UPDATE`, and `INSERT`.
- The two-connection pool is demonstration sizing only.
- The five-second acquire bound covers waiting for a pooled connection, not active SQL, DDL, or PostgreSQL lock time; production must configure statement/lock timeouts and migrations.
- Only one demo resources lifecycle may own Exposed's process-wide default R2DBC database at a time; external code must not replace that default while the demo runs.
- The stderr diagnostic sink is synchronous and may block under output backpressure; production should use bounded structured logging.
- Ktor owns internal engine-stop exception logging; status `2` covers application-resource cleanup failures, while production owns engine-level log policy and shutdown observability.
- The demo exposes no readiness drain; production owns traffic withdrawal.

## See Also

- [`bluetape4k-exposed-ktor`](../../ktor/exposed/README.md)
- [`bluetape4k-exposed-r2dbc-caffeine`](../../exposed/r2dbc-caffeine/README.md)
- [Ktor demo resources](src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt)
- [PostgreSQL integration proof](src/postgresIntegrationTest/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoPostgresIntegrationTest.kt)
