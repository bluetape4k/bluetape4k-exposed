---
manualId: "exposed-spring-boot-r2dbc-demo"
id: "exposed-spring-boot-r2dbc-demo"
title: "Spring Boot R2DBC Demo"
locale: "en"
kind: "example"
gradlePath: ":exposed-spring-boot-r2dbc-demo"
sourceDir: "examples/r2dbc-demo"
releaseRef: "1.12.1"
artifact: null
---

# Spring Boot R2DBC Demo

> Trace a WebFlux `suspend` endpoint through an application-owned Exposed R2DBC database.

## What you learn {#problem}

This demo connects Spring WebFlux, an `ExposedR2dbcRepository`, a pooled H2 R2DBC connection, coroutine endpoints, and integration tests. It makes an important ownership rule visible: the application creates the `ConnectionPool`, `R2dbcDatabase`, and database dispatcher. The integration module maps repository interfaces; it does not create a Spring reactive transaction manager.

## Prerequisites {#prerequisites}

- JDK 21+
- the repository Gradle wrapper
- no Docker for the default path; the application uses in-memory H2 over R2DBC

## Run {#run}

```bash
./gradlew :exposed-spring-boot-r2dbc-demo:test
./gradlew :exposed-spring-boot-r2dbc-demo:bootRun
```

The HTTP application listens on port `8080` by default.

## Expected result {#expected-result}

`DataInitializer` starts after `ApplicationReadyEvent`, creates the table when missing, and asynchronously inserts three products. The first list request may race with that coroutine, so the test polls until three rows are visible.

```bash
curl http://localhost:8080/products
curl -i http://localhost:8080/products/999999
```

The list settles at three seeded products and a missing ID returns `404`. Controller tests also verify create, update, delete, and post-delete `404`. Repository tests cover CRUD, `Flow`, materialized lists, bulk operations, count/existence, and `streamAll()`.

## Failure diagnosis {#failures}

- No `R2dbcDatabase` bean: inspect `spring.r2dbc.*`, `ConnectionFactoryOptions`, and `ConnectionPool` construction in `ExposedR2dbcConfig`.
- The first list is empty: initialization is asynchronous; check initializer logs and schema creation before treating it as a repository failure.
- One half of update/delete commits: wrap the read plus write in one explicit `suspendTransaction`; separate repository calls may otherwise own separate transactions.
- Request stalls: find blocking JDBC, `runBlocking`, or thread sleep on the request path. The polling sleep exists only in the test helper.
- Pool does not shut down: keep `ConnectionPool` as a Spring bean so its lifecycle remains application-owned and observable.

## Next route {#next}

Read [Spring Boot R2DBC integration](bluetape4k-exposed-spring-boot-r2dbc.md) for repository transaction semantics, then [R2DBC repository patterns](bluetape4k-exposed-r2dbc/repository-patterns.md). Continue with the [Exposed R2DBC workshop](https://github.com/bluetape4k/exposed-r2dbc-workshop) for transactions, Flow, WebFlux, Ktor, caching, and routing exercises.

## When to use it {#when-to-use}

Use this demo as the first runnable reference for a coroutine-based WebFlux service that uses Exposed R2DBC. Prefer the JDBC demo when the surrounding stack is blocking. This example does not prove R2DBC is faster; it shows how to keep connection, transaction, and cancellation ownership consistent.

## Coordinates {#coordinates}

The demo publishes no artifact. A consumer imports the central BOM and declares the integration without an individual version:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-r2dbc")
}
```

## Core concepts {#concepts}

`ExposedR2dbcConfig` builds `ConnectionFactoryOptions`, a `ConnectionPool`, and `R2dbcDatabase`; it currently uses `Dispatchers.IO` as the database dispatcher. `ProductR2dbcRepository` maps `ResultRow` to immutable `ProductRecord` values. Every controller endpoint is `suspend`.

Repository methods open their own Exposed R2DBC transaction where required. The update and delete endpoints call more than one repository method, so they add an outer `suspendTransaction` to make the read-check-write sequence atomic. There is no Spring reactive transaction manager in this path.

## Quick start {#quick-start}

1. Run the test task and inspect both controller and repository tests.
2. Start `bootRun`; wait for the initializer, then call `GET /products`.
3. Follow `ExposedR2dbcConfig` from URL to pool to `R2dbcDatabase`.
4. Compare a single `save()` call with the explicit multi-call transaction in `update()`.
5. Cancel a request in an application-level test and make sure cancellation is not converted into a retry or generic server error.

## API by task {#api-by-task}

| Task | Released source to follow |
| --- | --- |
| Pool and database ownership | `ExposedR2dbcConfig` |
| Table and record mapping | `Products`, `ProductRecord` |
| Repository mapping | `ProductR2dbcRepository` |
| Single-call and multi-call transactions | `ProductController` |
| Asynchronous schema/seed lifecycle | `DataInitializer` |
| WebFlux behavior | `ProductControllerTest` |
| Repository and Flow behavior | `ProductR2dbcRepositoryTest` |

## Recommended patterns {#patterns}

- Let one application component own and close the connection pool and `R2dbcDatabase`.
- Use an explicit outer `suspendTransaction` when several repository calls form one unit of work.
- Preserve `CancellationException`; cancellation is control flow, not a transient database error.
- Distinguish a materialized `Flow` wrapper from a cursor-backed stream; use and test `streamAll()` when streaming is required.
- Replace startup schema creation with reviewed migrations before production.

## Integrations {#integrations}

The demo uses Spring Boot WebFlux, the bluetape4k Exposed Spring Boot R2DBC module, Exposed R2DBC, `r2dbc-pool`, the H2 R2DBC driver, coroutines, and Jackson 3. The JDBC H2 runtime dependency supports the repository's migration tooling; HTTP persistence uses the R2DBC path.

## Configuration {#configuration}

`application.yml` defines `r2dbc:h2:mem:///webfluxdb`. `R2dbcPoolProperties` exposes idle/lifetime limits, create/acquire timeouts, max/initial/min sizes, retries, and eviction interval under `bluetape4k.r2dbc.pool.*`. Defaults are demonstration values, not production sizing advice. Measure concurrency and database limits before changing them.

## Operations {#operations}

Observe pool acquisition time, active/idle connections, query latency, transaction rollback, cancellation, and initializer completion separately. An empty first read can be a startup race rather than data loss. A successful H2 run proves wiring and repository behavior, not compatibility or capacity on the production database.

## Testing {#testing}

`ProductControllerTest` starts a random-port WebFlux application and uses `WebTestClient`. `ProductR2dbcRepositoryTest` verifies single and bulk operations, `Flow`, and `streamAll()` against the configured `R2dbcDatabase`. Add a cancellation test and a multi-call rollback test for the service boundary you adopt.

## Workshops and learning path {#workshops}

Read the demo in ownership order: `ExposedR2dbcConfig` → `DataInitializer` → `ProductR2dbcRepository` → `ProductController` → tests. The integration manual explains repository behavior, while the R2DBC workshop supplies deeper exercises. Return to this demo to verify that those concepts still form one runnable application.

## Limitations {#limitations}

The demo uses in-memory H2, asynchronous startup schema creation, one process, and no authentication. It does not provide a Spring reactive transaction manager, production migrations, pool sizing, retry policy, high availability, or evidence that R2DBC outperforms JDBC for a specific workload.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `1.12.1` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### Spring Boot R2DBC demo structure diagram

[![Spring Boot R2DBC demo structure diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-01.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-01.svg)

_Release README: [`examples/r2dbc-demo/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/r2dbc-demo/README.md)_

### WebFlux suspend request flow diagram

[![WebFlux suspend request flow diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-exposed/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-02.png)](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/docs/images/readme-diagrams/spring-boot-exposed-r2dbc-demo-diagram-02.svg)

_Release README: [`examples/r2dbc-demo/README.md`](https://github.com/bluetape4k/bluetape4k-exposed/blob/4cc2cce07087241ec24a597d8464615434ea2b81/examples/r2dbc-demo/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

- [Demo overview](../../../../examples/r2dbc-demo/README.md)
- [Pool and database configuration](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/config/ExposedR2dbcConfig.kt)
- [Coroutine controller and transaction boundaries](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/controller/ProductController.kt)
- [Repository mapping](../../../../examples/r2dbc-demo/src/main/kotlin/io/bluetape4k/examples/exposed/webflux/repository/ProductR2dbcRepository.kt)
- [Repository tests](../../../../examples/r2dbc-demo/src/test/kotlin/io/bluetape4k/examples/exposed/webflux/ProductR2dbcRepositoryTest.kt)
