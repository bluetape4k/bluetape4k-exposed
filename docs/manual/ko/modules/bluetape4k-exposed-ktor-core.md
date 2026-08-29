---
manualId: "bluetape4k-exposed-ktor-core"
id: "bluetape4k-exposed-ktor-core"
title: "Exposed Ktor 코어"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor-core"
sourceDir: "ktor/core"
releaseRef: "develop"
releaseStatus: "develop-only"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core
---

# Exposed Ktor 코어

선택형 Exposed 아티팩트가 공유하는 backend-neutral Ktor health, readiness, 오류, 지표 계약입니다.

## 문제 {#problem}

기존 Ktor 아티팩트는 JDBC, R2DBC, cache API를 한 번에 결합했습니다. 이 코어 모듈은 해당 backend와 무관한 라우트·오류 계약만 소유합니다.

## 사용하기 좋은 경우 {#when-to-use}

데이터베이스 어댑터 없이 health 라우트와 readiness probe가 필요할 때 사용합니다. 실제 backend에 맞는 child adapter만 추가하세요.

## 의존성 좌표 {#coordinates}

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
```

## 핵심 개념 {#concepts}

`ExposedKtorReadinessProbe`는 호출자가 소유하며 취소에 협력해야 합니다. 등록 시 component와 backend tag를 검증·상태 고정하고, 하나의 monotonic deadline 아래 순차 실행합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
route.bluetape4kExposedHealthRoutes(probes = listOf(probe))
```

호출자 소유의 cooperative probe를 등록한 뒤 기존 routing tree에 라우트를 추가합니다.

## 작업별 API {#api-by-task}

- `ExposedKtorCooperativeReadinessProbe`를 구현합니다.
- `Route.bluetape4kExposedHealthRoutes`로 liveness와 readiness를 등록합니다.
- 기존 `StatusPages` 블록에서 `bluetape4kExposedCoreErrors()`를 조합합니다.

## 권장 패턴 {#patterns}

probe는 작고 취소 가능하며 resource를 소유하지 않게 하세요. component는 안정적인 low-cardinality 이름으로 유지하고 애플리케이션의 `MeterRegistry`를 주입합니다.

## 연동 {#integrations}

`bluetape4k-exposed-ktor-jdbc`, `bluetape4k-exposed-ktor-r2dbc`,
`bluetape4k-exposed-ktor-cache` 또는 tenant 전용 JDBC/R2DBC 어댑터를 필요한
backend에 따라 선택합니다. 코어는 dispatcher, pool, database, scope를
만들거나 닫지 않습니다.

## 구성 {#configuration}

probe는 1~16개까지 등록할 수 있고 component는 `[a-z][a-z0-9_.-]{0,62}` 형식의 고유 이름이어야 합니다. 경로는 literal absolute path이며 timeout은 유한한 양수입니다.

## 실패 모드 {#failures}

잘못된 등록은 라우트 설치 전에 실패합니다. probe 예외는 `DOWN`, 공유 deadline 만료는 `TIMEOUT`이 되며 호출자 취소는 다시 전달됩니다. 오류 응답은 고정 문구만 반환합니다.

## 운영 {#operations}

readiness는 동시에 하나의 probe만 실행합니다. 지표 tag는 `backend`, `operation`, `component`, `outcome`으로 고정합니다. 인증, rate limit, telemetry와 종료는 애플리케이션이 소유합니다.

## 테스트 {#testing}

결정적인 cooperative fake로 라우트 등록, deadline, 취소, 오류 정제, 중복 component, meter 충돌을 검증합니다.

## 워크숍 {#workshops}

선택형 Ktor 모듈은 이 release line에서 develop-only이며 아직 별도 workshop 아티팩트가 없습니다.

## 제한 사항 {#limitations}

코어는 backend I/O를 실행하지 않으므로 blocking 또는 비협력 probe를 안전하게 만들 수 없습니다. JDBC와 R2DBC 동작은 각 어댑터가 담당합니다.

## 출처 {#sources}

- [Ktor routing 문서](https://ktor.io/docs/server-routing.html)
- [Kotlin coroutine 취소](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
