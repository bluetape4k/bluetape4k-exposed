---
manualId: "bluetape4k-exposed-ktor-core"
id: "bluetape4k-exposed-ktor-core"
title: "Exposed Ktor Core"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-core"
sourceDir: "ktor/core"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core
---

# Exposed Ktor Core

Backend-neutral Ktor health, readiness, error, and metric contracts for the selective Exposed artifacts.

## Problem {#problem}

The legacy Ktor artifact coupled JDBC, R2DBC, and cache APIs. This core module keeps route and error contracts independent of those backends.

## When to use it {#when-to-use}

Use it when an application needs health routes and readiness probes without adding a database adapter. Choose a child adapter only for the backend it actually uses.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
```

## Core concepts {#concepts}

`ExposedKtorReadinessProbe` is caller-owned and cooperative. Registration snapshots validated component names and backend tags. Readiness executes probes sequentially under one monotonic deadline.

## Quick start {#quick-start}

Register one or more cooperative probes and install the routes in the application's existing routing tree:

```kotlin
route.bluetape4kExposedHealthRoutes(probes = listOf(probe))
```

## API by task {#api-by-task}

- Implement `ExposedKtorCooperativeReadinessProbe` for an application-owned probe.
- Call `Route.bluetape4kExposedHealthRoutes` for liveness and readiness.
- Compose `bluetape4kExposedCoreErrors()` inside the application's `StatusPages` block.

## Recommended patterns {#patterns}

Keep probes small, cancellation-cooperative, and free of resource ownership. Use stable low-cardinality component names and inject the application `MeterRegistry`.

## Integrations {#integrations}

Use `bluetape4k-exposed-ktor-jdbc`, `bluetape4k-exposed-ktor-r2dbc`,
`bluetape4k-exposed-ktor-cache`, or the tenant-specific JDBC/R2DBC adapters
alongside this module. The core module does not create dispatchers, pools,
databases, scopes, or routes outside the paths requested by the caller.

## Configuration {#configuration}

Probe registration requires one to sixteen unique component names matching `[a-z][a-z0-9_.-]{0,62}`. Paths are literal absolute paths and the finite readiness timeout is positive.

## Failure modes {#failures}

An invalid registration fails before routes are installed. A probe exception becomes `DOWN`; an expired shared deadline becomes `TIMEOUT`; caller cancellation is rethrown. Error responses use fixed messages and do not expose paths or causes.

## Operations {#operations}

Readiness is sequential with at most one probe in flight. Metrics use fixed `backend`, `operation`, `component`, and `outcome` tags. The application owns authentication, rate limiting, telemetry export, and shutdown.

## Testing {#testing}

Test route registration, deadline expiry, cancellation, error redaction, duplicate components, and meter-family collisions with deterministic cooperative probe fakes.

## Workshops {#workshops}

The selective Ktor modules are develop-only in this release line; no workshop artifact is published yet.

## Limitations {#limitations}

The core module does not perform backend I/O and cannot make a non-cooperative or blocking probe safe. JDBC and R2DBC behavior belongs to their respective adapters.

## Sources {#sources}

- [Ktor server routing documentation](https://ktor.io/docs/server-routing.html)
- [Kotlin coroutine cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
