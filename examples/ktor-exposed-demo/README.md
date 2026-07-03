# examples-ktor-exposed-demo

English | [한국어](./README.ko.md)

Ktor smoke demo for `bluetape4k-exposed-ktor`. It shows how an application can
compose bluetape4k Ktor core, Exposed-specific `StatusPages` mappings,
caller-owned JDBC/R2DBC resources, readiness routes, and a JDBC transaction
route.

## Overview

This module is intentionally small. The demo application creates local H2 JDBC
and R2DBC resources for the example process, passes those resources into
`installBluetape4kExposedKtor()`, and closes them from the Ktor application
lifecycle.

The library contract stays explicit:

- `installBluetape4kExposedKtor()` does not create databases, pools,
  dispatchers, content negotiation, or generic Ktor core plugins.
- The application owns `Database`, `R2dbcDatabase`, the JDBC dispatcher, and
  their shutdown lifecycle.
- Ktor core and Exposed error mappings are composed in one `StatusPages` block.
- Exposed health/readiness routes are installed only because the demo opts in
  with `installHealthRoutes = true`.

## Project Structure

```text
src/main/kotlin/io/bluetape4k/examples/exposed/ktor/
├── KtorExposedDemoApplication.kt    # Ktor plugin composition and routes
└── KtorExposedDemoResources.kt      # H2 JDBC/R2DBC resources for the demo

src/test/kotlin/io/bluetape4k/examples/exposed/ktor/
└── KtorExposedDemoApplicationTest.kt # health, readiness, transaction smoke test
```

## Resource Ownership

`KtorExposedDemoResources.create()` builds the demo-local resources:

- HikariCP-backed H2 JDBC `Database`
- H2 R2DBC `ConnectionPool` and `R2dbcDatabase`
- fixed-size JDBC blocking dispatcher
- `ktor_demo_items` table with two sample rows

The application subscribes to `ApplicationStopped` and closes those resources
explicitly. Production applications should follow the same ownership rule, but
usually create the pools and dispatcher from their own configuration layer.

## Ktor Composition

`installKtorExposedDemo(resources)` installs the plugins in this order:

1. `installBluetape4kKtorCore(...)` with status pages and generic health routes
   disabled.
2. One shared `StatusPages` block with `bluetape4kErrorResponses()` and
   `bluetape4kExposedErrors()`.
3. `installBluetape4kExposedKtor(...)` with caller-owned JDBC/R2DBC resources
   and `installHealthRoutes = true`.
4. A demo route that reads `DemoItems` through
   `ApplicationCall.exposedJdbcTransaction()`.

## Routes

| Method | Path | Description |
|---|---|---|
| GET | `/healthz/exposed` | Exposed integration health route |
| GET | `/readyz/exposed` | JDBC/R2DBC readiness route |
| GET | `/transactions/jdbc-count` | Counts demo rows through a JDBC transaction |

Expected smoke responses:

```bash
curl http://localhost:8080/healthz/exposed
curl http://localhost:8080/readyz/exposed
curl http://localhost:8080/transactions/jdbc-count
```

`/transactions/jdbc-count` returns `2` after the demo resources insert the two
sample rows.

## Running

Run the smoke test:

```bash
./gradlew :examples-ktor-exposed-demo:test
```

Run the application:

```bash
./gradlew :examples-ktor-exposed-demo:run
```

The embedded Netty server starts on port `8080`.

## See Also

- [`bluetape4k-exposed-ktor`](../../ktor/exposed/README.md) — public API,
  readiness semantics, rollback guidance, and non-goals
