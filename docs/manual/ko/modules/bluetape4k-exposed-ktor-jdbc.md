---
manualId: "bluetape4k-exposed-ktor-jdbc"
id: "bluetape4k-exposed-ktor-jdbc"
title: "Exposed Ktor JDBC 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-jdbc"
sourceDir: "ktor/jdbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc
---

# Exposed Ktor JDBC 어댑터

backend-neutral Ktor 코어를 사용하는 애플리케이션을 위한 JDBC 전용 readiness와 transaction helper입니다.

## 문제 {#problem}

blocking JDBC를 Ktor event loop에서 실행하면 요청 처리가 막히고, JDBC 소비자에게 R2DBC와 cache 클래스까지 전달됩니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 `Database`와 제한된 blocking dispatcher를 소유하는 JDBC 경로에 사용합니다.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
```

## 핵심 개념 {#concepts}

`exposedKtorJdbcReadinessProbe`는 `runInterruptible` 안에서 `SELECT 1`을 실행합니다. statement timeout은 설정값과 공유 readiness 잔여 시간 중 작은 값입니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val probe = exposedKtorJdbcReadinessProbe(database, jdbcDispatcher)
route.bluetape4kExposedHealthRoutes(listOf(probe))
```

## 작업별 API {#api-by-task}

- `exposedKtorJdbcReadinessProbe`로 JDBC readiness를 등록합니다.
- `ApplicationCall.exposedJdbcTransaction`으로 blocking transaction을 실행합니다.
- 기존 `StatusPages`에서 `bluetape4kExposedJdbcErrors()`를 조합합니다.

## 권장 패턴 {#patterns}

dispatcher 크기를 pool 동시성에 맞추고 transaction을 제한하세요. database/pool과 dispatcher의 생성·종료는 애플리케이션 lifecycle에서 수행합니다.

## 연동 {#integrations}

이 어댑터는 코어와 Exposed JDBC만 의존합니다. R2DBC와 cache는 별도 아티팩트입니다.

## 구성 {#configuration}

JDBC readiness와 transaction에는 dispatcher가 필요합니다. timeout은 유한한 양수여야 하며 sub-second JDBC 값은 driver timeout 최소 1초 규칙을 적용합니다.

## 실패 모드 {#failures}

event loop에서 blocking 작업을 실행하거나 dispatcher가 포화되면 요청이 지연될 수 있습니다. 데이터베이스 오류는 SQL과 cause를 노출하지 않는 고정 unavailable 응답으로 매핑합니다.

## 운영 {#operations}

코어 지표 registry로 readiness와 transaction 시간을 관찰하고 pool, dispatcher, 인증과 종료는 애플리케이션에서 관리합니다.

## 테스트 {#testing}

H2를 먼저 실행한 뒤 PostgreSQL과 MySQL Testcontainers 경로를 순서대로 검증합니다. 취소, statement timeout, dispatcher 격리를 포함하세요.

## 워크숍 {#workshops}

이 develop-only release line에는 선택형 JDBC workshop이 없습니다.

## 제한 사항 {#limitations}

statement 취소와 query timeout 지원은 driver마다 다릅니다. coroutine deadline은 요청 경계이지 비협력 driver의 즉시 중단을 보장하지 않습니다.

## 출처 {#sources}

- [Kotlin `runInterruptible`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)
- [Ktor server plugin](https://ktor.io/docs/server-plugins.html)
