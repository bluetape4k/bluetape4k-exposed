# bluetape4k-exposed-ktor-tenant-r2dbc

Tenant-aware coroutine-native R2DBC transaction helpers for Ktor. The adapter
reads the `TenantId` bound to the current `ApplicationCall`, resolves the
caller-owned `R2dbcDatabase`, and delegates to the existing R2DBC transaction
helper.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc")
```

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

`databaseResolver` must be a fast, non-blocking exact-match lookup. An
immutable map with `getValue` makes unknown tenants fail closed; there is no
default database fallback. Missing context is rejected before the resolver or
transaction starts. The application owns databases, pools, and metric
registries; this adapter does not create or close any of them.

Compose the existing `StatusPages` policy in the application to classify
`MissingTenantContextException` as `tenant_context_missing` and resolver
failures as `tenant_resolution_failed`. Cancellation, exception propagation,
and transaction metrics remain those of the existing R2DBC helper. Do not put
raw tenant identifiers, headers, URLs, SQL, or credentials in logs or metrics.

See the [module manual](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-tenant-r2dbc/).
