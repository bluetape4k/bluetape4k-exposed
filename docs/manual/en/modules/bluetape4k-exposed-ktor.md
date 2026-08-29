---
manualId: "bluetape4k-exposed-ktor"
id: "bluetape4k-exposed-ktor"
title: "Exposed Ktor Integration"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor"
sourceDir: "ktor/exposed"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor
---

# Exposed Ktor Integration

> Explicit JDBC/R2DBC transaction, readiness, metrics, and error-response helpers for application-owned Ktor infrastructure.

## Problem {#problem}

This module offers opt-in helpers through `installBluetape4kExposedKtor`, `ApplicationCall.exposedJdbcTransaction`, `ApplicationCall.exposedR2dbcTransaction`, and route/status-page extensions. It does not install generic Ktor infrastructure and never creates or closes a database, connection pool, blocking dispatcher, or `MeterRegistry`. The application owns all of those lifecycles.

## When to use it {#when-to-use}

Use it when a Ktor application wants the released Exposed transaction wrappers, `/healthz/exposed` and `/readyz/exposed` routes, bounded readiness probes, Micrometer timings, or client-safe database error responses. The framework does not dictate JDBC versus R2DBC: choose from driver maturity, workload, operational tooling, and the rest of the call path. JDBC is valid in Ktor when isolated on an application-supplied blocking dispatcher.

## Coordinates {#coordinates}

Import the ecosystem BOM and omit the module version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

This artifact remains the compatibility aggregator for existing consumers. New
applications should select `bluetape4k-exposed-ktor-core` plus exactly the
backend adapter they use, including `-tenant-jdbc` or `-tenant-r2dbc` when the
database is selected by `TenantId`. The selective child artifacts and their
manuals are develop-only during the 2.0 migration and are not published in
stable `1.12.1`; a stable consumer must keep this aggregator coordinate. The
legacy coordinates and packages stay available during the migration window;
move imports and dependencies one backend at a time before removing the
aggregator from a future major release.

## Core concepts {#concepts}

`Bluetape4kExposedKtorConfig` accepts optional application-owned JDBC `Database`, JDBC blocking dispatcher, R2DBC `R2dbcDatabase`, `MeterRegistry`, route paths, and probe timeouts. Installing the helper only registers the selected StatusPages mapping and health routes. It does not hide startup or shutdown work.

The JDBC transaction helper switches to the caller's dispatcher and runs Exposed `transaction(db = db)`. The R2DBC helper runs Exposed `suspendTransaction(db = db)`. Both rethrow `CancellationException`; other failures are wrapped as `ExposedKtorTransactionException` for shared HTTP mapping.

## Quick start {#quick-start}

Create the database and pool during application wiring. For JDBC, also create a bounded dispatcher sized with the connection pool. Install only the helpers the application needs:

```kotlin
installBluetape4kExposedKtor(
    Bluetape4kExposedKtorConfig(
        jdbcDatabase = database,
        jdbcBlockingDispatcher = jdbcDispatcher,
        installHealthRoutes = true,
        meterRegistry = registry,
    )
)
```

Close the pool and any owned dispatcher in the application's shutdown path; the plugin has no hidden close hook.

## API by task {#api-by-task}

- Run blocking JDBC with `call.exposedJdbcTransaction(database, dispatcher, registry) { ... }`.
- Run coroutine-native R2DBC with `call.exposedR2dbcTransaction(database, registry) { ... }`.
- Add liveness/readiness routes with `bluetape4kExposedHealthRoutes` or the install flag.
- Add database error responses with `bluetape4kExposedErrors()` inside the application's single `StatusPages` block.
- Record transaction and readiness timers by passing a `MeterRegistry`.

## Recommended patterns {#patterns}

Construct one application-scoped infrastructure bundle and inject it into routes or services. Keep transaction blocks in services when several routes share the same use case. Size the JDBC dispatcher no larger than useful pool concurrency, and never run JDBC directly on Ktor's event-loop dispatcher. Preserve structured concurrency and rethrow cancellation after cleanup.

## Integrations {#integrations}

`installBluetape4kExposedKtor` refuses to install its own `StatusPages` block when `StatusPages` already exists. In that case compose mappings once in the application's shared block:

```kotlin
install(StatusPages) {
    bluetape4kErrorResponses()
    bluetape4kExposedErrors()
}
```

The helper does not install content negotiation, bluetape4k Ktor core, a pool, dispatcher, or registry.

## Configuration {#configuration}

Health and readiness paths must be absolute. Probe and JDBC query timeouts must be positive. Enabling health routes requires at least one JDBC or R2DBC database; a JDBC readiness route additionally requires the blocking dispatcher. Defaults are `/healthz/exposed`, `/readyz/exposed`, a one-second readiness timeout, and a one-second JDBC query timeout.

## Failure modes {#failures}

- JDBC database without a dispatcher for health routes: configuration validation fails before route installation.
- `StatusPages` is already installed while `installStatusPages=true`: installation fails; compose all mappings in the existing block.
- JDBC request stalls the server: the call bypassed the supplied blocking dispatcher or the dispatcher/pool is saturated.
- Cancellation becomes an HTTP 500: application code swallowed `CancellationException`; rethrow it.
- Readiness returns `DOWN` or `TIMEOUT`: inspect backend-specific probe timing, pool acquisition, driver error, and configured timeout rather than restarting blindly.

## Operations {#operations}

The liveness route reports the helper component as up without querying the database. Readiness runs `SELECT 1`: JDBC on the supplied dispatcher with a query timeout, R2DBC inside `suspendTransaction`, both under the overall readiness timeout. Optional timers use `bluetape4k.exposed.ktor.transaction` and `bluetape4k.exposed.ktor.readiness` with bounded `backend`, `operation`, and `outcome` tags.

During shutdown, stop accepting traffic, allow structured in-flight calls to finish or cancel, then close the application-owned pool, database resources, dispatcher, and registry as appropriate. The plugin deliberately performs none of those closes.

## Testing {#testing}

Run the exact module tests:

```bash
./gradlew :bluetape4k-exposed-ktor:test
```

Verify JDBC runs on the supplied dispatcher, R2DBC uses `suspendTransaction`, successful and failed transactions are mapped correctly, cancellation is rethrown, duplicate StatusPages installation is rejected, readiness validates required resources, timeout/down outcomes are returned, metrics tags remain bounded, and the application shutdown closes owned resources.

## Workshops and learning path {#workshops}

Run the [Ktor example](examples-ktor-exposed-demo.md), then read [transaction boundaries](../guides/transaction-boundaries.md). Continue with the [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) for JDBC or the [Exposed R2DBC workshop](https://github.com/bluetape4k/exposed-r2dbc-workshop) for a fully suspending database path.

## Limitations {#limitations}

These helpers do not provision infrastructure, create schema, select JDBC or R2DBC for the application, or add a distributed transaction. Liveness is not database readiness, and a successful readiness probe only describes that probe. Exception mapping intentionally returns client-safe messages rather than exposing driver details.

## Sources {#sources}

- [`Bluetape4kExposedKtor.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt)
- [`Bluetape4kExposedKtorConfig.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt)
- [`ExposedKtorTransactions.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactions.kt)
- [`ExposedKtorHealthRoutes.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt)
- [`ExposedKtorMetrics.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetrics.kt)
- [`ExposedKtorStatusPages.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPages.kt)
