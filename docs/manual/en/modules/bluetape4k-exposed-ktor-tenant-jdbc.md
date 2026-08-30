---
manualId: "bluetape4k-exposed-ktor-tenant-jdbc"
id: "bluetape4k-exposed-ktor-tenant-jdbc"
title: "Exposed Ktor Tenant JDBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-tenant-jdbc"
sourceDir: "ktor/tenant-jdbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc
---

# Exposed Ktor Tenant JDBC Adapter

An opt-in adapter that routes a Ktor call to the JDBC `Database` selected by
its already-bound `TenantId`, then delegates to the existing blocking
transaction helper.

## Problem {#problem}

Applications that use one JDBC database per tenant need a single transaction
boundary without placing tenant lookup or blocking JDBC work on the Ktor event
loop. The adapter keeps tenant context, resolution, and transaction execution
as explicit application-owned boundaries.

## When to use it {#when-to-use}

Use this module when a Ktor application has a validated tenant context and one
caller-owned JDBC `Database` per tenant. Choose the regular Ktor JDBC adapter
when the database is not tenant-selected.

## Core concepts {#concepts}

`KtorTenantContext` supplies the call-local `TenantId`; the resolver maps it to
`Database`; `exposedTenantJdbcTransaction` delegates to the existing JDBC
helper. No thread-local tenant state or implicit default is introduced.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc")
```

The module exposes the upstream `bluetape4k-tenant` and `bluetape4k-ktor-tenant`
contracts through its public API. The central catalog aliases
`bt4k.bluetape4k.tenant` and `bt4k.bluetape4k.ktor.tenant` are available and
were verified at catalog commit `29d858bd22553a31709123908a2eb5c5644093b3`.
Keep the dependency platform aligned with the `2.0.0-SNAPSHOT` development
train.

## Quick start {#quick-start}

Bind a validated tenant at the authentication or routing boundary and use an
immutable exact-match map for resolution:

```kotlin
val databases = mapOf(
    TenantId("acme") to acmeDatabase,
    TenantId("globex") to globexDatabase,
)
val jdbcDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

routing {
    get("/orders") {
        KtorTenantContext.bindTenant(call, authenticatedTenantId)
        val orders = call.exposedTenantJdbcTransaction(
            databaseResolver = databases::getValue,
            blockingDispatcher = jdbcDispatcher,
        ) {
            Orders.selectAll().toList()
        }
        call.respond(orders)
    }
}
```

The `blockingDispatcher` is required and must be sized for the application's
JDBC pool. Close it from the application lifecycle.

## API by task {#api-by-task}

- Bind once with `KtorTenantContext.bindTenant(call, tenantId)`.
- Call `ApplicationCall.exposedTenantJdbcTransaction` for tenant-routed work.
- Keep HTTP error mapping in the application's existing `StatusPages` policy.

## Recommended patterns {#patterns}

Build the resolver from an immutable map, keep the dispatcher bounded, and
resolve authentication before entering the transaction. Never mutate the map
or infer a tenant from an unvalidated request value inside the adapter.

## Integrations {#integrations}

Use with `bluetape4k-exposed-ktor-core` and the existing Ktor JDBC adapter.
R2DBC and cache integrations remain separate artifacts; this module does not
install Ktor plugins or own application resources.

## Configuration {#configuration}

Configure the tenant map, JDBC pools, dispatcher size, optional
`MeterRegistry`, and `StatusPages` in application code. Keep all upstream
tenant artifacts on the same dependency-platform version.

## Resolver contract {#resolver}

`databaseResolver` is called after `KtorTenantContext.requireCurrent(call)` and
before any transaction or metric timer starts. It must be a fast, non-blocking,
O(1) exact-match lookup over immutable, thread-safe state. Do not perform a
network call, database query, lazy pool creation, or default-tenant fallback in
the resolver. Resolve asynchronously before entering the route or propose a
separate future API; the current function is intentionally synchronous.

Missing context throws `MissingTenantContextException` without invoking the
resolver. Resolver exceptions are propagated unchanged. Once a `Database` is
resolved, transaction exception, cancellation, interruption, and metrics
behavior are inherited from `exposedJdbcTransaction`.

## Failure handling {#failures}

The adapter does not install `StatusPages` or choose an HTTP policy. Compose
the application's existing policy and expose stable classifications:

```kotlin
install(StatusPages) {
    exception<MissingTenantContextException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, "tenant_context_missing")
    }
    exception<NoSuchElementException> { call, _ ->
        call.respond(HttpStatusCode.NotFound, "tenant_resolution_failed")
    }
}
```

Map the resolver's own exception type in the same way when it is not
`NoSuchElementException`. Do not log or tag raw tenant identifiers, request
headers, URLs, SQL, credentials, or exception messages containing them.

## Lifecycle and metrics {#operations}

The caller owns the tenant-to-database map, databases, pools, dispatcher, and
optional `MeterRegistry`. The adapter creates none of these and never closes
them. Existing transaction timers retain the `jdbc` backend and success,
failure, or cancellation outcome tags without adding a tenant identifier.

## Testing {#testing}

Cover missing context fail-fast, resolver exception identity, exact routing of
multiple databases, concurrent call isolation, dispatcher execution, and
cancellation re-propagation. Run the H2 test first, then the repository's
PostgreSQL and MySQL Testcontainers suites where configured.

## Workshops {#workshops}

No selective tenant JDBC workshop is published in this develop-only release
line.

## Limitations {#limitations}

This adapter does not parse tenant headers, authenticate requests, create
databases, migrate schemas, or provide a fallback database. Those policies
remain application responsibilities. The upstream tenant coordinates are
pinned to the verified `2.0.0-SNAPSHOT` snapshot and the immutable catalog
commit `29d858bd22553a31709123908a2eb5c5644093b3` for this development train.

## Sources {#sources}

- [Ktor server StatusPages](https://ktor.io/docs/server-status-pages.html)
- [Kotlin `asCoroutineDispatcher`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html)
