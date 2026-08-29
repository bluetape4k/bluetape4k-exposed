---
manualId: "bluetape4k-exposed-ktor"
id: "bluetape4k-exposed-ktor"
title: "Exposed Ktor 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-exposed-ktor"
sourceDir: "ktor/exposed"
releaseRef: "1.12.1"
artifact: io.github.bluetape4k.exposed:bluetape4k-exposed-ktor
---

# Exposed Ktor 연동

> 애플리케이션이 소유한 Ktor 기반 객체에 JDBC/R2DBC 트랜잭션, readiness, 지표, 오류 응답을 명시적으로 연결합니다.

## 제공하는 기능 {#problem}

이 모듈은 `installBluetape4kExposedKtor`, `ApplicationCall.exposedJdbcTransaction`, `ApplicationCall.exposedR2dbcTransaction`, 라우트·StatusPages 확장을 선택적으로 제공합니다. 일반 Ktor 기반 기능을 설치하지 않으며 데이터베이스, 커넥션 풀, 블로킹 디스패처, `MeterRegistry`를 만들거나 닫지 않습니다. 이 자원의 생명주기는 모두 애플리케이션이 책임집니다.

## 사용하기 좋은 경우 {#when-to-use}

Ktor 애플리케이션에서 배포본의 Exposed 트랜잭션 함수, `/healthz/exposed`와 `/readyz/exposed` 라우트, 제한 시간이 있는 readiness 검사, Micrometer 측정, 클라이언트에 안전한 데이터베이스 오류 응답을 사용하려 할 때 적합합니다. Ktor를 쓴다는 이유만으로 R2DBC를 선택할 필요는 없습니다. 드라이버 성숙도, 작업 특성, 운영 도구, 나머지 호출 경로를 보고 고르세요. JDBC도 애플리케이션이 제공한 블로킹 디스패처로 격리하면 사용할 수 있습니다.

## 의존성 좌표 {#coordinates}

생태계 BOM을 가져오면 모듈 버전을 따로 적지 않아도 됩니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

이 아티팩트는 기존 소비자를 위한 compatibility aggregator로 유지됩니다. 새
애플리케이션은 `bluetape4k-exposed-ktor-core`와 실제로 사용하는 backend
어댑터만 선택하세요. `TenantId`로 database를 선택한다면
`-tenant-jdbc` 또는 `-tenant-r2dbc`를 사용합니다. 선택형 자식 아티팩트와
해당 매뉴얼은 2.0 마이그레이션 동안 develop-only이며 안정판 `1.12.1`에는
배포되지 않습니다. 안정판 소비자는 이 aggregator 좌표를 유지해야 합니다.
마이그레이션 기간에는 기존 좌표와 package를 사용할 수 있으므로 import와
dependency를 backend별로 옮긴 뒤 향후 major release에서 aggregator를 제거할
수 있습니다.

## 핵심 개념 {#concepts}

`Bluetape4kExposedKtorConfig`는 애플리케이션이 소유한 JDBC `Database`, JDBC 블로킹 디스패처, R2DBC `R2dbcDatabase`, `MeterRegistry`, 라우트 경로, probe 제한 시간을 선택적으로 받습니다. 보조 기능을 설치하면 선택한 StatusPages 매핑과 상태 라우트만 등록됩니다. 시작과 종료 작업을 숨기지 않습니다.

JDBC 트랜잭션 함수는 호출자가 준 디스패처로 이동해 Exposed `transaction(db = db)`을 실행합니다. R2DBC 함수는 Exposed `suspendTransaction(db = db)`을 사용합니다. 두 함수 모두 `CancellationException`을 다시 던지며 나머지 실패는 공통 HTTP 매핑을 위해 `ExposedKtorTransactionException`으로 감쌉니다.

## 빠르게 시작하기 {#quick-start}

애플리케이션 구성 단계에서 데이터베이스와 풀을 만듭니다. JDBC라면 풀 크기에 맞춘 제한된 디스패처도 준비하세요. 필요한 보조 기능만 설치합니다.

```kotlin
installBluetape4kExposedKtor(
    Bluetape4kExposedKtorConfig(
        jdbcDatabase = database,
        jdbcBlockingDispatcher = jdbcDispatcher,
        installHealthRoutes = true,
        meterRegistry = registry,
    )
)
```

애플리케이션 종료 경로에서 풀과 소유한 디스패처를 닫으세요. 플러그인에는 숨은 close 훅이 없습니다.

## 작업별 API {#api-by-task}

- 블로킹 JDBC는 `call.exposedJdbcTransaction(database, dispatcher, registry) { ... }`로 실행합니다.
- 코루틴 기반 R2DBC는 `call.exposedR2dbcTransaction(database, registry) { ... }`로 실행합니다.
- `bluetape4kExposedHealthRoutes` 또는 설치 옵션으로 liveness·readiness 라우트를 추가합니다.
- 애플리케이션의 단일 `StatusPages` 블록 안에 `bluetape4kExposedErrors()`를 추가해 데이터베이스 오류 응답을 등록합니다.
- `MeterRegistry`를 전달하면 트랜잭션과 readiness 시간을 기록합니다.

## 권장 패턴 {#patterns}

애플리케이션 범위 기반 객체 묶음을 하나 만들고 라우트나 서비스에 주입하세요. 여러 라우트가 같은 사용 사례를 공유한다면 트랜잭션 블록을 서비스에 둡니다. JDBC 디스패처는 풀에서 실제로 처리할 수 있는 동시 수보다 크게 만들지 말고 Ktor 이벤트 루프에서 JDBC를 직접 실행하지 마세요. 구조화된 동시성을 유지하고 정리를 마친 뒤 취소를 다시 던집니다.

## 연동 {#integrations}

`installBluetape4kExposedKtor`는 `StatusPages`가 이미 있는데 자체 설치 옵션까지 켜면 중복 설치를 거부합니다. 이때는 애플리케이션의 공통 블록에서 한 번만 조합합니다.

```kotlin
install(StatusPages) {
    bluetape4kErrorResponses()
    bluetape4kExposedErrors()
}
```

이 보조 기능은 content negotiation, bluetape4k Ktor core, 풀, 디스패처, registry를 설치하지 않습니다.

## 설정 {#configuration}

상태와 readiness 경로는 절대 경로여야 하고 probe와 JDBC query 제한 시간은 양수여야 합니다. 상태 라우트를 켜려면 JDBC 또는 R2DBC 데이터베이스가 하나 이상 필요합니다. JDBC readiness에는 블로킹 디스패처도 반드시 있어야 합니다. 기본값은 `/healthz/exposed`, `/readyz/exposed`, readiness 1초, JDBC query 1초입니다.

## 실패 유형과 해결 방법 {#failures}

- JDBC 데이터베이스만 제공하고 상태 라우트용 디스패처를 빠뜨림: 라우트를 설치하기 전에 설정 검증이 실패합니다.
- `StatusPages`가 이미 있는데 `installStatusPages=true`를 사용함: 설치가 실패하므로 기존 블록에 모든 매핑을 조합합니다.
- JDBC 요청 때문에 서버가 멈춤: 제공한 블로킹 디스패처를 거치지 않았거나 디스패처·풀이 포화된 상태입니다.
- 취소가 HTTP 500으로 바뀜: 애플리케이션 코드가 `CancellationException`을 삼킨 것이므로 다시 던지세요.
- readiness가 `DOWN` 또는 `TIMEOUT`임: 무작정 재시작하지 말고 backend별 probe 시간, 풀 연결 획득, 드라이버 오류, 설정한 제한 시간을 확인합니다.

## 운영 {#operations}

liveness 라우트는 데이터베이스를 조회하지 않고 보조 구성 요소가 살아 있다고 응답합니다. readiness는 `SELECT 1`을 실행합니다. JDBC는 제공한 디스패처와 query 제한 시간을 사용하고, R2DBC는 `suspendTransaction` 안에서 실행하며 둘 다 전체 readiness 제한 시간을 적용합니다. 선택적 timer 이름은 `bluetape4k.exposed.ktor.transaction`과 `bluetape4k.exposed.ktor.readiness`이고 `backend`, `operation`, `outcome`처럼 값의 종류가 제한된 태그를 사용합니다.

종료할 때는 새 트래픽을 먼저 막고 구조화된 진행 작업이 끝나거나 취소되기를 기다린 뒤 애플리케이션이 소유한 풀, 데이터베이스 자원, 디스패처, registry를 필요한 순서로 닫습니다. 플러그인은 이 자원을 닫지 않습니다.

## 테스트 {#testing}

정확한 모듈 테스트 명령은 다음과 같습니다.

```bash
./gradlew :bluetape4k-exposed-ktor:test
```

JDBC가 제공한 디스패처에서 실행되는지, R2DBC가 `suspendTransaction`을 사용하는지, 트랜잭션 성공·실패 매핑, 취소 재전파, StatusPages 중복 설치 거부, readiness 필수 자원 검증, timeout·down 응답, 제한된 지표 태그, 애플리케이션 종료 시 소유 자원 정리를 확인하세요.

## 학습 경로와 예제 {#workshops}

[Ktor 예제](examples-ktor-exposed-demo.md)를 실행한 뒤 [트랜잭션 경계](../guides/transaction-boundaries.md)를 읽으세요. JDBC는 [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop), 전체 suspend 데이터베이스 경로는 [Exposed R2DBC 워크숍](https://github.com/bluetape4k/exposed-r2dbc-workshop)으로 이어 갑니다.

## 제약 사항 {#limitations}

이 보조 기능은 기반 객체를 준비하거나 스키마를 만들거나 애플리케이션 대신 JDBC·R2DBC를 선택하지 않으며 분산 트랜잭션도 제공하지 않습니다. liveness는 데이터베이스 readiness가 아니고 readiness 성공도 해당 probe 결과만 설명합니다. 오류 응답은 클라이언트에 안전한 메시지만 반환하며 드라이버 세부 정보를 노출하지 않습니다.

## 근거 자료 {#sources}

- [`Bluetape4kExposedKtor.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt)
- [`Bluetape4kExposedKtorConfig.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt)
- [`ExposedKtorTransactions.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactions.kt)
- [`ExposedKtorHealthRoutes.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt)
- [`ExposedKtorMetrics.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetrics.kt)
- [`ExposedKtorStatusPages.kt`](../../../../ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPages.kt)
