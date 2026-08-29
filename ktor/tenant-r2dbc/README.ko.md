# bluetape4k-exposed-ktor-tenant-r2dbc

Ktor에서 tenant별 coroutine-native R2DBC transaction을 실행하는
어댑터입니다. 현재 `ApplicationCall`에 binding된 `TenantId`를 읽고
애플리케이션이 소유한 `R2dbcDatabase`를 해석한 뒤 기존 R2DBC transaction
helper로 위임합니다.

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

`databaseResolver`는 빠른 non-blocking exact-match 조회여야 합니다.
immutable map의 `getValue`를 사용하면 알 수 없는 tenant가 fail closed로
실패하며 기본 database fallback은 없습니다. context가 없으면 resolver와
transaction보다 먼저 실패합니다. database, pool, metric registry의
생성·종료는 애플리케이션이 소유하며 이 어댑터가 대신 관리하지 않습니다.

기존 `StatusPages` 정책에서 `MissingTenantContextException`을
`tenant_context_missing`으로, resolver 실패를 `tenant_resolution_failed`로
분류하세요. 취소, 예외 전파와 transaction metric은 기존 R2DBC helper의
계약을 유지합니다. 로그와 metric에 raw tenant 식별자, header, URL, SQL,
credential을 넣지 마세요.

[모듈 매뉴얼](../../docs/manual/ko/modules/bluetape4k-exposed-ktor-tenant-r2dbc.md)을
참고하세요.
