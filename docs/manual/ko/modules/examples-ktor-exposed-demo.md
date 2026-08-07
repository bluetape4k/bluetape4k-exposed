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

> JDBC와 R2DBC 상태를 함께 점검하고, 실제 Exposed JDBC 트랜잭션까지 실행하는 작은 Ktor 애플리케이션입니다.

## 학습 내용 {#problem}

간단한 Ktor 예제에서 놓치기 쉬운 연동 경계를 한 번에 확인합니다. 애플리케이션이 두 H2 데이터베이스 환경을 직접 만들고, 블로킹 JDBC 작업을 이벤트 루프에서 분리하며, 오류 처리 플러그인은 한 번만 설치합니다. 상태 확인 경로를 제공하고 Ktor가 멈출 때 소유한 자원도 모두 정리합니다.

규모는 스모크 테스트에 가깝지만, 자원 생명주기와 디스패처를 다루는 방식은 실제 서비스에도 그대로 적용할 수 있습니다.

## 사전 조건 {#prerequisites}

- JDK와 저장소의 Gradle Wrapper
- 외부 데이터베이스와 Docker는 필요하지 않습니다. JDBC와 R2DBC가 각각 애플리케이션 소유의 인메모리 H2 데이터베이스를 사용합니다.

## 실행 {#run}

먼저 전체 HTTP 계약을 검증합니다.

```bash
./gradlew :examples-ktor-exposed-demo:test
```

Netty 애플리케이션을 `8080` 포트에서 실행하려면 다음 명령을 사용합니다.

```bash
./gradlew :examples-ktor-exposed-demo:run
```

실행 후 `/healthz/exposed`, `/readyz/exposed`, `/transactions/jdbc-count`를 차례로 호출해 보세요.

## 확인할 결과 {#expected-result}

두 상태 확인 경로가 HTTP `200`을 반환해야 합니다. Exposed 상태는 `UP`이고, 준비 상태에는 `jdbc`와 `r2dbc`가 각각 `UP`으로 나타납니다. `/transactions/jdbc-count`의 응답 본문은 `2`입니다. 이 값은 라우트가 Exposed JDBC 트랜잭션에 진입해 자원 초기화 단계에서 넣은 두 행을 세었다는 뜻입니다.

## 실패 진단 {#failures}

- `StatusPages`에서 `DuplicatePluginException` 발생: 코어 플러그인과 애플리케이션이 중복 설치한 것입니다. 코어 설정의 `installStatusPages = false`를 유지하고, 두 오류 매핑을 담은 `StatusPages`를 한 번만 설치하세요.
- `/healthz/exposed`가 없음: 코어 상태 경로와 Exposed 상태 경로 설정을 혼동했는지 확인합니다. 이 예제는 전자를 끄고 Exposed 플러그인의 `installHealthRoutes`를 켭니다.
- `/readyz/exposed`가 시간 초과되거나 일부 항목이 내려감: JDBC와 R2DBC 초기화를 따로 확인하세요. 준비 상태 점검 제한 시간은 2초입니다.
- 부하가 걸리면 `/transactions/jdbc-count`가 멈춤: `exposedJdbcTransaction`에 전용 JDBC 디스패처를 넘겼는지 확인합니다. 블로킹 JDBC 작업을 Ktor 이벤트 루프에서 실행하면 안 됩니다.
- 응답이 `2`가 아님: 서버 시작 전에 `initialize()`가 `DemoItems` 테이블과 초기 데이터 두 건을 만들었는지 확인합니다.
- 테스트가 끝났는데 Gradle이 종료되지 않음: R2DBC 풀, Hikari 데이터 소스, JDBC 디스패처를 모두 닫는지 살펴봅니다. 이 예제는 Docker를 사용하지 않습니다.

## 다음 학습 경로 {#next}

[Ktor 연동 매뉴얼](bluetape4k-exposed-ktor.md)에서 플러그인 API를 익힌 다음 [트랜잭션 경계](../guides/transaction-boundaries.md)에서 더 큰 서비스의 JDBC·R2DBC 선택 기준을 살펴보세요. 이 예제보다 넓은 영속화 설계가 필요하다면 [Exposed 워크숍](https://github.com/bluetape4k/exposed-workshop)으로 이어 가면 됩니다.

## 사용하기 좋은 경우 {#when-to-use}

운영 인프라를 붙이기 전에 Ktor 플러그인 설치 순서, 데이터베이스 소유권, 상태 확인 경로, 디스패처 격리, 종료 처리를 검증할 때 유용합니다. Ktor나 Exposed, bluetape4k Ktor 어댑터를 올릴 때 사용할 간결한 회귀 테스트이기도 합니다.

## 의존성 좌표 {#coordinates}

이 애플리케이션 자체는 라이브러리로 배포되지 않습니다. 사용하는 프로젝트에서는 중앙 BOM을 가져오고 bluetape4k 라이브러리마다 버전을 따로 적지 마세요.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k:bluetape4k-exposed-ktor")
}
```

저장소 안의 예제 빌드는 관리 중인 Ktor·Exposed BOM 카탈로그도 함께 사용해 릴리스 `1.12.1`의 데모 의존성을 맞춥니다.

## 핵심 개념 {#concepts}

`KtorExposedDemoResources.create()`는 서로 독립된 인메모리 H2 환경 두 개를 만듭니다. JDBC 쪽은 Hikari와 Exposed `Database`, R2DBC 쪽은 `ConnectionPool`과 `R2dbcDatabase`로 구성합니다. 블로킹 JDBC 호출만 처리할 고정 크기 2의 코루틴 디스패처도 별도로 만듭니다. 컨테이너나 전역 싱글턴에 맡기지 않고 애플리케이션이 이 자원을 직접 소유합니다.

`ApplicationStopped` 이벤트가 발생하면 `resources.close()`를 호출합니다. R2DBC 풀은 최대 5초 안에 폐기하고, Hikari 데이터 소스와 JDBC 디스패처도 닫습니다. 자원을 만든 Ktor 애플리케이션과 자원의 수명을 일치시키는 구조입니다.

## 빠르게 시작하기 {#quick-start}

1. Docker 준비 없이 모듈 테스트를 실행합니다.
2. `run` 작업으로 애플리케이션을 시작합니다.
3. 트랜잭션 경로를 호출하기 전에 상태와 준비 상태부터 확인합니다.
4. 프로세스를 멈춘 뒤 Hikari, R2DBC, 디스패처 스레드가 남지 않는지 확인합니다.

## 작업별 API {#api-by-task}

| 작업 | API 또는 설정 | 역할 |
| --- | --- | --- |
| 자원 소유 | `KtorExposedDemoResources.create()` | JDBC·R2DBC·디스패처를 만들고 JDBC 데이터 두 건을 준비합니다. |
| 자원 정리 | `monitor.subscribe(ApplicationStopped)` | Ktor 종료 시점에 자원을 함께 닫습니다. |
| 오류 플러그인 중복 방지 | `installStatusPages = false`와 한 번의 `install(StatusPages)` | 코어와 Exposed 오류 응답을 한 플러그인에 모읍니다. |
| 데이터베이스 상태 공개 | `installHealthRoutes = true` | `/healthz/exposed`와 `/readyz/exposed`를 추가합니다. |
| 준비 상태 제한 | `readinessProbeTimeout = 2.seconds` | 데이터베이스 점검이 끝없이 기다리지 않게 합니다. |
| JDBC 격리 | `exposedJdbcTransaction(..., blockingDispatcher = resources.jdbcDispatcher)` | 블로킹 작업을 Ktor 이벤트 루프 밖에서 실행합니다. |

## 권장 패턴 {#patterns}

데이터베이스 생성과 정리를 한 소유자에게 맡기고, 플러그인과 라우트에는 데이터베이스 핸들을 명시적으로 전달하세요. JDBC 경계마다 블로킹 디스패처가 드러나게 해야 합니다. `StatusPages` 같은 공통 플러그인은 한 번만 설치하고, 연동 도우미마다 별도 인스턴스를 설치하는 대신 처리기를 한곳에 조합합니다.

JDBC와 R2DBC가 서로 다른 H2 데이터베이스를 쓰는 것은 의도한 설계입니다. 준비 상태는 두 환경에 각각 연결할 수 있는지 검증하고, 카운트 경로는 JDBC 트랜잭션이 동작하는지 보여 줍니다. 두 데이터베이스가 같은 데이터를 공유한다고 오해하면 안 됩니다.

## 연동 {#integrations}

`installBluetape4kKtorCore`는 공통 Ktor 기능을 제공하지만 이 예제에서는 `StatusPages`와 코어 상태 경로를 끕니다. 애플리케이션이 `StatusPages`를 한 번 설치하고 `bluetape4kErrorResponses()`와 `bluetape4kExposedErrors()`를 함께 등록합니다. 이어서 `installBluetape4kExposedKtor`에 두 데이터베이스 핸들, JDBC 디스패처, 2초 준비 상태 제한을 전달합니다.

## 설정 {#configuration}

두 H2 풀은 로컬 실행과 테스트에 맞게 작게 설정되어 있습니다. 데이터베이스 이름에는 호출자가 넘긴 접미사를 붙이므로 테스트의 `create("smoke")`처럼 격리된 인메모리 데이터베이스를 만들 수 있습니다. 운영 서비스에서는 URL과 인증 정보를 외부 설정으로 옮기고, 측정한 동시성에 맞춰 풀 크기를 정해야 합니다. 명시적인 소유권과 디스패처 경계는 그대로 유지하세요.

## 운영 {#operations}

`/healthz/exposed`는 플러그인 생존 상태, `/readyz/exposed`는 의존성 준비 상태로 다루세요. 준비 상태에는 `jdbc`와 `r2dbc`가 따로 표시됩니다. 2초 제한 시간을 관찰 지표로 남기면 반복되는 시간 초과가 HTTP 라우팅 문제가 아니라 풀 고갈, 디스패처 정체, 데이터베이스 연결 실패 때문인지 구분하기 쉽습니다.

## 테스트 {#testing}

`KtorExposedDemoApplicationTest`는 `testApplication` 안에서 자원을 만들고 `use`로 닫습니다. 두 상태 확인 경로를 호출한 다음 트랜잭션 경로가 `2`를 반환하는지 검증합니다. 포트를 열지 않는 인프로세스 통합 테스트이며 Docker도 필요하지 않습니다.

실제 서비스에 적용할 때는 준비 상태 실패, Exposed 예외 매핑, 애플리케이션 반복 시작·종료 사례를 추가해 생명주기 회귀를 빠르게 발견할 수 있게 하세요.

## 학습 경로와 예제 {#workshops}

아래 릴리스 소스를 애플리케이션 설정, 자원 소유권, HTTP 단언, 의존성 선언 순서로 따라가 보세요. 이어서 Ktor 모듈 매뉴얼에서 설정 선택지를 살펴보고, 트랜잭션 가이드에서 작업 특성에 맞는 경계를 정합니다. 저장소와 스키마 변경, 운영 데이터베이스 예제가 필요해지면 워크숍으로 넘어가면 됩니다.

## 제약 사항 {#limitations}

인메모리 H2는 예제를 결정적으로 만들지만 운영 데이터베이스의 SQL 방언, 네트워크 장애, 풀 압력, 마이그레이션, JDBC·R2DBC 데이터 공유를 재현하지는 않습니다. 고정된 디스패처와 풀 크기도 학습용 기본값이지 용량 권장치가 아닙니다. 인증과 권한, 메트릭 내보내기, 트래픽 드레이닝은 사용하는 애플리케이션에서 설계해야 합니다.

## 근거 자료 {#sources}

- [1.12.1 애플리케이션 설정](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplication.kt)
- [1.12.1 자원 소유권](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoResources.kt)
- [1.12.1 통합 테스트](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/examples/exposed/ktor/KtorExposedDemoApplicationTest.kt)
- [1.12.1 Gradle 빌드](https://github.com/bluetape4k/bluetape4k-exposed/blob/1.12.1/examples/ktor-exposed-demo/build.gradle.kts)
