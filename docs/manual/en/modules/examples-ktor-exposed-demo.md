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

> Run an H2 JDBC count route beside a PostgreSQL R2DBC order-confirmation flow, with cache readiness, event handoff, ownership, and shutdown kept inside the Ktor application.

## What you learn {#problem}

This executable application shows the integration boundaries that are easy to miss in a minimal Ktor sample: the application creates an H2 JDBC stack and a PostgreSQL R2DBC stack, keeps blocking JDBC work off the event loop, installs one shared error handler, exposes JDBC/R2DBC/cache liveness and readiness, and releases every owned resource when Ktor stops.

It is a smoke-test-sized example, but the lifecycle and dispatcher choices are intended to be copied into a real service.

## Prerequisites {#prerequisites}

- JDK and the repository Gradle wrapper
- Docker with the repository PostgreSQL Compose file for the runnable order flow
- The normal contract tests are Docker-free; the `postgresIntegrationTest` task and the running application require PostgreSQL

## Run {#run}

Run the Docker-free route and service contracts:

```bash
./gradlew :examples-ktor-exposed-demo:test
```

Run the serialized PostgreSQL proof when Docker is available:

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest --no-parallel
```

Start PostgreSQL and then the Netty application on port `8080`:

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml up -d --wait
./gradlew :examples-ktor-exposed-demo:run
```

Then request `/healthz/exposed`, `/readyz/exposed`, `/transactions/jdbc-count`, `/transactions/r2dbc-count`, and the order routes.

## Expected result {#expected-result}

The readiness response returns HTTP `200` with `jdbc`, `r2dbc`, and `cache.orders` components when PostgreSQL is available. `/transactions/jdbc-count` returns the text body `2` from the H2 seed table. The first `POST /orders/{orderId}/confirm` returns `eventPublished=true`, a subsequent sequential confirmation returns `eventPublished=false`, and `/transactions/r2dbc-count` increases by one after the order is persisted.

## Failure diagnosis {#failures}

- `DuplicatePluginException` for `StatusPages`: the core plugin and the application both installed it. Keep `installStatusPages = false` in the core configuration, then install `StatusPages` once with both error mappings.
- `/healthz/exposed` is missing: the core health route or the Exposed health route was disabled. This example disables the former and enables `installHealthRoutes` in the Exposed plugin.
- `/readyz/exposed` times out or reports a component down: inspect the H2 JDBC pool, PostgreSQL R2DBC pool, and `cache.orders` consistency probe separately. The configured readiness budget is two seconds.
- `/transactions/jdbc-count` hangs under load: verify that `exposedJdbcTransaction` receives the dedicated JDBC dispatcher; blocking JDBC must not run on Ktor's event-loop threads.
- `/transactions/r2dbc-count` or order confirmation fails: verify that the Compose PostgreSQL service is healthy and that `DEMO_POSTGRES_*` values match the service.
- The JDBC count is not `2`: confirm that `initialize()` created `DemoItems` and inserted its two H2 seed rows before the server started.
- Gradle does not exit after a test: verify that the R2DBC repository, pool, Hikari data source, and JDBC dispatcher are all closed.

## Next route {#next}

Read the [Ktor integration manual](bluetape4k-exposed-ktor.md) to understand the plugin API, then use [transaction boundaries](../guides/transaction-boundaries.md) to choose between JDBC and R2DBC in a larger service. Continue with the [Exposed workshop](https://github.com/bluetape4k/exposed-workshop) when you want a broader persistence design rather than this focused lifecycle example.

## When to use it {#when-to-use}

Use this example to validate a Ktor application's plugin order, database ownership, health routes, dispatcher isolation, and shutdown behavior before introducing production infrastructure. It is also a compact regression fixture for upgrades of Ktor, Exposed, or the bluetape4k Ktor adapters.

## Coordinates {#coordinates}

This application is not published as a library. Consumer builds should import the central BOM and omit versions from individual bluetape4k dependencies. The develop migration uses the backend-selective artifacts explicitly:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-core")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-jdbc")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-r2dbc")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-cache")
}
```

The selective coordinates are develop-only during the 2.0 migration. A stable `1.12.1` consumer must keep using `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor` until the child artifacts are published.

The repository example additionally uses its managed Ktor and Exposed BOM catalogs so all demonstration dependencies stay aligned with release `1.12.1`.

## Core concepts {#concepts}

`KtorExposedDemoResources.create()` constructs an H2 Hikari/Exposed `Database` for the JDBC count route and a PostgreSQL `ConnectionPool`/`R2dbcDatabase` for the order scenario. It also creates the Caffeine-backed R2DBC order repository, an application-owned event publisher, and a fixed two-thread coroutine dispatcher dedicated to blocking JDBC calls. The application owns these objects rather than relying on a global singleton.

`ApplicationStopped` calls `resources.close()`. Cleanup disposes the R2DBC pool with a five-second bound, closes the Hikari data source, and closes the JDBC dispatcher. This ties resource lifetime to the Ktor application that created it.

## Quick start {#quick-start}

1. Start the PostgreSQL Compose service.
2. Run the module test or the serialized PostgreSQL integration test.
3. Start the application with the `run` task.
4. Check health and readiness before calling transaction or order routes.
5. Stop the process, then run `docker compose -f examples/ktor-exposed-demo/compose.yaml down` and confirm that no Hikari, R2DBC, or dispatcher threads remain.

## API by task {#api-by-task}

| Task | API or setting | Purpose |
| --- | --- | --- |
| Own resources | `KtorExposedDemoResources.create()` | Creates H2 JDBC, PostgreSQL R2DBC, Caffeine repository, event publisher, and dispatcher resources; seeds two JDBC rows and creates the order schema. |
| Close resources | `monitor.subscribe(ApplicationStopped)` | Couples cleanup to Ktor shutdown. |
| Avoid duplicate error plugins | `installStatusPages = false` and one `install(StatusPages)` | Combines core and Exposed error responses in one installation. |
| Expose database probes | `installHealthRoutes = true` | Adds `/healthz/exposed` and `/readyz/exposed` with JDBC, R2DBC, and `cache.orders` components. |
| Bound readiness | `readinessProbeTimeout = 2.seconds` | Prevents database probes from waiting indefinitely. |
| Isolate JDBC | `exposedJdbcTransaction(..., blockingDispatcher = resources.jdbcDispatcher)` | Moves blocking work away from Ktor event-loop threads. |

## Recommended patterns {#patterns}

Keep database creation and disposal in one owner, pass database handles explicitly to the plugin and route, and make the blocking dispatcher visible at every JDBC boundary. Install cross-cutting plugins such as `StatusPages` once; compose their handlers instead of letting integration helpers install competing instances.

The demo uses H2 for the small JDBC count route and PostgreSQL for the R2DBC order scenario deliberately. Readiness verifies both database stacks and the order-cache consistency probe, while the two count routes make the separate data stores visible.

## Integrations {#integrations}

`installBluetape4kKtorCore` supplies common Ktor behavior but has `StatusPages` and core health routes disabled here. The application installs `StatusPages` once and composes the core, JDBC, and R2DBC error mappings. It registers backend-specific readiness probes and `bluetape4kExposedHealthRoutes`, then passes the H2 database, PostgreSQL database, JDBC dispatcher, and the two-second readiness timeout to the selected child helpers.

## Configuration {#configuration}

The demo fixes the H2 pool and PostgreSQL R2DBC pool at small sizes suitable for local execution and tests. PostgreSQL URL, user, and password are configurable through `DEMO_POSTGRES_R2DBC_URL`, `DEMO_POSTGRES_USER`, and `DEMO_POSTGRES_PASSWORD`. Production services should externalize credentials, size pools from measured concurrency, and preserve the same explicit ownership and dispatcher boundaries.

## Operations {#operations}

Treat `/healthz/exposed` as plugin liveness and `/readyz/exposed` as the dependency gate: readiness includes separate `jdbc`, `r2dbc`, and `cache.orders` components. Keep the two-second timeout observable, because a repeated timeout usually indicates pool exhaustion, a blocked dispatcher, a cache consistency failure, or a database connectivity failure rather than an HTTP routing problem.

## Testing {#testing}

The normal `KtorExposedDemoApplicationTest` suite is a Docker-free in-process contract test. The `postgresIntegrationTest` source set starts the PostgreSQL-backed resources sequentially and verifies readiness, the JDBC count, the R2DBC count, order confirmation, repeated confirmation, and cleanup. A real `run` process therefore needs the Compose PostgreSQL service even though the fast contract suite does not.

When adapting the example, add tests for a failed readiness probe, Exposed exception mapping, and repeated application startup/shutdown so lifecycle regressions remain visible.

## Workshops and learning path {#workshops}

First trace the four release sources below in this order: application setup, resource ownership, HTTP assertions, then dependency declarations. Next study the Ktor module manual for configuration choices and the transaction guide for workload boundaries. The workshop is the next step when you need repositories, schema evolution, and production database examples.

## Limitations {#limitations}

The H2 JDBC portion is deterministic but does not reproduce a production database's SQL dialect or pool pressure; the PostgreSQL path still leaves network failures, migration management, and production sizing to the consuming application. The fixed dispatcher and pool sizes are teaching defaults, not capacity recommendations. The demo also leaves authentication, authorization, metrics export, and graceful traffic draining to the consuming application.

## Sources {#sources}

- [Application setup at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt)
- [Resource ownership at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt)
- [Integration test at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt)
- [Gradle build at release 1.12.1](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/build.gradle.kts)
