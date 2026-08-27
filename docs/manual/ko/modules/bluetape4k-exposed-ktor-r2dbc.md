---
manualId: "bluetape4k-exposed-ktor-r2dbc"
id: "bluetape4k-exposed-ktor-r2dbc"
title: "Exposed Ktor R2DBC 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-r2dbc"
sourceDir: "ktor/r2dbc"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc
---

# Exposed Ktor R2DBC 어댑터

선택형 Ktor 경계를 위한 coroutine-native R2DBC readiness, transaction, 오류 helper입니다.

## 문제 {#problem}

R2DBC 소비자는 사용하지 않는 blocking JDBC API나 cache lifecycle을 상속하지 않아야 합니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 Exposed `R2dbcDatabase`와 coroutine-first persistence 경로를 소유할 때 사용합니다.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
```

## 핵심 개념 {#concepts}

`exposedKtorR2dbcReadinessProbe`는 취소 가능한 `suspendTransaction` 안에서 `SELECT 1`을 실행합니다. query timeout 정책은 호출자가 소유한 Exposed R2DBC 설정을 따릅니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val probe = exposedKtorR2dbcReadinessProbe(r2dbcDatabase)
route.bluetape4kExposedHealthRoutes(listOf(probe))
```

## 작업별 API {#api-by-task}

- `exposedKtorR2dbcReadinessProbe`로 readiness를 등록합니다.
- `ApplicationCall.exposedR2dbcTransaction`으로 coroutine transaction을 실행합니다.
- `StatusPages`에서 `bluetape4kExposedR2dbcErrors()`를 조합합니다.

## 권장 패턴 {#patterns}

transaction block을 suspend-native로 유지하고 취소를 전달하세요. pool과 database resource의 종료는 하나의 애플리케이션 lifecycle owner가 담당합니다.

## 연동 {#integrations}

이 모듈은 코어와 Exposed R2DBC만 의존합니다. JDBC와 cache API는 의도적으로 분리되어 있습니다.

## 구성 {#configuration}

component와 route path는 코어 검증 규칙을 따릅니다. R2DBC timeout 동작은 호출자 소유 database와 driver에 따릅니다.

## 실패 모드 {#failures}

R2DBC 오류는 고정 unavailable 응답으로 매핑하고 취소는 다시 전달합니다. timeout과 실패 probe는 driver detail 없이 `TIMEOUT` 또는 `DOWN`을 보고합니다.

## 운영 {#operations}

제한된 backend와 outcome tag를 가진 코어 readiness·transaction 지표를 내보냅니다. pool과 driver 지표는 별도로 관찰하세요.

## 테스트 {#testing}

R2DBC H2 suite 뒤에 PostgreSQL/MySQL integration 경로를 실행합니다. 취소, driver timeout, 공유 deadline 순서를 포함하세요.

## 워크숍 {#workshops}

이 develop-only release line에는 선택형 R2DBC workshop이 없습니다.

## 제한 사항 {#limitations}

query 취소와 timeout 지원은 driver 구현에 따라 다릅니다. cooperative coroutine cancellation은 여전히 요청 계약입니다.

## 출처 {#sources}

- [R2DBC 사양](https://r2dbc.io/spec/0.9.0.RELEASE/spec/html/)
- [Kotlin structured concurrency](https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency)
