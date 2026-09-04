# bluetape4k-exposed-ktor-tenant-jdbc

Tenant-aware JDBC transaction helpers for Ktor. The adapter reads the
`TenantId` already bound to the current `ApplicationCall`, resolves the
caller-owned `Database`, and delegates blocking work to the existing JDBC
transaction helper.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc")
```

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

`databaseResolver` must be a fast, non-blocking exact-match lookup. An
immutable map with `getValue` makes unknown tenants fail closed; there is no
default database fallback. Missing context is rejected before the resolver or
transaction starts. The adapter does not create or close databases,
dispatchers, pools, or metric registries; the application owns their
lifecycle and must close the dispatcher during shutdown.

Compose the existing `StatusPages` policy in the application to classify
`MissingTenantContextException` as `tenant_context_missing` and resolver
failures as `tenant_resolution_failed`. Do not put raw tenant identifiers,
headers, URLs, SQL, or credentials in logs or metrics.

See the [module manual](https://bluetape4k.github.io/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-tenant-jdbc/).
