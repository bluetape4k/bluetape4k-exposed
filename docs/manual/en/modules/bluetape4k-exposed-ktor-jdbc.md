---
manualId: "bluetape4k-exposed-ktor-jdbc"
id: "bluetape4k-exposed-ktor-jdbc"
title: "Exposed Ktor JDBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-jdbc"
sourceDir: "ktor/jdbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc
---

# Exposed Ktor JDBC Adapter

JDBC-specific readiness and transaction helpers for applications that already depend on the backend-neutral Ktor core.

## Problem {#problem}

Blocking JDBC must not run on a Ktor event-loop dispatcher, and a JDBC-only consumer should not receive R2DBC or cache classes.

## When to use it {#when-to-use}

Use this adapter for a JDBC `Database` and an application-provided bounded blocking dispatcher.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
```

## Core concepts {#concepts}

`exposedKtorJdbcReadinessProbe` runs `SELECT 1` in `runInterruptible`. Its statement timeout is the smaller of the configured timeout and the remaining shared readiness budget.

## Quick start {#quick-start}

```kotlin
val probe = exposedKtorJdbcReadinessProbe(database, jdbcDispatcher)
route.bluetape4kExposedHealthRoutes(listOf(probe))
```

## API by task {#api-by-task}

- Use `exposedKtorJdbcReadinessProbe` for a caller-owned JDBC database.
- Use `ApplicationCall.exposedJdbcTransaction` for blocking transactions.
- Compose `bluetape4kExposedJdbcErrors()` in the existing `StatusPages` block.

## Recommended patterns {#patterns}

Size the dispatcher to useful pool concurrency, keep transaction work bounded, and close the database/pool and dispatcher in the application lifecycle.

## Integrations {#integrations}

Depend on `bluetape4k-exposed-ktor-core` and the Exposed JDBC module only. R2DBC and cache integrations are separate artifacts.

## Configuration {#configuration}

The dispatcher is required for JDBC readiness and transactions. Query timeout values must be finite and positive; sub-second JDBC values use a one-second driver timeout minimum.

## Failure modes {#failures}

Blocking work on the event loop, an exhausted dispatcher, or a driver that ignores interruption can delay a request. Database failures map to a fixed unavailable response and never expose SQL or causes.

## Operations {#operations}

Observe readiness and transaction timers with the core metric registry. Keep pool, dispatcher, authentication, and shutdown ownership in application code.

## Testing {#testing}

Test H2 first, then the repository's PostgreSQL and MySQL Testcontainers paths. Include cancellation, statement timeout, and dispatcher-isolation cases.

## Workshops {#workshops}

No selective JDBC workshop is published in this develop-only release line.

## Limitations {#limitations}

Driver support for statement cancellation and query timeout varies. The coroutine deadline is a request boundary, not a guarantee that a non-cooperative driver stops immediately.

## Sources {#sources}

- [Kotlin `runInterruptible`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)
- [Ktor server plugins](https://ktor.io/docs/server-plugins.html)
