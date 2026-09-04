# bluetape4k-exposed-ktor-tenant-jdbc

Ktor에서 tenant별 JDBC transaction을 실행하는 어댑터입니다. 현재
`ApplicationCall`에 binding된 `TenantId`를 읽고 애플리케이션이 소유한
`Database`를 해석한 뒤 기존 JDBC transaction helper로 blocking 작업을
위임합니다.

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

`databaseResolver`는 빠른 non-blocking exact-match 조회여야 합니다.
immutable map의 `getValue`를 사용하면 알 수 없는 tenant가 fail closed로
실패하며 기본 database fallback은 없습니다. context가 없으면 resolver와
transaction보다 먼저 실패합니다. 어댑터는 database, dispatcher, pool,
metric registry를 만들거나 닫지 않으므로 lifecycle은 애플리케이션이
소유하고 종료 시 dispatcher를 닫아야 합니다.

애플리케이션의 기존 `StatusPages` 정책에서
`MissingTenantContextException`을 `tenant_context_missing`으로, resolver
실패를 `tenant_resolution_failed`로 분류하세요. 로그와 metric에 raw tenant
식별자, header, URL, SQL, credential을 넣지 마세요.

[모듈 매뉴얼](https://bluetape4k.github.io/ko/manual/bluetape4k-exposed/2.0/modules/bluetape4k-exposed-ktor-tenant-jdbc/)을
참고하세요.
