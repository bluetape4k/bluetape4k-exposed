---
manualId: "bluetape4k-exposed-ktor-tenant-jdbc"
id: "bluetape4k-exposed-ktor-tenant-jdbc"
title: "Exposed Ktor Tenant JDBC 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-tenant-jdbc"
sourceDir: "ktor/tenant-jdbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc
---

# Exposed Ktor Tenant JDBC 어댑터

이미 binding된 `TenantId`로 Ktor call을 tenant별 JDBC `Database`에 연결한
뒤 기존 blocking transaction helper에 위임하는 opt-in 어댑터입니다.

## 문제 {#problem}

tenant마다 JDBC database를 사용하는 애플리케이션은 tenant 조회와 blocking
JDBC 작업을 Ktor event loop에서 실행하지 않으면서 하나의 transaction 경계를
필요로 합니다. 이 어댑터는 tenant context, 해석과 transaction 실행을
애플리케이션이 소유하는 명시적 경계로 유지합니다.

## 사용하기 좋은 경우 {#when-to-use}

검증된 tenant context와 tenant별로 호출자가 소유한 JDBC `Database`가 있는
Ktor 애플리케이션에 사용합니다. database를 tenant별로 선택하지 않는 경우에는
일반 Ktor JDBC 어댑터를 선택하세요.

## 핵심 개념 {#concepts}

`KtorTenantContext`가 call-local `TenantId`를 제공하고 resolver가 이를
`Database`로 매핑한 뒤 `exposedTenantJdbcTransaction`이 기존 JDBC helper로
위임합니다. thread-local tenant state나 암묵적 default는 추가하지 않습니다.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-jdbc")
```

모듈의 public API는 upstream `bluetape4k-tenant`와 `bluetape4k-ktor-tenant`
계약을 노출합니다. 중앙 catalog alias `bt4k.bluetape4k.tenant`와
`bt4k.bluetape4k.ktor.tenant`는 준비되었으며 catalog commit
`29d858bd22553a31709123908a2eb5c5644093b3`에서 검증했습니다.
`2.0.0-SNAPSHOT` 개발 train과 dependency platform을 맞추세요.

## 빠르게 시작하기 {#quick-start}

인증 또는 routing 경계에서 검증된 tenant를 binding하고 immutable
exact-match map으로 해석합니다.

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

`blockingDispatcher`는 필수이며 애플리케이션 JDBC pool에 맞춰 크기를
정해야 합니다. 애플리케이션 lifecycle에서 닫으세요.

## 작업별 API {#api-by-task}

- `KtorTenantContext.bindTenant(call, tenantId)`로 한 번 binding합니다.
- tenant routing 작업에는 `ApplicationCall.exposedTenantJdbcTransaction`을 사용합니다.
- HTTP 오류 매핑은 애플리케이션의 기존 `StatusPages` 정책에 둡니다.

## 권장 패턴 {#patterns}

immutable map으로 resolver를 만들고 dispatcher를 제한된 크기로 유지하며
transaction 진입 전에 인증을 끝내세요. map을 변경하거나 검증되지 않은 request
값에서 어댑터 안에서 tenant를 추론하지 마세요.

## 연동 {#integrations}

`bluetape4k-exposed-ktor-core`와 기존 Ktor JDBC 어댑터와 함께 사용합니다.
R2DBC와 cache integration은 별도 artifact이며 이 모듈은 Ktor plugin을 설치하거나
애플리케이션 resource를 소유하지 않습니다.

## 구성 {#configuration}

tenant map, JDBC pool, dispatcher 크기, 선택적 `MeterRegistry`와 `StatusPages`는
애플리케이션 코드에서 구성합니다. 모든 upstream tenant artifact는 동일한
dependency-platform version을 사용하세요.

## Resolver 계약 {#resolver}

`databaseResolver`는 `KtorTenantContext.requireCurrent(call)` 다음, transaction과
metric timer 시작 전에 호출됩니다. immutable·thread-safe 상태를 대상으로
빠른 non-blocking O(1) exact-match 조회여야 합니다. network 호출, database
query, lazy pool 생성이나 default tenant fallback을 resolver 안에서 수행하지
마세요. 비동기 해석이 필요하면 route 진입 전에 끝내거나 별도 API를 제안해야
합니다. 현재 함수는 의도적으로 동기 resolver만 받습니다.

context가 없으면 resolver를 호출하지 않고 `MissingTenantContextException`을
던집니다. resolver 예외는 동일한 인스턴스로 전파됩니다. `Database`를 해석한
뒤의 transaction 예외, 취소, interruption과 metric 동작은
`exposedJdbcTransaction` 계약을 그대로 따릅니다.

## 실패 처리 {#failures}

어댑터는 `StatusPages`나 HTTP 정책을 설치하지 않습니다. 애플리케이션의
기존 정책에서 안정적인 분류를 조합하세요.

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

resolver가 `NoSuchElementException`이 아닌 자체 예외를 사용하면 같은 방식으로
매핑하세요. raw tenant 식별자, request header, URL, SQL, credential 또는
그 값을 포함한 exception message를 log나 tag에 넣지 마세요.

## Lifecycle과 metric {#operations}

tenant-to-database map, database, pool, dispatcher와 선택적 `MeterRegistry`는
호출자가 소유합니다. 어댑터가 생성하거나 닫지 않습니다. 기존 transaction
timer는 `jdbc` backend와 success/failure/cancellation outcome tag를 유지하며
tenant 식별자를 추가하지 않습니다.

## 테스트 {#testing}

context 누락 fail-fast, resolver exception identity, 여러 database의 exact
routing, concurrent call 격리, dispatcher 실행과 취소 재전파를 검증하세요.
먼저 H2를 실행하고 설정된 경우 PostgreSQL·MySQL Testcontainers suite를
순서대로 실행합니다.

## 워크숍 {#workshops}

이 develop-only release line에는 선택형 tenant JDBC workshop이 없습니다.

## 제한 사항 {#limitations}

이 어댑터는 tenant header parsing, request authentication, database 생성,
schema migration 또는 fallback database를 제공하지 않습니다. 이 정책들은
애플리케이션 책임입니다. 이 개발 train의 upstream tenant 좌표는 검증된
`2.0.0-SNAPSHOT` snapshot과 immutable catalog commit
`29d858bd22553a31709123908a2eb5c5644093b3`에 고정합니다.

## 출처 {#sources}

- [Ktor server StatusPages](https://ktor.io/docs/server-status-pages.html)
- [Kotlin `asCoroutineDispatcher`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-coroutine-dispatcher.html)
