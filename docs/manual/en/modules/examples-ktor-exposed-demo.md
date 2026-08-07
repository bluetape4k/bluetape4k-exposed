---
manualId: "examples-ktor-exposed-demo"
id: "examples-ktor-exposed-demo"
title: "Ktor and Exposed Example"
locale: "en"
kind: "example"
gradlePath: ":examples-ktor-exposed-demo"
sourceDir: "examples/ktor-exposed-demo"
releaseRef: "1.12.1"
artifact: null
---

# Ktor and Exposed Example

> Run JDBC and R2DBC health checks beside a real Exposed JDBC transaction, with ownership and shutdown kept inside the Ktor application.

## What you learn {#problem}

This executable application shows the integration boundaries that are easy to miss in a minimal Ktor sample: the application creates both H2 database stacks, keeps blocking JDBC work off the event loop, installs one shared error handler, exposes liveness and readiness, and releases every owned resource when Ktor stops.

It is a smoke-test-sized example, but the lifecycle and dispatcher choices are intended to be copied into a real service.

## Prerequisites {#prerequisites}

- JDK and the repository Gradle wrapper
- No external database and no Docker: JDBC and R2DBC each use an application-owned in-memory H2 database

## Run {#run}

Verify the complete route contract:

```bash
./gradlew :examples-ktor-exposed-demo:test
```

Start the Netty application on port `8080`:

```bash
./gradlew :examples-ktor-exposed-demo:run
```

Then request `/healthz/exposed`, `/readyz/exposed`, and `/transactions/jdbc-count`.

## Expected result {#expected-result}

The test expects both health endpoints to return HTTP `200`. The exposed health component is `UP`; readiness reports both `jdbc` and `r2dbc` as `UP`. `/transactions/jdbc-count` returns the text body `2`, proving that the route entered an Exposed JDBC transaction and counted the two rows inserted during resource initialization.

## Failure diagnosis {#failures}

- `DuplicatePluginException` for `StatusPages`: the core plugin and the application both installed it. Keep `installStatusPages = false` in the core configuration, then install `StatusPages` once with both error mappings.
- `/healthz/exposed` is missing: the core health route or the Exposed health route was disabled. This example disables the former and enables `installHealthRoutes` in the Exposed plugin.
- `/readyz/exposed` times out or reports a component down: inspect JDBC and R2DBC initialization separately. The configured readiness budget is two seconds.
- `/transactions/jdbc-count` hangs under load: verify that `exposedJdbcTransaction` receives the dedicated JDBC dispatcher; blocking JDBC must not run on Ktor's event-loop threads.
- The count is not `2`: confirm that `initialize()` created `DemoItems` and inserted its two seed rows before the server started.
- Gradle does not exit after a test: verify that the R2DBC pool, Hikari data source, and JDBC dispatcher are all closed. Docker is not part of this example.

## Next route {#next}

Read the [Ktor integration manual](bluetape4k-exposed-ktor.md) to understand the plugin API, then use [transaction boundaries](../guides/transaction-boundaries.md) to choose between JDBC and R2DBC in a larger service. Continue with the [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) when you want a broader persistence design rather than this focused lifecycle example.

## When to use it {#when-to-use}

Use this example to validate a Ktor application's plugin order, database ownership, health routes, dispatcher isolation, and shutdown behavior before introducing production infrastructure. It is also a compact regression fixture for upgrades of Ktor, Exposed, or the bluetape4k Ktor adapters.

## Coordinates {#coordinates}

This application is not published as a library. Consumer builds should import the central BOM and omit versions from individual bluetape4k dependencies:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor")
}
```

The repository example additionally uses its managed Ktor and Exposed BOM catalogs so all demonstration dependencies stay aligned with release `1.12.1`.

## Core concepts {#concepts}

`KtorExposedDemoResources.create()` constructs two independent in-memory H2 stacks: Hikari plus Exposed `Database` for JDBC, and `ConnectionPool` plus `R2dbcDatabase` for R2DBC. It also creates a fixed two-thread coroutine dispatcher dedicated to blocking JDBC calls. The application owns these objects rather than relying on a container or a global singleton.

`ApplicationStopped` calls `resources.close()`. Cleanup disposes the R2DBC pool with a five-second bound, closes the Hikari data source, and closes the JDBC dispatcher. This ties resource lifetime to the Ktor application that created it.

## Quick start {#quick-start}

1. Run the module test; no Docker setup is required.
2. Start the application with the `run` task.
3. Check health and readiness before calling the transaction route.
4. Stop the process and confirm that no Hikari, R2DBC, or dispatcher threads remain.

## API by task {#api-by-task}

| Task | API or setting | Purpose |
| --- | --- | --- |
| Own resources | `KtorExposedDemoResources.create()` | Creates JDBC, R2DBC, and dispatcher resources and seeds two JDBC rows. |
| Close resources | `monitor.subscribe(ApplicationStopped)` | Couples cleanup to Ktor shutdown. |
| Avoid duplicate error plugins | `installStatusPages = false` and one `install(StatusPages)` | Combines core and Exposed error responses in one installation. |
| Expose database probes | `installHealthRoutes = true` | Adds `/healthz/exposed` and `/readyz/exposed`. |
| Bound readiness | `readinessProbeTimeout = 2.seconds` | Prevents database probes from waiting indefinitely. |
| Isolate JDBC | `exposedJdbcTransaction(..., blockingDispatcher = resources.jdbcDispatcher)` | Moves blocking work away from Ktor event-loop threads. |

## Recommended patterns {#patterns}

Keep database creation and disposal in one owner, pass database handles explicitly to the plugin and route, and make the blocking dispatcher visible at every JDBC boundary. Install cross-cutting plugins such as `StatusPages` once; compose their handlers instead of letting integration helpers install competing instances.

The demo uses separate JDBC and R2DBC H2 databases deliberately. Readiness verifies that both stacks can connect, while the count route proves the JDBC transaction path without pretending that the two databases share data.

## Integrations {#integrations}

`installBluetape4kKtorCore` supplies common Ktor behavior but has `StatusPages` and core health routes disabled here. The application installs `StatusPages` once and registers both `bluetape4kErrorResponses()` and `bluetape4kExposedErrors()`. `installBluetape4kExposedKtor` then receives both database handles, the JDBC dispatcher, and the two-second readiness timeout.

## Configuration {#configuration}

The demo fixes both H2 pools at small sizes suitable for local execution and tests. Its database names include a caller-provided suffix, allowing tests such as `create("smoke")` to use isolated in-memory databases. Production services should externalize URLs and credentials, size pools from measured concurrency, and preserve the same explicit ownership and dispatcher boundaries.

## Operations {#operations}

Treat `/healthz/exposed` as plugin liveness and `/readyz/exposed` as the dependency gate: readiness includes separate `jdbc` and `r2dbc` components. Keep the two-second timeout observable, because a repeated timeout usually indicates pool exhaustion, a blocked dispatcher, or a database connectivity failure rather than an HTTP routing problem.

## Testing {#testing}

`KtorExposedDemoApplicationTest` runs `testApplication`, creates and closes its resources with `use`, calls both probe routes, and asserts that the transaction route returns `2`. This is a full in-process integration test; it needs neither a listening port nor Docker.

When adapting the example, add tests for a failed readiness probe, Exposed exception mapping, and repeated application startup/shutdown so lifecycle regressions remain visible.

## Workshops and learning path {#workshops}

First trace the four release sources below in this order: application setup, resource ownership, HTTP assertions, then dependency declarations. Next study the Ktor module manual for configuration choices and the transaction guide for workload boundaries. The workshop is the next step when you need repositories, schema evolution, and production database examples.

## Limitations {#limitations}

In-memory H2 keeps the example deterministic but does not reproduce a production database's SQL dialect, network failures, pool pressure, migration process, or shared JDBC/R2DBC data. The fixed dispatcher and pool sizes are teaching defaults, not capacity recommendations. The demo also leaves authentication, authorization, metrics export, and graceful traffic draining to the consuming application.

## Sources {#sources}

- [Application setup at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt)
- [Resource ownership at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt)
- [Integration test at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt)
- [Gradle build at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/build.gradle.kts)
