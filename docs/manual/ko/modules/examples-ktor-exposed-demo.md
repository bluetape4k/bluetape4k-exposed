---
manualId: "examples-ktor-exposed-demo"
id: "examples-ktor-exposed-demo"
title: "Ktor와 Exposed 예제"
locale: "ko"
kind: "example"
gradlePath: ":examples-ktor-exposed-demo"
sourceDir: "examples/ktor-exposed-demo"
releaseRef: "1.12.1"
artifact: null
---

# Ktor와 Exposed 예제

> H2 JDBC 카운트 경로와 PostgreSQL R2DBC 주문 확인 흐름을 함께 실행하고, 캐시 readiness·이벤트 전달·자원 소유권·종료까지 보여 주는 작은 Ktor 애플리케이션입니다.

## 학습 내용 {#problem}

간단한 Ktor 예제에서 놓치기 쉬운 연동 경계를 한 번에 확인합니다. 애플리케이션이 H2 JDBC 환경과 PostgreSQL R2DBC 환경을 직접 만들고, 블로킹 JDBC 작업을 이벤트 루프에서 분리하며, 오류 처리 플러그인은 한 번만 설치합니다. JDBC·R2DBC·캐시 상태 확인 경로를 제공하고 Ktor가 멈출 때 소유한 자원도 모두 정리합니다.

규모는 스모크 테스트에 가깝지만, 자원 생명주기와 디스패처를 다루는 방식은 실제 서비스에도 그대로 적용할 수 있습니다.

## 사전 조건 {#prerequisites}

- JDK와 저장소의 Gradle Wrapper
- 저장소의 PostgreSQL Compose 파일을 사용하는 Docker 환경이 필요합니다.
- 일반 계약 테스트는 Docker 없이 실행할 수 있지만 `postgresIntegrationTest`와 실제 애플리케이션 실행에는 PostgreSQL이 필요합니다.

## 실행 {#run}

Docker 없이 HTTP·서비스 계약을 검증합니다.

```bash
./gradlew :examples-ktor-exposed-demo:test
```

Docker를 사용할 수 있으면 PostgreSQL 검증도 순차적으로 실행합니다.

```bash
./gradlew :examples-ktor-exposed-demo:postgresIntegrationTest --no-parallel
```

PostgreSQL을 시작한 뒤 Netty 애플리케이션을 `8080` 포트에서 실행합니다.

```bash
docker compose -f examples/ktor-exposed-demo/compose.yaml up -d --wait
./gradlew :examples-ktor-exposed-demo:run
```

실행 후 `/healthz/exposed`, `/readyz/exposed`, `/transactions/jdbc-count`, `/transactions/r2dbc-count`, 주문 경로를 호출해 보세요.

## 확인할 결과 {#expected-result}

PostgreSQL이 준비되면 readiness 응답은 HTTP `200`이고 `jdbc`, `r2dbc`, `cache.orders`가 포함됩니다. `/transactions/jdbc-count`는 H2 초기 데이터 두 건을 세어 `2`를 반환합니다. 첫 `POST /orders/{orderId}/confirm`은 `eventPublished=true`를 반환하고, 순차적으로 다시 확인하면 `eventPublished=false`가 됩니다. 주문이 저장되면 `/transactions/r2dbc-count`도 한 건 증가합니다.

## 실패 진단 {#failures}

- `StatusPages`에서 `DuplicatePluginException` 발생: 코어 플러그인과 애플리케이션이 중복 설치한 것입니다. 코어 설정의 `installStatusPages = false`를 유지하고, 두 오류 매핑을 담은 `StatusPages`를 한 번만 설치하세요.
- `/healthz/exposed`가 없음: 코어 상태 경로와 Exposed 상태 경로 설정을 혼동했는지 확인합니다. 이 예제는 전자를 끄고 Exposed 플러그인의 `installHealthRoutes`를 켭니다.
- `/readyz/exposed`가 시간 초과되거나 일부 항목이 내려감: H2 JDBC 풀, PostgreSQL R2DBC 풀, `cache.orders` 일관성 점검을 각각 확인하세요. 준비 상태 점검 제한 시간은 2초입니다.
- 부하가 걸리면 `/transactions/jdbc-count`가 멈춤: `exposedJdbcTransaction`에 전용 JDBC 디스패처를 넘겼는지 확인합니다. 블로킹 JDBC 작업을 Ktor 이벤트 루프에서 실행하면 안 됩니다.
- `/transactions/r2dbc-count`나 주문 확인이 실패함: Compose PostgreSQL 서비스가 healthy인지, `DEMO_POSTGRES_*` 값이 서비스 설정과 같은지 확인합니다.
- JDBC 응답이 `2`가 아님: 서버 시작 전에 `initialize()`가 `DemoItems` 테이블과 H2 초기 데이터 두 건을 만들었는지 확인합니다.
- 테스트가 끝났는데 Gradle이 종료되지 않음: R2DBC repository·풀, Hikari 데이터 소스, JDBC 디스패처를 모두 닫는지 살펴봅니다.

## 다음 학습 경로 {#next}

[Ktor 연동 매뉴얼](bluetape4k-exposed-ktor.md)에서 플러그인 API를 익힌 다음 [트랜잭션 경계](../guides/transaction-boundaries.md)에서 더 큰 서비스의 JDBC·R2DBC 선택 기준을 살펴보세요. 이 예제보다 넓은 영속화 설계가 필요하다면 [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)으로 이어 가면 됩니다.

## 사용하기 좋은 경우 {#when-to-use}

운영 인프라를 붙이기 전에 Ktor 플러그인 설치 순서, 데이터베이스 소유권, 상태 확인 경로, 디스패처 격리, 종료 처리를 검증할 때 유용합니다. Ktor나 Exposed, bluetape4k Ktor 어댑터를 올릴 때 사용할 간결한 회귀 테스트이기도 합니다.

## 의존성 좌표 {#coordinates}

이 애플리케이션 자체는 라이브러리로 배포되지 않습니다. 사용하는 프로젝트에서는 중앙 BOM을 가져오고 bluetape4k 라이브러리마다 버전을 따로 적지 마세요. 2.0 마이그레이션 중인 develop 코드는 backend-selective artifact를 명시적으로 선택합니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-core")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-jdbc")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-r2dbc")
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor-cache")
}
```

선택형 좌표는 2.0 마이그레이션 동안 develop-only입니다. 안정판 `1.12.1`을 사용하는 프로젝트는 자식 artifact가 배포될 때까지 `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor`를 유지해야 합니다.

저장소 안의 예제 빌드는 관리 중인 Ktor·Exposed BOM 카탈로그도 함께 사용해 릴리스 `1.12.1`의 데모 의존성을 맞춥니다.

## 핵심 개념 {#concepts}

`KtorExposedDemoResources.create()`는 JDBC 카운트 경로용 H2 Hikari/Exposed `Database`와 주문 시나리오용 PostgreSQL `ConnectionPool`/`R2dbcDatabase`를 만듭니다. Caffeine 기반 R2DBC 주문 repository, 애플리케이션 소유 이벤트 publisher, 블로킹 JDBC 호출만 처리할 고정 크기 2의 코루틴 디스패처도 만듭니다. 전역 싱글턴에 맡기지 않고 애플리케이션이 이 자원을 직접 소유합니다.

`ApplicationStopped` 이벤트가 발생하면 `resources.close()`를 호출합니다. R2DBC 풀은 최대 5초 안에 폐기하고, Hikari 데이터 소스와 JDBC 디스패처도 닫습니다. 자원을 만든 Ktor 애플리케이션과 자원의 수명을 일치시키는 구조입니다.

## 빠르게 시작하기 {#quick-start}

1. PostgreSQL Compose 서비스를 시작합니다.
2. 모듈 테스트 또는 순차 PostgreSQL 통합 테스트를 실행합니다.
3. `run` 작업으로 애플리케이션을 시작합니다.
4. 트랜잭션·주문 경로보다 상태와 readiness를 먼저 확인합니다.
5. 프로세스를 멈추고 `docker compose -f examples/ktor-exposed-demo/compose.yaml down`을 실행한 뒤 Hikari, R2DBC, 디스패처 스레드가 남지 않는지 확인합니다.

## 작업별 API {#api-by-task}

| 작업 | API 또는 설정 | 역할 |
| --- | --- | --- |
| 자원 소유 | `KtorExposedDemoResources.create()` | H2 JDBC·PostgreSQL R2DBC·Caffeine repository·이벤트 publisher·디스패처를 만들고 JDBC 데이터 두 건과 주문 스키마를 준비합니다. |
| 자원 정리 | `monitor.subscribe(ApplicationStopped)` | Ktor 종료 시점에 자원을 함께 닫습니다. |
| 오류 플러그인 중복 방지 | `installStatusPages = false`와 한 번의 `install(StatusPages)` | 코어와 Exposed 오류 응답을 한 플러그인에 모읍니다. |
| 데이터베이스 상태 공개 | `installHealthRoutes = true` | JDBC·R2DBC·`cache.orders` 항목이 있는 `/healthz/exposed`와 `/readyz/exposed`를 추가합니다. |
| 준비 상태 제한 | `readinessProbeTimeout = 2.seconds` | 데이터베이스 점검이 끝없이 기다리지 않게 합니다. |
| JDBC 격리 | `exposedJdbcTransaction(..., blockingDispatcher = resources.jdbcDispatcher)` | 블로킹 작업을 Ktor 이벤트 루프 밖에서 실행합니다. |

## 권장 패턴 {#patterns}

데이터베이스 생성과 정리를 한 소유자에게 맡기고, 플러그인과 라우트에는 데이터베이스 핸들을 명시적으로 전달하세요. JDBC 경계마다 블로킹 디스패처가 드러나게 해야 합니다. `StatusPages` 같은 공통 플러그인은 한 번만 설치하고, 연동 도우미마다 별도 인스턴스를 설치하는 대신 처리기를 한곳에 조합합니다.

JDBC 카운트 경로에는 H2를, R2DBC 주문 시나리오에는 PostgreSQL을 사용하는 것이 의도한 설계입니다. readiness는 두 데이터베이스와 주문 캐시 일관성 점검을 확인하고, 두 카운트 경로는 서로 다른 데이터 저장소를 드러냅니다.

## 연동 {#integrations}

`installBluetape4kKtorCore`는 공통 Ktor 기능을 제공하지만 이 예제에서는 `StatusPages`와 코어 상태 경로를 끕니다. 애플리케이션이 `StatusPages`를 한 번 설치하고 코어·JDBC·R2DBC 오류 매핑을 조합합니다. backend별 readiness probe와 `bluetape4kExposedHealthRoutes`를 등록한 뒤 H2 데이터베이스, PostgreSQL 데이터베이스, JDBC 디스패처, 2초 준비 상태 제한을 선택한 자식 helper에 전달합니다.

## 설정 {#configuration}

H2 풀과 PostgreSQL R2DBC 풀은 로컬 실행과 테스트에 맞게 작게 설정되어 있습니다. PostgreSQL URL·사용자·비밀번호는 `DEMO_POSTGRES_R2DBC_URL`, `DEMO_POSTGRES_USER`, `DEMO_POSTGRES_PASSWORD`로 설정합니다. 운영 서비스에서는 인증 정보를 외부 설정으로 옮기고, 측정한 동시성에 맞춰 풀 크기를 정해야 합니다. 명시적인 소유권과 디스패처 경계는 그대로 유지하세요.

## 운영 {#operations}

`/healthz/exposed`는 플러그인 생존 상태, `/readyz/exposed`는 의존성 준비 상태로 다루세요. readiness에는 `jdbc`, `r2dbc`, `cache.orders`가 따로 표시됩니다. 2초 제한 시간을 관찰 지표로 남기면 반복되는 시간 초과가 HTTP 라우팅 문제가 아니라 풀 고갈, 디스패처 정체, 캐시 일관성 실패, 데이터베이스 연결 실패 때문인지 구분하기 쉽습니다.

## 테스트 {#testing}

일반 `KtorExposedDemoApplicationTest`는 Docker 없이 실행하는 인프로세스 계약 테스트입니다. `postgresIntegrationTest` 소스 세트는 PostgreSQL 자원을 순차적으로 시작해 readiness, JDBC 카운트, R2DBC 카운트, 주문 확인·재확인, 정리를 검증합니다. 따라서 실제 `run` 프로세스에는 Compose PostgreSQL이 필요하지만 빠른 계약 테스트에는 필요하지 않습니다.

실제 서비스에 적용할 때는 준비 상태 실패, Exposed 예외 매핑, 애플리케이션 반복 시작·종료 사례를 추가해 생명주기 회귀를 빠르게 발견할 수 있게 하세요.

## 학습 경로와 예제 {#workshops}

아래 릴리스 소스를 애플리케이션 설정, 자원 소유권, HTTP 단언, 의존성 선언 순서로 따라가 보세요. 이어서 Ktor 모듈 매뉴얼에서 설정 선택지를 살펴보고, 트랜잭션 가이드에서 작업 특성에 맞는 경계를 정합니다. 저장소와 스키마 변경, 운영 데이터베이스 예제가 필요해지면 워크숍으로 넘어가면 됩니다.

## 제약 사항 {#limitations}

H2 JDBC 부분은 결정적이지만 운영 데이터베이스의 SQL 방언과 풀 압력을 재현하지 않으며, PostgreSQL 경로도 네트워크 장애·마이그레이션·운영 용량 산정을 대신하지 않습니다. 고정된 디스패처와 풀 크기도 학습용 기본값이지 용량 권장치가 아닙니다. 인증과 권한, 메트릭 내보내기, 트래픽 드레이닝은 사용하는 애플리케이션에서 설계해야 합니다.

## 근거 자료 {#sources}

- [1.12.1 애플리케이션 설정](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt)
- [1.12.1 자원 소유권](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt)
- [1.12.1 통합 테스트](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt)
- [1.12.1 Gradle 빌드](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/build.gradle.kts)
