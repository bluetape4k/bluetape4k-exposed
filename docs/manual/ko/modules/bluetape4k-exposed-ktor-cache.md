---
manualId: "bluetape4k-exposed-ktor-cache"
id: "bluetape4k-exposed-ktor-cache"
title: "Exposed Ktor 캐시 어댑터"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-cache"
sourceDir: "ktor/cache"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache
---

# Exposed Ktor 캐시 어댑터

호출자 소유 Exposed cache repository와 실패 버퍼 상태를 위한 Ktor readiness contributor입니다.

## 문제 {#problem}

cache health 관찰 때문에 readiness 요청이 database, network, blocking 작업으로 변해서는 안 됩니다.

## 사용하기 좋은 경우 {#when-to-use}

JDBC, R2DBC, 실패 버퍼 또는 custom cache의 O(1) in-memory 상태를 Ktor 서비스에서 보고할 때 사용합니다.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

## 핵심 개념 {#concepts}

`ExposedKtorCacheContributor`는 유한한 상태와 측정치를 정제합니다. contributor는 호출자가 소유하고 side-effect-free, cancellation-cooperative이며 low-cardinality component를 사용합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val config = ExposedKtorCacheReadinessConfig(listOf(
    ExposedKtorCacheContributor.custom("local-cache") { ExposedKtorCacheStatus.UP },
))
val probes = exposedKtorCacheReadinessProbes(config)
route.bluetape4kExposedHealthRoutes(probes)
```

## 작업별 API {#api-by-task}

- `jdbcRepository`, `r2dbcRepository`, `snapshot`, `custom` contributor를 선택합니다.
- `ExposedKtorCacheReadinessConfig`으로 immutable 설정을 만듭니다.
- `exposedKtorCacheReadinessProbes`로 코어 probe 목록으로 변환합니다.

## 권장 패턴 {#patterns}

기존 health 상태만 읽고 supplier에서 I/O를 수행하지 마세요. component 이름은 안정적으로 유지하고 cache lifecycle은 애플리케이션이 소유합니다.

## 연동 {#integrations}

코어와 Exposed cache foundation만 의존합니다. JDBC Caffeine과 R2DBC Caffeine persistence 구현은 선택 사항입니다.

## 구성 {#configuration}

1~16개의 고유 contributor를 등록할 수 있습니다. queue와 실패 버퍼 측정치는 음수가 아니며 unavailable 값은 `NaN`으로 유지하고 0으로 바꾸지 않습니다.

## 실패 모드 {#failures}

supplier 예외는 `DOWN`, 요청 context 취소는 취소로 처리합니다. 잘못된 component와 측정치는 구성 시 실패하며 cache key, URL, SQL, cause를 반환하지 않습니다.

## 운영 {#operations}

cache probe는 코어의 공유 deadline 아래 순차 실행합니다. 코어 readiness 지표와 cache repository lifecycle report를 함께 관찰하세요.

## 테스트 {#testing}

O(1) 상태 변환, 음수 거부, supplier 취소, deadline, 중복 component, 정제된 응답을 검증합니다.

## 워크숍 {#workshops}

이 develop-only release line에는 선택형 cache workshop이 없습니다.

## 제한 사항 {#limitations}

contributor 계약은 blocking 또는 backend-I/O supplier를 안전하게 바꾸지 않습니다. 애플리케이션이 실제 in-memory observer를 제공해야 합니다.

## 출처 {#sources}

- [Micrometer meter 개념](https://docs.micrometer.io/micrometer/reference/concepts.html)
- [Caffeine cache](https://github.com/ben-manes/caffeine)
