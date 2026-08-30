---
manualId: "bluetape4k-exposed-ktor-tenant-r2dbc"
id: "bluetape4k-exposed-ktor-tenant-r2dbc"
title: "Exposed Ktor Tenant R2DBC Adapter"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-tenant-r2dbc"
sourceDir: "ktor/tenant-r2dbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc
---

# Exposed Ktor Tenant R2DBC Adapter

An opt-in adapter that routes a Ktor call to the R2DBC `R2dbcDatabase`
selected by its already-bound `TenantId`, then delegates to the existing
coroutine-native transaction helper.

## Problem {#problem}

Applications that use one R2DBC database per tenant need tenant selection to
remain isolated across concurrent calls without introducing blocking work or
thread-local state. The adapter keeps the call binding and database resolver
explicit and preserves the existing `suspendTransaction` boundary.

## When to use it {#when-to-use}

Use this module when a Ktor application has a validated tenant context and one
caller-owned R2DBC `R2dbcDatabase` per tenant. Choose the regular Ktor R2DBC
adapter when the database is not tenant-selected.

## Core concepts {#concepts}

`KtorTenantContext` supplies the call-local `TenantId`; the resolver maps it to
`R2dbcDatabase`; `exposedTenantR2dbcTransaction` delegates to the existing
coroutine-native helper. No thread-local tenant state or implicit default is
introduced.

## Coordinates {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc")
```

The module exposes the upstream `bluetape4k-tenant` and `bluetape4k-ktor-tenant`
contracts through its public API. The central catalog aliases
`bt4k.bluetape4k.tenant` and `bt4k.bluetape4k.ktor.tenant` are available and
were verified at catalog commit `29d858bd22553a31709123908a2eb5c5644093b3`.
Keep the dependency platform aligned with the `2.0.0-SNAPSHOT` development
train.

## Quick start {#quick-start}

Bind a validated tenant at the authentication or routing boundary and use an
immutable exact-match map:

```kotlin
val databases = mapOf(
    TenantId("acme") to acmeDatabase,
    TenantId("globex") to globexDatabase,
)

routing {
    get("/orders") {
        KtorTenantContext.bindTenant(call, authenticatedTenantId)
        val orders = call.exposedTenantR2dbcTransaction(
            databaseResolver = databases::getValue,
        ) {
            Orders.selectAll().toList()
        }
        call.respond(orders)
    }
}
```

The resolver is synchronous by contract, but must be fast and non-blocking.
Resolve remote or asynchronous tenant state before entering this transaction
helper.

## API by task {#api-by-task}

- Bind once with `KtorTenantContext.bindTenant(call, tenantId)`.
- Call `ApplicationCall.exposedTenantR2dbcTransaction` for tenant-routed work.
- Keep HTTP error mapping in the application's existing `StatusPages` policy.

## Recommended patterns {#patterns}

Build the resolver from an immutable map and resolve authentication before
entering the transaction. Never mutate the map or infer a tenant from an
unvalidated request value inside the adapter.

## Integrations {#integrations}

Use with `bluetape4k-exposed-ktor-core` and the existing Ktor R2DBC adapter.
JDBC and cache integrations remain separate artifacts; this module does not
install Ktor plugins or own application resources.

## Configuration {#configuration}

Configure the tenant map, R2DBC pools, optional `MeterRegistry`, and
`StatusPages` in application code. Keep all upstream tenant artifacts on the
same dependency-platform version.

## Resolver contract {#resolver}

`databaseResolver` is called after `KtorTenantContext.requireCurrent(call)` and
before `suspendTransaction` or metric timing starts. It must be an O(1),
exact-match lookup over immutable, thread-safe state. Do not perform network or
database I/O, lazy pool creation, or default-tenant fallback in the resolver.

Missing context throws `MissingTenantContextException` without invoking the
resolver. Resolver exceptions are propagated unchanged. Once an
`R2dbcDatabase` is resolved, transaction exception, cancellation, and metrics
behavior are inherited from `exposedR2dbcTransaction`.

## Failure handling {#failures}

The adapter does not install `StatusPages` or select an HTTP policy. Compose
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

The caller owns the tenant-to-database map, databases, pools, and optional
`MeterRegistry`. The adapter creates none of these and never closes them.
Existing transaction timers retain the `r2dbc` backend and success, failure,
or cancellation outcome tags without adding a tenant identifier.

## Testing {#testing}

Cover missing context fail-fast, resolver exception identity, exact routing of
multiple databases, concurrent call isolation, and cancellation
re-propagation. Run the H2 R2DBC test first, then configured PostgreSQL and
MySQL Testcontainers suites.

## Workshops {#workshops}

No selective tenant R2DBC workshop is published in this develop-only release
line.

## Limitations {#limitations}

This adapter does not parse tenant headers, authenticate requests, create
databases, migrate schemas, or provide a fallback database. Those policies
remain application responsibilities. The upstream tenant coordinates are
pinned to the verified `2.0.0-SNAPSHOT` snapshot and the immutable catalog
commit `29d858bd22553a31709123908a2eb5c5644093b3` for this development train.

## Sources {#sources}

- [Ktor server StatusPages](https://ktor.io/docs/server-status-pages.html)
- [Exposed R2DBC transactions](https://www.jetbrains.com/help/exposed/transactions.html)
