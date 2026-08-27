---
manualId: "bluetape4k-exposed-ktor-r2dbc"
id: "bluetape4k-exposed-ktor-r2dbc"
title: "Exposed Ktor R2DBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-r2dbc"
sourceDir: "ktor/r2dbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc
---

# Exposed Ktor R2DBC Adapter

Coroutine-native R2DBC readiness, transaction, and error helpers for the selective Ktor boundary.

## Problem {#problem}

R2DBC consumers should not inherit blocking JDBC APIs or a cache lifecycle they do not use.

## When to use it {#when-to-use}

Use this adapter when the application owns an Exposed `R2dbcDatabase` and uses a coroutine-first persistence path.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
```

## Core concepts {#concepts}

`exposedKtorR2dbcReadinessProbe` executes `SELECT 1` in a cancellable `suspendTransaction`. Query timeout policy follows the caller-owned Exposed R2DBC configuration.

## Quick start {#quick-start}

```kotlin
val probe = exposedKtorR2dbcReadinessProbe(r2dbcDatabase)
route.bluetape4kExposedHealthRoutes(listOf(probe))
```

## API by task {#api-by-task}

- Use `exposedKtorR2dbcReadinessProbe` for readiness.
- Use `ApplicationCall.exposedR2dbcTransaction` for coroutine-native transactions.
- Compose `bluetape4kExposedR2dbcErrors()` in `StatusPages`.

## Recommended patterns {#patterns}

Keep transaction blocks suspend-native, propagate cancellation, and close the application-owned pool and database resources in one lifecycle owner.

## Integrations {#integrations}

This module depends on core and Exposed R2DBC only. JDBC and cache APIs are intentionally separate.

## Configuration {#configuration}

Component names and route paths follow core validation. R2DBC timeout behavior is inherited from the caller-owned database and driver.

## Failure modes {#failures}

R2DBC failures map to a fixed unavailable response. Cancellation is rethrown. A timeout or failed probe reports `TIMEOUT` or `DOWN` without driver details.

## Operations {#operations}

Export core readiness and transaction timers with bounded backend and outcome tags. Instrument the pool and driver separately when their metrics are available.

## Testing {#testing}

Run the R2DBC H2 suite and then PostgreSQL/MySQL integration paths. Cover cancellation, driver timeout, and shared-deadline ordering.

## Workshops {#workshops}

No selective R2DBC workshop is published in this develop-only release line.

## Limitations {#limitations}

Driver support for query cancellation and timeout is implementation-specific; cooperative coroutine cancellation remains the request contract.

## Sources {#sources}

- [R2DBC specification](https://r2dbc.io/spec/0.9.0.RELEASE/spec/html/)
- [Kotlin structured concurrency](https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency)
