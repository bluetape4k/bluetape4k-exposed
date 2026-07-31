# Issue #275 Ktor Exposed 통합 설계

날짜: 2026-06-23
이슈: #275
마일스톤: 1.11.0
브랜치: `feat/issue-275-ktor-integration`

## 문제

`bluetape4k-exposed`는 JDBC/R2DBC repository, cache decorator, column codec,
database-specific helper, Spring Boot 4 integration을 제공하지만 Ktor
application에서 Exposed를 명시적으로 설치하고 운영 표면을 붙이는 전용 모듈이
없다. Ktor는 Spring Boot auto-configuration이나 repository scanning 모델이
아니므로 Spring Boot module을 복제하면 Ktor다운 explicit plugin/route helper
계약을 깨기 쉽다.

목표는 Ktor application이 caller-owned `Database`/`R2dbcDatabase`를 넘기고,
route handler 안에서 JDBC `transaction {}` 또는 R2DBC `suspendTransaction {}`
경계를 명시적으로 사용할 수 있게 하는 것이다. 상태 응답은 기존
`bluetape4k-ktor-core`의 `ApiErrorResponse`, `respondApiError`, health route
스타일을 재사용해야 한다.

## 현재 근거

- 현재 Exposed dependency line은 `gradle/libs.versions.toml`의
  `exposed = "1.3.0"`이다. shared dependency catalog도 Exposed `1.3.0`과
  Ktor `3.5.0`을 제공한다.
- 이 저장소는 `settings.gradle.kts`에서 `exposed/*`를 자동 포함하고,
  `spring-boot/jdbc`, `spring-boot/r2dbc`, `spring-boot/batch-exposed`,
  `spring-boot/spring-modulith`처럼 integration family는 explicit mapping으로
  published-style Gradle project name에 연결한다.
- BOM은 `exposed/bom/build.gradle.kts`에서 published module 전체를 자동
  constraint로 수집하고, examples/benchmark/demo module은 제외한다.
- workflow path filter는 module family별로 명시되어 있어 새 Ktor module과
  example 추가 시 CI/Nightly coverage를 별도 wiring해야 한다.
- `bluetape4k-projects`의 Ktor family는 다음 표면을 사용한다.
  - `Application.installBluetape4kKtorCore(config)`
  - `Bluetape4kKtorCoreConfig`
  - `StatusPagesConfig.bluetape4kErrorResponses()`
  - `ApplicationCall.respondApiError(...)`
  - `Route.bluetape4kHealthRoutes(...)`
  - `ApplicationTestBuilder.installBluetape4kKtorCoreForTest(...)`
  - `HttpResponse.shouldHaveApiError(...)`
- official Ktor documentation 기준 integration surface는 `Application` plugin
  installation, `Route` extension, `StatusPages` exception mapping, and
  `testApplication {}` contract tests가 자연스럽다. Ktor 3.4+ 문서는
  request cancellation을 `HttpRequestLifecycle`과 coroutine cancellation으로
  다루므로 transaction wrapper는 `CancellationException`을 삼키면 안 된다.

## 결정

### 모듈

- 물리 경로: `ktor/exposed`
- Gradle project/artifact: `:bluetape4k-exposed-ktor`
- Package root: `io.bluetape4k.exposed.ktor`
- 일반 Ktor 헬퍼는 계속 `bluetape4k-projects`에 둔다.

이 경로는 Spring Boot integration family와 같은 root-level integration
family 패턴을 따른다. `exposed/ktor`도 자동 include가 가능하지만, Ktor 관련
surface가 Exposed dialect/helper라기보다 server integration이므로
`ktor/exposed`가 책임을 더 명확히 드러낸다.

### 소유권

모듈은 pool, registry, global lifecycle을 만들지 않는다. application이 다음
객체를 공급한다.

- JDBC: `org.jetbrains.exposed.v1.jdbc.Database`
- R2DBC: `org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase`
- Optional metrics: `io.micrometer.core.instrument.MeterRegistry`

`DataSource`, `ConnectionFactory`, pool, OpenTelemetry SDK, Prometheus registry는
caller-owned이다.

### Ktor Core 구성

`bluetape4k-exposed-ktor`는 `bluetape4k-ktor-core`를 `api` dependency로
사용하고 다음 타입/함수를 재사용한다.

- `ApiErrorResponse`
- `HealthResponse`
- `ApplicationCall.respondApiError(...)`
- `requireAbsoluteKtorPath`와 같은 path validation style

그러나 `installBluetape4kExposedKtor`는 `installBluetape4kKtorCore()`를 자동
호출하지 않는다. Content negotiation, 일반 core status pages, 일반
`/healthz`/`/readyz` route는 application이 `bluetape4k-ktor-core` 또는 자체 Ktor
configuration으로 명시적으로 설치한다. 이 모듈은 Exposed 전용 status mapping,
Exposed 전용 liveness/readiness route, Exposed transaction 헬퍼만 추가한다.

따라서 README/KDoc example은 `installBluetape4kKtorCore(
Bluetape4kKtorCoreConfig(installStatusPages = false)
)` 뒤에 caller가 직접 `install(StatusPages) { bluetape4kErrorResponses();
bluetape4kExposedErrors() }`로 status mapping을 조합하는 형태를 보여준다.
Exposed status mapping은 core `StatusPages`와 중복 설치되지 않도록 별도 extension
으로 제공한다. `installBluetape4kExposedKtor`의 `installStatusPages` 기본값도
`false`이다. `installStatusPages = true`는 standalone 사용을 위해 `StatusPages`가
아직 설치되지 않은 경우에만 설치하며, caller가 Ktor JSON/content negotiation을
이미 설치했거나 응답 serialization을 자기 Ktor configuration으로 보장한 경우에만
지원한다. 이미 `StatusPages`가 설치된 application에서 이 flag를 켜면 기존 config를
reopen/extend하려고 하지 말고 clear message로 fail fast 한다. Core mapping과
Exposed mapping을 같이 쓰는 supported path는 caller가 하나의 `install(StatusPages)`
block 안에서 `bluetape4kErrorResponses()`와 `bluetape4kExposedErrors()`를 조합하는
방식이다.

### Lifecycle 계약

모듈은 다음 객체를 닫거나 mutate하지 않는다.

- `Database`
- `R2dbcDatabase`
- `DataSource`
- `ConnectionFactory`
- `MeterRegistry`

모듈은 global registry, shutdown hook, connection pool, dispatcher, executor를
생성하지 않는다. Test/example이 pool이나 executor를 만들면 해당 test/example이
`finally` 또는 lifecycle hook에서 닫는다.

### 공개 API 개요

```kotlin
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig = Bluetape4kExposedKtorConfig(),
)

class Bluetape4kExposedKtorConfig(
    val jdbcDatabase: Database? = null,
    val jdbcBlockingDispatcher: CoroutineDispatcher? = null,
    val r2dbcDatabase: R2dbcDatabase? = null,
    val installStatusPages: Boolean = false,
    val installHealthRoutes: Boolean = false,
    val healthPath: String = "/healthz/exposed",
    val readinessPath: String = "/readyz/exposed",
    val readinessProbeTimeout: Duration = 1.seconds,
    val jdbcQueryTimeout: Duration = 1.seconds,
    val meterRegistry: MeterRegistry? = null,
)

fun StatusPagesConfig.bluetape4kExposedErrors()

fun Route.bluetape4kExposedHealthRoutes(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    healthPath: String = "/healthz/exposed",
    readinessPath: String = "/readyz/exposed",
    readinessProbeTimeout: Duration = 1.seconds,
    jdbcQueryTimeout: Duration = 1.seconds,
)

suspend fun <T> ApplicationCall.exposedJdbcTransaction(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    block: Transaction.() -> T,
): T

suspend fun <T> ApplicationCall.exposedR2dbcTransaction(
    db: R2dbcDatabase,
    block: suspend R2dbcTransaction.() -> T,
): T
```

구현 단계에서 overload 이름은 소스 호환성과 가독성을 기준으로
정리할 수 있지만, 다음 계약은 유지한다.

- transaction 헬퍼는 호출자가 넘긴 database만 사용한다.
- R2DBC 헬퍼는 `suspendTransaction(db = ...)`를 사용하고 cancellation을
  보존한다.
- JDBC 헬퍼는 `suspend` API이며 호출자가 넘긴 `CoroutineDispatcher` 안에서 기존
  Exposed `transaction(db = ...)`를 실행한다. Dispatcher에는 `Dispatchers.VT`,
  application 소유 dispatcher 또는 호출자가 직접 선택한 blocking isolation
  dispatcher를 넘긴다. 헬퍼는 숨겨진 dispatcher를 만들지 않으며,
  README/KDoc/example/test는 Ktor route coroutine에서 blocking JDBC를 직접 실행하지
  않는다. `CoroutineContext`나 `EmptyCoroutineContext`를 받아 event-loop에서
  blocking transaction이 실행될 수 있는 API는 제공하지 않는다.
- status mapping은 Exposed/SQL exception을 안전한 `ApiErrorResponse`로 바꾸되,
  `CancellationException`은 재던진다.
- metrics는 호출자가 `MeterRegistry`를 제공할 때만 timer/counter를 기록한다.
- `installHealthRoutes = true` 또는 `bluetape4kExposedHealthRoutes(...)` 호출 시
  `jdbcDatabase`와 `r2dbcDatabase`가 모두 `null`이면 fail fast 한다. StatusPages
  only 사용은 `installHealthRoutes = false`로 허용한다.
- 기본 `installBluetape4kExposedKtor()` 호출은 의도적으로 아무 작업도 하지 않을 수 있다.
  KDoc/README는 installer가 Exposed 전용 기능만 추가하며, 실제 사용에는
  `installStatusPages = true`, `installHealthRoutes = true` + backend, 또는 direct
  route/transaction 헬퍼 호출 중 하나가 필요하다고 경고한다. 테스트는 기본
  installer가 StatusPages나 health/readiness route를 추가하지 않음을 검증한다.
- `jdbcDatabase`가 있고 readiness route 설치가 요청되면 `jdbcBlockingDispatcher`는
  필수다. 없으면 fail fast 한다. R2DBC-only readiness는
  `jdbcBlockingDispatcher` 없이 허용한다.

### Readiness 계약

- `/healthz/exposed`는 Exposed integration liveness endpoint다. DB probe를 하지
  않고 HTTP 200 + `HealthResponse.up(details = mapOf("exposed" to "UP"))`를
  반환한다.
- `/readyz/exposed`는 설정한 backend만 probe한다.
- 설정한 backend가 모두 UP이면 HTTP 200 +
  `HealthResponse.up(details = mapOf("jdbc" to "UP", "r2dbc" to "UP"))`를
  반환한다. 한 backend만 설정된 경우 해당 key만 포함한다.
- 설정한 backend 중 하나라도 실패하면 HTTP 503 +
  `HealthResponse.down(details = ...)`를 반환한다.
- 설정하지 않은 backend는 details에서 제외한다. `jdbcDatabase`와 `r2dbcDatabase`가
  모두 없는데 readiness route 설치가 요청되면 fail fast 한다.
- `readinessProbeTimeout` default는 1초이며 0 이하 값은 fail fast 한다.
- `jdbcQueryTimeout` default는 1초이며 0 이하 값은 fail fast 한다. JDBC readiness
  implementation은 JDBC/Exposed statement-level query timeout을 적용해야 한다.
- 모듈 내부 readiness deadline은 `timeout`으로 분류한다. 외부 호출자
  cancellation과 request cancellation에서 발생한 `CancellationException`은
  재던지고 `timeout`이나 SQL error로 변환하지 않는다.
- probe는 schema/table scan을 하지 않는다. JDBC는 호출자가 소유하는 blocking dispatcher
  안에서 single minimal `SELECT 1`류 connectivity query만 수행하며
  `readinessProbeTimeout` 이후 route coroutine이 복귀하고 `jdbcQueryTimeout` 이후
  blocked JDBC statement가 timeout으로 정리됨을 test로 검증한다. R2DBC는
  `suspendTransaction(db = ...)` 안에서 동등한 minimal query만 수행하고 internal
  deadline timeout과 external cancellation을 분리해 검증한다.
- `HealthResponse.details` allowlist는 backend key와 coarse state만 허용한다.
  허용 key는 `jdbc`, `r2dbc`, `exposed`이고 허용 value는 `UP`, `DOWN`,
  `timeout`이다.
- response body에는 driver/dialect/server version, JDBC/R2DBC URL, database name,
  schema/table/column/constraint name, username, SQL text, bind value, SQLState,
  vendor code, pool name, exception message, stack trace, latency histogram을
  포함하지 않는다.

### 보안 요구 사항

- `StatusPagesConfig.bluetape4kExposedErrors()`는 stable error code와 generic
  client-safe message만 반환한다.
- 대상 exception family는 `ExposedSQLException`, `SQLException`, R2DBC exception,
  pool/connectivity failure, timeout failure를 포함한다.
- error classification allowlist는 다음으로 제한한다.
  - `CancellationException`: response body 없이 rethrow, metrics outcome
    `cancelled`.
  - module-internal readiness timeout: HTTP 503,
    `EXPOSED_READINESS_TIMEOUT`, `Exposed readiness probe timed out`, metrics
    outcome `timeout`.
  - SQL/Exposed/R2DBC/pool/connectivity failure: HTTP 503,
    `EXPOSED_DATABASE_UNAVAILABLE`, `Exposed database operation failed`, metrics
    outcome `error`.
  - transaction user block failure: HTTP 500, `EXPOSED_TRANSACTION_FAILED`,
    `Exposed transaction failed`, metrics outcome `error`.
- response body에는 `cause.message`, SQL text, bind value, SQLState, vendor code,
  constraint/table/column/schema/database name, JDBC/R2DBC URL, username, password,
  token, stack trace를 포함하지 않는다.
- status/readiness/metrics path는 raw `Throwable`, `cause.message`,
  `localizedMessage`, SQL text, JDBC/R2DBC URL, SQLState, vendor code,
  constraint/table/column/schema/database name, username, password, token, stack
  trace를 log에 남기지 않는다. logging이 필요하면 stable classification fields
  (`backend`, `operation`, `outcome`)만 사용한다.
- tests는 secret-bearing exception message와 SQL-looking payload를 던져 response
  body가 해당 문자열을 포함하지 않음을 검증한다.

### Metrics 계약

- registry가 없으면 no-op이며 meter를 만들지 않는다.
- registry가 있으면 meter 이름은 다음 allowlist만 사용한다.
  - `bluetape4k.exposed.ktor.transaction`
  - `bluetape4k.exposed.ktor.readiness`
- 허용 tag는 `backend`(`jdbc`/`r2dbc`), `operation`(`transaction`/`readiness`),
  `outcome`(`success`/`error`/`timeout`/`cancelled`)만이다.
- SQL text, raw route path, table/entity/constraint name, exception message,
  database URL, tenant/user id, schema/database name은 tag/name에 사용하지 않는다.
- successful transaction/readiness는 `success`, SQL/driver failure는 `error`,
  timeout은 `timeout`, `CancellationException`은 `cancelled`로 분류한다.
  Cancellation은 재던지며 generic error로 집계하지 않는다.
- `SimpleMeterRegistry` 테스트는 정확한 meter name/tag, registry가 없을 때의
  no-op, 반복 호출 시 meter identity 재사용을 검증한다.

## 대안

### A. 단일 `ktor/exposed` 모듈

장점:

- issue proposal의 artifact 이름과 일치한다.
- JDBC/R2DBC를 한 installer/config에서 명시적으로 opt-in할 수 있다.
- Ktor family와 Spring Boot integration family 사이의 책임 경계가 선명하다.
- BOM 자동 constraint 수집과 publish workflow가 단순하다.

단점:

- JDBC-only caller도 R2DBC API type을 볼 수 있다. dependency scope를
  careful하게 잡아 compile/runtime 의존성을 과하게 끌지 않게 해야 한다.

결정: 채택한다.

### B. `ktor/jdbc`, `ktor/r2dbc` 분할 모듈

장점:

- 의존성 표면이 가장 작다.
- 각 backend의 test matrix를 독립적으로 관리할 수 있다.

단점:

- issue가 요구한 first module보다 PR/문서/CI surface가 커진다.
- `StatusPages`/health/metrics helper 중복 가능성이 높다.
- 첫 Ktor integration의 public API 설계 검토가 분산된다.

결정: 첫 버전에서는 보류한다. 향후 dependency pressure가 실제로 문제일 때
split을 별도 issue로 다룬다.

### C. 자동 탐색되는 `exposed/ktor` 모듈

장점:

- `settings.gradle.kts` 변경이 적다.
- existing `exposed/*` auto-discovery로 artifact 이름을 얻을 수 있다.

단점:

- Ktor integration이 Exposed core helper처럼 보인다.
- 향후 `ktor/*` family가 생길 때 위치가 애매해진다.
- issue의 suggested path와 Spring Boot family layout에서 멀어진다.

결정: 거절한다.

## 테스트 전략

- Unit/contract tests는 Ktor `testApplication {}`를 사용한다.
- Response assertions는 `bluetape4k-ktor-testing`의 helper를 재사용한다.
- JDBC H2 test:
  - supplied `Database`와 explicit blocking dispatcher로 route helper가
    `transaction(db = ...)` 안에서 query를 실행한다.
  - Ktor route example/test는 `Dispatchers.VT` 또는 app-owned dispatcher를 넘겨
    event-loop coroutine에서 blocking JDBC를 직접 실행하지 않는다.
  - route exception이 safe `ApiErrorResponse`로 매핑된다.
  - wrapper 안에서 write 후 exception을 던지고 rollback 또는 unchanged state를
    검증한다.
  - failed request 뒤에도 같은 supplied `Database`가 다음 successful request에서
    재사용 가능해야 한다.
- R2DBC H2 test:
  - supplied `R2dbcDatabase`로 route helper가 `suspendTransaction(db = ...)`
    안에서 query를 실행한다.
  - cancellation/exception test는 `CancellationException`이 status mapper에서
    숨겨지지 않는지 확인한다.
  - wrapper 안에서 write 후 exception을 던지고 rollback 또는 unchanged state를
    검증한다.
  - failed request 뒤에도 같은 supplied `R2dbcDatabase`가 다음 successful
    request에서 재사용 가능해야 한다.
- Health/readiness test:
  - `/healthz/exposed`는 DB를 probe하지 않고 HTTP 200/UP을 반환한다.
  - `/readyz/exposed`는 configured backend만 minimal bounded probe를 수행한다.
  - jdbc-only, r2dbc-only, both-backend, DB-down, timeout, invalid all-null,
    invalid JDBC readiness without `jdbcBlockingDispatcher`, invalid path, invalid
    timeout cases를 검증한다.
  - JDBC readiness는 caller-owned `jdbcBlockingDispatcher`에서 실행되고,
    statement-level `jdbcQueryTimeout`이 적용됨을 검증한다.
  - internal readiness timeout은 `timeout` response로, external request
    cancellation은 rethrown cancellation으로 분리 검증한다.
  - DB-down/misconfigured test response는 URL, dialect, exception message,
    SQLState, schema/table/column/constraint name을 포함하지 않아야 한다.
- Metrics test:
  - `SimpleMeterRegistry` 제공 시 transaction/readiness helper가 allowlisted
    meter name/tag만 만든다.
  - registry가 없으면 no-op이다.
  - `CancellationException`은 재던져지고 generic failure로 집계되지 않는다.
- Test isolation:
  - repo `withTables`/`withDb` helper를 우선 사용한다.
  - helper가 맞지 않는 Ktor route-level test는 unique per-test H2 URL/table name을
    사용한다.
  - shared mutable application state를 두지 않고, 다음 `testApplication`으로 state가
    leak되지 않음을 검증한다.
- Concurrency:
  - shared caller-owned `Database`/`R2dbcDatabase`를 사용하는 concurrent route
    transaction smoke를 추가한다.
  - 가능하면 `MultithreadingTester`, `SuspendedJobTester`, or
    `StructuredTaskScopeTester`를 사용한다. Ktor `testApplication` 구조상 맞지 않으면
    lesson에 rationale을 남기고 coroutine/job based concurrency smoke로 대체한다.

## 문서/릴리스 영향

- `README.md`, `README.ko.md`: Ktor integration module row, dependency snippet,
  explicit app-owned database example, JDBC blocking caution, R2DBC suspend
  example.
- StatusPages docs/examples:
  - core status pages와 Exposed status pages를 함께 쓸 때는
    `installBluetape4kKtorCore(Bluetape4kKtorCoreConfig(installStatusPages = false))`
    후 `install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }`
    로 조합한다.
  - `installBluetape4kExposedKtor(... installStatusPages = true)`는 standalone 또는
    caller가 core status pages를 끈 경우에만 사용한다.
- `AGENTS.md`: layout/module naming table에 `ktor/exposed` 추가.
- `.github/workflows/ci.yml`, `nightly-tests.yml`: Ktor module/example path
  filters, compile/test/kover artifact wiring.
  - `ci.yml`: `changes.outputs.ktor`, `dorny/paths-filter` `ktor` entry,
    `ktor/exposed/**`, Ktor example path, `settings.gradle.kts`, `build.gradle.kts`,
    `gradle/**`, `buildSrc/**`, workflow file paths.
  - `ci.yml`: `test-ktor-exposed` job, Kover XML artifact name,
    `coverage-report.needs`, `ci-status.needs`.
  - `nightly-tests.yml`: smoke/full placement for Ktor integration, coverage artifact,
    `nightly-status.needs`.
  - examples workflow or existing example job command must include the new runnable
    Ktor Exposed example.
- `settings.gradle.kts`: `includeMappedModule("ktor/exposed",
  "bluetape4k-exposed-ktor")`.
- dependency aliases:
  - source-of-truth는 shared `bt4k` catalog이다. `settings.gradle.kts`는
    `bluetape4k-dependencies` catalog를 `bt4k`로 로드하고, 이 catalog에는
    `ktor = "3.5.0"`, `ktor-bom`, `bluetape4k-ktor-core`,
    `bluetape4k-ktor-testing` aliases가 있다.
  - plan 단계에서 `bt4k` alias를 module build에서 사용할 수 있음을 Gradle proof로
    확인한다.
  - public signature에 노출되는 `Database`, `R2dbcDatabase`, `Transaction`,
    `R2dbcTransaction`, `MeterRegistry`, Ktor server/core, and
    `bluetape4k-ktor-core` type은 `api`-visible dependency 또는 wrapper type으로
    처리한다. Public signature에 노출되지 않는 testing/driver/H2 의존성은
    `testImplementation`/`testRuntimeOnly`로 제한한다.
  - direct unpinned version literal은 금지한다.
- publish/BOM:
  - `bluetape4k-exposed-ktor`는 published module이므로 BOM constraint에
    포함되어야 한다.
  - same PR에서 sibling `bluetape4k-dependencies`에
    `bluetape4k-exposed-ktor` alias를 추가하거나, release 전에 반드시 닫을 linked
    follow-up issue를 생성한다. PR DoD에는 어느 경로를 택했는지와 issue/commit
    evidence를 남긴다.
  - generated BOM POM에 `bluetape4k-exposed-ktor` constraint가 들어가고, examples는
    BOM/NMCP aggregation에서 제외됨을 검증한다.
  - `publishToMavenLocal` 또는 equivalent publish metadata check를 수행한다.
- README/README.ko runbook:
  - plugin flags로 status pages/readiness를 끄는 방법.
  - rollback은 dependency, `installBluetape4kExposedKtor` call,
    `bluetape4kExposedErrors()`, `bluetape4kExposedHealthRoutes(...)`,
    `exposedJdbcTransaction(...)`, `exposedR2dbcTransaction(...)` 사용 지점을
    제거하거나 raw Exposed `transaction` / `suspendTransaction`으로 되돌리는
    방식이라는 점.
  - `/readyz/exposed` DOWN/timeout triage 방법.
    - `DOWN`은 configured backend connectivity failure, `timeout`은
      module-internal readiness deadline으로 구분한다.
    - details key는 configured backend만 포함하며 `jdbc`, `r2dbc`, `exposed`만
      허용한다.
    - JDBC는 dispatcher saturation과 `jdbcQueryTimeout`을, R2DBC는 connection
      factory/connectivity를 우선 확인한다.
    - route timeout과 `jdbcQueryTimeout`의 차이를 설명하고 response/log가 secret
      detail을 노출하지 않음을 명시한다.
  - caller-owned resource 목록과 이 module이 하지 않는 일(pool creation,
    repository scanning, auth, global registries, schema migration, DDL
    initialization, application config binding).
  - examples/snippets는 local H2 또는 placeholder env vars만 사용하고 실제
    username/password/token/hostname을 넣지 않는다.
- Source-backed docs:
  - implementation은 `bluetape4k-ktor-core/testing` import와 package name을 실제
    source/compile로 확인하고 README/KDoc examples를 맞춘다.

## 위험

1. JDBC blocking transaction이 Ktor event loop를 막을 수 있다.
   - JDBC helper는 `suspend` + explicit caller-supplied `CoroutineDispatcher`로
     제한한다. KDoc/README/example/test는 dispatcher/virtual-thread guard를
     명시하고, helper는 hidden dispatcher를 만들지 않는다.
2. R2DBC cancellation이 status mapper나 metrics wrapper에서 삼켜질 수 있다.
   - `CancellationException` rethrow test를 추가한다.
3. Ktor core dependency를 duplicate하거나 generic helper를 이 repo로 가져올 수
   있다.
   - `bluetape4k-ktor-core/testing`을 재사용하고 generic API 추가는 non-goal로
     둔다.
4. 새 module wiring 누락으로 publish/CI/BOM이 drift될 수 있다.
   - `./gradlew projects`, affected module build/test, workflow validation,
     BOM/README grep checks를 DoD에 포함한다.
5. Health payload shape이 기존 `HealthResponse`와 맞지 않을 수 있다.
   - core `HealthResponse`를 재사용하고 details allowlist를 고정한다. future richer
     payload가 필요하면 별도 issue에서 opt-in DTO를 추가한다.
6. Workflow/BOM/catalog 누락으로 release 직전에 drift가 발견될 수 있다.
   - CI/Nightly needs, Kover artifacts, generated BOM POM, Maven local metadata,
     shared catalog alias or linked release-blocking issue를 DoD에 넣는다.

## 인수 기준

- [ ] `ktor/exposed` physical path와 `:bluetape4k-exposed-ktor` project/artifact가
      문서화되고 등록된다.
- [ ] Generic Ktor helpers는 `bluetape4k-projects`에 남고, 이 module은
      Exposed-specific helpers만 제공한다.
- [ ] Caller-owned JDBC `Database`와 R2DBC `R2dbcDatabase`를 받는 explicit
      installer/config API가 있다.
- [ ] `installBluetape4kExposedKtor`는 Ktor core를 자동 설치하지 않고, core DTO와
      helper만 `api` dependency로 재사용한다.
- [ ] StatusPages는 default auto-install이 꺼져 있고, core/exposed mapping을 한
      `install(StatusPages)` block에서 조합하는 README/KDoc/test path가 있다.
- [ ] JDBC/R2DBC transaction wrappers가 있고 cancellation/exception behavior가
      검증된다.
- [ ] JDBC wrapper는 `suspend` + explicit caller-supplied blocking dispatcher를
      요구하며 route examples/tests가 blocking JDBC를 event-loop coroutine에서
      직접 실행하지 않는다.
- [ ] `StatusPages` error mapping이 `ApiErrorResponse`/`respondApiError`를
      재사용한다.
- [ ] Exposed/SQL/R2DBC/pool/timeout errors는 stable code와 generic message만
      반환하며 SQL text, URL, username, schema/table/column/constraint, SQLState,
      vendor code, exception message, stack trace를 노출하지 않는다.
- [ ] Health/readiness helpers가 configured Exposed backend connectivity를
      확인한다.
- [ ] `/healthz/exposed`는 static liveness이고 `/readyz/exposed`는 configured
      backend만 bounded minimal probe하며 allowlisted `HealthResponse.details`만
      반환한다.
- [ ] `installHealthRoutes = true`일 때 all-null backend config, non-absolute path,
      non-positive timeout은 fail fast 한다.
- [ ] JDBC readiness는 `jdbcBlockingDispatcher`와 statement-level `jdbcQueryTimeout`을
      요구/검증하며 hidden dispatcher/executor를 만들지 않는다.
- [ ] Metrics는 allowlisted meter names/tags만 사용하고 registry 없음은 no-op이다.
- [ ] Ktor `testApplication` + H2/JDBC/R2DBC fixture 기반 focused tests가 있다.
- [ ] Rollback/reuse/concurrency/isolation tests가 JDBC/R2DBC helper 모두에 있다.
- [ ] Runnable example이 `examples/` 아래에 하나 이상 추가된다.
- [ ] README/README.ko.md, module layout docs, CI/Nightly path filters, BOM/publish
      wiring이 업데이트된다.
- [ ] Shared `bt4k` catalog alias 사용/추가 경로가 검증되고 direct unpinned version
      literal이 없다.
- [ ] `bluetape4k-exposed-ktor` BOM/publish metadata가 검증되며 shared
      `bluetape4k-dependencies` alias는 same PR 또는 release-blocking linked
      follow-up issue로 처리된다.

## DoD

- [ ] `./gradlew -q projects --no-configuration-cache --no-daemon`
- [ ] `./gradlew :bluetape4k-exposed-ktor:compileKotlin :bluetape4k-exposed-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
- [ ] `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:cleanTest :bluetape4k-exposed-ktor:test --no-parallel --no-build-cache --no-configuration-cache --no-daemon`
- [ ] example module compile/test command
- [ ] `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- [ ] CI/Nightly `needs`/artifacts/path filters include Ktor module and example
- [ ] generated BOM POM or Maven local metadata contains `bluetape4k-exposed-ktor`
- [ ] dependency insight/runtime classpath checks for Ktor, Exposed, Micrometer,
      JDBC/R2DBC scopes
- [ ] README/KDoc names grep-match actual source
- [ ] README/README.ko runbook and examples avoid real secrets/connection strings
- [ ] `git diff --check`
- [ ] No JMH/benchmark required for first integration; lightweight regression checks
      prove no per-call registry, hidden pool/dispatcher, global plugin install, or
      unbounded health details
- [ ] Step 2-R, Step 3-R, Step 6-R review gates converge with P0/P1 = 0
- [ ] Lessons file committed before PR
