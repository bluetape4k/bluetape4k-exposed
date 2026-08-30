---
manualId: "bluetape4k-exposed-ktor-tenant-r2dbc"
id: "bluetape4k-exposed-ktor-tenant-r2dbc"
title: "Exposed Ktor Tenant R2DBC 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-tenant-r2dbc"
sourceDir: "ktor/tenant-r2dbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc
---

# Exposed Ktor Tenant R2DBC 어댑터

이미 binding된 `TenantId`로 Ktor call을 tenant별 `R2dbcDatabase`에 연결한
뒤 기존 coroutine-native transaction helper에 위임하는 opt-in 어댑터입니다.

## 문제 {#problem}

tenant마다 R2DBC database를 사용하는 애플리케이션은 blocking 작업이나
thread-local state 없이 concurrent call 사이의 tenant 선택을 격리해야
합니다. 이 어댑터는 call binding과 database resolver를 명시적으로 유지하고
기존 `suspendTransaction` 경계를 보존합니다.

## 사용하기 좋은 경우 {#when-to-use}

검증된 tenant context와 tenant별로 호출자가 소유한 R2DBC `R2dbcDatabase`가
있는 Ktor 애플리케이션에 사용합니다. database를 tenant별로 선택하지 않는
경우에는 일반 Ktor R2DBC 어댑터를 선택하세요.

## 핵심 개념 {#concepts}

`KtorTenantContext`가 call-local `TenantId`를 제공하고 resolver가 이를
`R2dbcDatabase`로 매핑한 뒤 `exposedTenantR2dbcTransaction`이 기존
coroutine-native helper로 위임합니다. thread-local tenant state나 암묵적
default는 추가하지 않습니다.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-tenant-r2dbc")
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

resolver는 동기 함수 계약이지만 빠른 non-blocking 조회여야 합니다. 원격 또는
비동기 tenant state는 이 transaction helper에 진입하기 전에 해석하세요.

## 작업별 API {#api-by-task}

- `KtorTenantContext.bindTenant(call, tenantId)`로 한 번 binding합니다.
- tenant routing 작업에는 `ApplicationCall.exposedTenantR2dbcTransaction`을 사용합니다.
- HTTP 오류 매핑은 애플리케이션의 기존 `StatusPages` 정책에 둡니다.

## 권장 패턴 {#patterns}

immutable map으로 resolver를 만들고 transaction 진입 전에 인증을 끝내세요.
map을 변경하거나 검증되지 않은 request 값에서 어댑터 안에서 tenant를 추론하지
마세요.

## 연동 {#integrations}

`bluetape4k-exposed-ktor-core`와 기존 Ktor R2DBC 어댑터와 함께 사용합니다.
JDBC와 cache integration은 별도 artifact이며 이 모듈은 Ktor plugin을 설치하거나
애플리케이션 resource를 소유하지 않습니다.

## 구성 {#configuration}

tenant map, R2DBC pool, 선택적 `MeterRegistry`와 `StatusPages`는 애플리케이션
코드에서 구성합니다. 모든 upstream tenant artifact는 동일한 dependency-platform
version을 사용하세요.

## Resolver 계약 {#resolver}

`databaseResolver`는 `KtorTenantContext.requireCurrent(call)` 다음,
`suspendTransaction`과 metric timing 시작 전에 호출됩니다. immutable·thread-safe
상태를 대상으로 O(1) exact-match 조회여야 합니다. network나 database I/O,
lazy pool 생성, default tenant fallback을 resolver 안에서 수행하지 마세요.

context가 없으면 resolver를 호출하지 않고 `MissingTenantContextException`을
던집니다. resolver 예외는 동일한 인스턴스로 전파됩니다. `R2dbcDatabase`를
해석한 뒤의 transaction 예외, 취소와 metric 동작은
`exposedR2dbcTransaction` 계약을 그대로 따릅니다.

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

tenant-to-database map, database, pool과 선택적 `MeterRegistry`는 호출자가
소유합니다. 어댑터가 생성하거나 닫지 않습니다. 기존 transaction timer는
`r2dbc` backend와 success/failure/cancellation outcome tag를 유지하며 tenant
식별자를 추가하지 않습니다.

## 테스트 {#testing}

context 누락 fail-fast, resolver exception identity, 여러 database의 exact
routing, concurrent call 격리와 취소 재전파를 검증하세요. 먼저 H2 R2DBC
테스트를 실행하고 설정된 경우 PostgreSQL·MySQL Testcontainers suite를
실행합니다.

## 워크숍 {#workshops}

이 develop-only release line에는 선택형 tenant R2DBC workshop이 없습니다.

## 제한 사항 {#limitations}

이 어댑터는 tenant header parsing, request authentication, database 생성,
schema migration 또는 fallback database를 제공하지 않습니다. 이 정책들은
애플리케이션 책임입니다. 이 개발 train의 upstream tenant 좌표는 검증된
`2.0.0-SNAPSHOT` snapshot과 immutable catalog commit
`29d858bd22553a31709123908a2eb5c5644093b3`에 고정합니다.

## 출처 {#sources}

- [Ktor server StatusPages](https://ktor.io/docs/server-status-pages.html)
- [Exposed R2DBC transactions](https://www.jetbrains.com/help/exposed/transactions.html)
