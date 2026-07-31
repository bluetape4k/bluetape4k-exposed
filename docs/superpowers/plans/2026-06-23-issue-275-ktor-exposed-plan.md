# Ktor Exposed 통합 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: 이 계획을 작업별로 구현할 때 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용한다. 진행 상태는 checkbox(`- [ ]`) 문법으로 추적한다.

**목표:** 호출자가 소유하는 JDBC/R2DBC database, 안전한 StatusPages mapping, health/readiness route, transaction 헬퍼, metrics, 테스트, 문서, CI/Nightly 연결, release metadata를 포함하는 공개 `:bluetape4k-exposed-ktor` 모듈을 추가한다.

**아키텍처:** 단일 `ktor/exposed` 통합 모듈이 기존 `bluetape4k-ktor-core` API와 이 저장소의 JDBC/R2DBC 모듈에 의존한다. Exposed 전용 Ktor 헬퍼만 제공하며, pool, dispatcher, registry, global plugin, schema migration, repository scanning, Spring 방식 auto-configuration은 생성하거나 수행하지 않는다.

**기술 스택:** Kotlin, Gradle Kotlin DSL, JetBrains Exposed 1.3.0, 공유 `bt4k` catalog/BOM을 통한 Ktor 3.5.0, 선택적 Micrometer metrics, JUnit 5, Ktor `testApplication`, H2 JDBC/R2DBC, Kover, GitHub Actions.

---

## 파일 구조

새로 생성:

- `ktor/exposed/build.gradle.kts`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorConfig.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtor.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPages.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactions.kt`
- `ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetrics.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTestFixtures.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/Bluetape4kExposedKtorTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorTransactionsTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorStatusPagesTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutesTest.kt`
- `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorMetricsTest.kt`
- `examples/ktor-exposed-demo/build.gradle.kts`
- `examples/ktor-exposed-demo/src/main/kotlin/io/bluetape4k/exposed/examples/ktor/KtorExposedDemoApplication.kt`
- `examples/ktor-exposed-demo/src/test/kotlin/io/bluetape4k/exposed/examples/ktor/KtorExposedDemoApplicationTest.kt`
- `docs/superpowers/lessons/2026-06-23-issue-275-ktor-exposed.md`

수정:

- `settings.gradle.kts`
- `README.md`
- `README.ko.md`
- `AGENTS.md`
- `exposed/bom/README.md`
- `exposed/bom/README.ko.md`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly-tests.yml`

## 작업

- [ ] **Task 0 - 계획 기준선을 커밋한다(소규모, Kotlin 없음).**
  - 파일: 이 spec/plan과 `.omx/artifacts/step-2r-spec-review-issue-275-ktor-exposed.md`
  - 명령:
    - `git diff --check`
    - `git status --short`
  - 예상 결과: 구현을 시작하기 전에 계획/검토 artifact만 stage하고 Lore commit으로 커밋한다. `.omx/artifacts/**`가 ignore 대상이면 검토 artifact에 `git add -f`를 사용하고 `git status --short`에 나타나는지 확인한다.

- [ ] **Task 1 - 구현 스킬을 로드하고 소스 버전을 확인한다(소규모, 코드 수정 없음).**
  - 코드 수정 전 필수 스킬: `$bluetape4k-code-patterns`, `$ecc-kotlin-patterns`, `$ecc-kotlin-exposed`, `$ecc-kotlin-testing`, `$kotlin-coroutines-skill`
  - Worktree Gradle 규칙: worktree가 조용히 원격 catalog로 fallback하지 않도록 catalog에 민감한 Gradle 명령은 `-Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml`과 함께 실행한다.
  - 명령:
    - `test -f ../../../bluetape4k-dependencies/gradle/libs.versions.toml`
    - `rg -n '^exposed =|^ktor =|ktor-bom|bluetape4k-ktor-(core|testing)' gradle/libs.versions.toml ../../../bluetape4k-dependencies/gradle/libs.versions.toml`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
  - 예상 결과: 공유 `bt4k` catalog에서 Exposed `1.3.0`, Ktor `3.5.0`, `ktor-bom`, `bluetape4k-ktor-core`, `bluetape4k-ktor-testing`을 확인할 수 있고, 현재 project 목록에는 아직 Ktor 모듈이 없다.

- [ ] **Task 2 - 새 모듈과 dependency 영역을 등록한다(중간 규모, Gradle).**
  - 파일: `settings.gradle.kts`, `ktor/exposed/build.gradle.kts`
  - `includeMappedModule("ktor/exposed", "bluetape4k-exposed-ktor")`를 추가한다.
  - Build script 계약:
    - `implementation(platform(bt4k.ktor.bom))` 또는 Gradle로 확인한 기존 공유 catalog Ktor BOM accessor를 사용한다.
    - `ApiErrorResponse`, `HealthResponse`, `respondApiError`, path validation 방식, Ktor 공개 type을 위해 `api(bt4k.bluetape4k.ktor.core)`를 사용한다.
    - 공개 signature가 `Database`, `R2dbcDatabase`, `Transaction`, `R2dbcTransaction`을 노출하므로 `api(project(":bluetape4k-exposed-jdbc"))`와 `api(project(":bluetape4k-exposed-r2dbc"))`를 사용한다.
    - Micrometer는 공개 signature 필요에 따라 `api` 또는 `compileOnly`를 사용한다. 공개 config에 `MeterRegistry`가 있으면 `api`가 필요하다.
    - `testImplementation(bt4k.bluetape4k.ktor.testing)`, H2 JDBC/R2DBC, JUnit 5, coroutine test, 저장소 기본 test 헬퍼를 사용한다.
    - Ktor, Exposed, Micrometer, H2의 버전을 직접 쓰지 않는다.
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencies --configuration compileClasspath --no-configuration-cache --no-daemon`
  - 예상 결과: Gradle project에 `:bluetape4k-exposed-ktor`가 나타나고 catalog/BOM으로 관리하는 dependency를 통해 compile classpath가 resolve된다.

- [ ] **Task 3 - config와 installer shell을 구현한다(중간 규모, Kotlin, `$bluetape4k-code-patterns` 사용).**
  - 파일:
    - `Bluetape4kExposedKtorConfig.kt`
    - `Bluetape4kExposedKtor.kt`
  - nullable `jdbcDatabase`, nullable `jdbcBlockingDispatcher: CoroutineDispatcher?`, nullable `r2dbcDatabase`, `installStatusPages = false`, `installHealthRoutes = false`, `healthPath = "/healthz/exposed"`, `readinessPath = "/readyz/exposed"`, `readinessProbeTimeout = 1.seconds`, `jdbcQueryTimeout = 1.seconds`, nullable `MeterRegistry`를 포함하는 `Bluetape4kExposedKtorConfig`를 구현한다.
  - `Application.installBluetape4kExposedKtor(config)`를 명시적인 Exposed 전용 installer로 구현한다.
    - `installBluetape4kKtorCore()`를 호출하지 않는다.
    - content negotiation이나 일반 health route를 설치하지 않는다.
    - `installStatusPages = true`이면 `StatusPages`가 없을 때만 Exposed 전용 `StatusPages`를 설치한다.
    - `installStatusPages = true`이면 호출자가 Ktor JSON/content negotiation 또는 자체 response serialization 설정을 설치해야 한다. 이 모듈은 content negotiation을 설치하지 않는다.
    - `installStatusPages = true`인데 `StatusPages`가 이미 설치되어 있으면 호출자가 하나의 `install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }` block을 사용하도록 안내하는 명확한 메시지로 즉시 실패한다.
    - `installHealthRoutes = true`이면 Exposed 전용 health/readiness route를 등록한다.
    - 기본 `installBluetape4kExposedKtor()` 호출은 아무 작업도 하지 않을 수 있으며 실패해서는 안 된다.
  - 검증:
    - route를 설치할 때 `readinessProbeTimeout`과 `jdbcQueryTimeout`은 양수여야 한다.
    - backend가 모두 null인 config는 readiness route를 요청한 경우에만 실패한다.
    - JDBC readiness에는 `jdbcBlockingDispatcher`가 필요하다.
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - 예상 결과: installer가 compile되고 lifecycle side effect가 없다.

- [ ] **Task 4 - transaction 헬퍼를 구현한다(중간 규모, Kotlin/coroutines, `$bluetape4k-code-patterns` 사용).**
  - 파일: `ExposedKtorTransactions.kt`, `ExposedKtorMetrics.kt`
  - 구현:
    - `suspend fun <T> ApplicationCall.exposedJdbcTransaction(db: Database, blockingDispatcher: CoroutineDispatcher, block: Transaction.() -> T): T`
    - `suspend fun <T> ApplicationCall.exposedR2dbcTransaction(db: R2dbcDatabase, block: suspend R2dbcTransaction.() -> T): T`
  - JDBC 헬퍼는 숨겨진 dispatcher/executor 없이 호출자가 제공한 `CoroutineDispatcher` 안에서 `transaction(db = db)`를 실행한다.
  - JDBC에 `CoroutineContext` overload를 노출하지 않는다. `EmptyCoroutineContext`를 허용해서는 안 된다.
  - R2DBC 헬퍼는 `suspendTransaction(db = db)`를 실행한다.
  - 두 헬퍼 모두 `CancellationException`을 보존한다.
  - Metrics wrapper는 registry가 제공된 경우에만 allowlist에 포함된 meter name/tag를 기록한다. registry가 없으면 아무 작업도 하지 않고 meter도 만들지 않는다.
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - 예상 결과: 헬퍼가 compile되고 global state를 노출하지 않으며 cancellation을 보존하는 제어 흐름을 지원한다.

- [ ] **Task 5 - 안전한 StatusPages mapping을 구현한다(중간 규모, Kotlin/security, `$bluetape4k-code-patterns` 사용).**
  - 파일: `ExposedKtorStatusPages.kt`
  - `fun StatusPagesConfig.bluetape4kExposedErrors()`를 구현한다.
  - Exposed/SQL/R2DBC/pool/connectivity/timeout 실패를 `respondApiError`를 통해 `ApiErrorResponse`로 mapping한다.
  - 안정적인 code와 일반적인 메시지만 사용한다.
  - `CancellationException`은 다시 던진다.
  - 다음 allowlist table을 사용한다.
    - `CancellationException`: 광범위한 catch 전에 다시 던지고 response body를 만들지 않으며 metrics outcome은 `cancelled`이다.
    - 모듈 내부 readiness timeout: HTTP 503, error `EXPOSED_READINESS_TIMEOUT`, message `Exposed readiness probe timed out`, metrics outcome `timeout`
    - SQL/Exposed/R2DBC/pool/connectivity failure: HTTP 503, error `EXPOSED_DATABASE_UNAVAILABLE`, message `Exposed database operation failed`, metrics outcome `error`
    - 사용자 block에서 발생한 transaction failure: HTTP 500, error `EXPOSED_TRANSACTION_FAILED`, message `Exposed transaction failed`, metrics outcome `error`
  - Redaction denylist: `cause.message`, SQL text, bind value, SQLState, vendor code, constraint/table/column/schema/database name, URL, username, password, token, stack trace
  - status/readiness/metrics 경로에서는 원시 `Throwable`, `cause.message`, `localizedMessage`, SQL text, JDBC/R2DBC URL, SQLState, vendor code, constraint/table/column/schema/database name, username, password, token, stack trace를 log하지 않는다. logging을 추가한다면 `backend`, `operation`, `outcome` 같은 안정적인 분류 필드만 기록한다.
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - 예상 결과: status mapping이 compile되고 secret을 포함한 출력 경로가 없다.

- [ ] **Task 6 - health/readiness route를 구현한다(중간 규모, Kotlin/coroutines, `$bluetape4k-code-patterns` 사용).**
  - 파일: `ExposedKtorHealthRoutes.kt`
  - `Route.bluetape4kExposedHealthRoutes(...)`를 구현한다.
  - `/healthz/exposed`: DB probe 없는 정적 liveness, HTTP 200, `HealthResponse.up(details = mapOf("exposed" to "UP"))`
  - `/readyz/exposed`: 설정한 backend만 probe하고 설정하지 않은 backend는 details에서 생략한다. 설정한 모든 probe가 UP이면 200, 그렇지 않으면 503을 반환한다.
  - JDBC readiness:
    - `jdbcBlockingDispatcher`가 필요하다.
    - 호출자가 제공한 dispatcher에서 최소 `SELECT 1`을 실행한다.
    - statement 수준 `jdbcQueryTimeout`을 적용한다.
    - 내부 readiness timeout과 외부 cancellation을 구분한다.
  - R2DBC readiness:
    - `suspendTransaction(db = ...)`과 최소 query를 사용한다.
    - 내부 readiness timeout과 외부 cancellation을 구분한다.
  - Health details allowlist: key는 `exposed`, `jdbc`, `r2dbc`; value는 `UP`, `DOWN`, `timeout`
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin --no-configuration-cache --no-daemon`
  - 예상 결과: route가 compile되고 `HealthResponse`를 사용하며 개략적인 readiness 상태만 노출한다.

- [ ] **Task 7 - 집중된 Ktor 모듈 테스트를 추가한다(대규모, 테스트, `$bluetape4k-code-patterns` 사용).**
  - `ktor/exposed/src/test/kotlin/io/bluetape4k/exposed/ktor/` 아래의 파일
  - Ktor `testApplication`과 `bluetape4k-ktor-testing` response 헬퍼를 사용한다.
  - JDBC 테스트:
    - 제공된 `Database`와 명시적인 `blockingDispatcher`를 사용하는 transaction route 성공
    - 공개 API를 통해 `EmptyCoroutineContext`/일반 `CoroutineContext`를 사용할 수 없음
    - 호출자가 제공한 이름 있는 dispatcher/thread에서 헬퍼/readiness 실행
    - 예외 후 rollback/상태 불변
    - 실패한 request 후에도 동일한 제공 `Database`를 재사용할 수 있음
    - cancellation이 `ApiErrorResponse`로 변환되지 않음
  - R2DBC 테스트:
    - 제공된 `R2dbcDatabase`를 사용하는 transaction route 성공
    - 예외 후 rollback/상태 불변
    - 실패한 request 후에도 동일한 제공 `R2dbcDatabase`를 재사용할 수 있음
    - cancellation을 다시 던져 보존함
  - StatusPages 테스트:
    - 기본 `installBluetape4kExposedKtor()`가 StatusPages를 설치하지 않고 `/healthz/exposed`나 `/readyz/exposed`를 추가하지 않음
    - Exposed/SQL/R2DBC/pool/timeout 오류를 예상한 안정적인 code/message로 mapping함
    - secret을 포함한 예외 메시지와 SQL처럼 보이는 payload가 response body에 나타나지 않음
    - core `StatusPages`가 이미 설치된 상태에서 `installBluetape4kExposedKtor(installStatusPages = true)`를 호출하면 문서화한 composition 안내와 함께 즉시 실패함
    - 독립적인 `installStatusPages = true` 경로는 호출자가 Ktor JSON/content negotiation을 설치한 경우에만 테스트하며, serialization 설정을 호출자가 소유한다고 문서화함
  - Health/readiness 테스트:
    - 정적 health가 DB에 접근하지 않음
    - jdbc-only, r2dbc-only, 두 backend, DB down, 내부 timeout
    - readiness config가 모두 null이면 즉시 실패함
    - `jdbcBlockingDispatcher` 없이 JDBC readiness를 사용하면 즉시 실패함
    - 잘못된 path와 양수가 아닌 timeout은 즉시 실패함
    - JDBC/R2DBC 외부 cancellation은 내부 timeout과 구분되며 `timeout`, 503 readiness, `ApiErrorResponse`를 반환하는 대신 `CancellationException`을 전파함. Ktor `testApplication`에서 이를 신뢰성 있게 표현할 수 없으면 취소된 parent job을 사용하는 직접 헬퍼 테스트를 추가함
    - JDBC statement 수준 timeout 정리는 `queryTimeout`을 기록하는 가짜 JDBC statement/driver, 신뢰할 수 있는 H2 sleep/alias 테스트, 또는 차단된 statement가 `jdbcQueryTimeout` 후 종료됨을 입증하는 다른 제한된 테스트로 결정적으로 입증함. 이 근거는 route 수준 `readinessProbeTimeout`과 별개임
  - `SimpleMeterRegistry`를 사용하는 metrics 테스트:
    - 정확한 meter name/tag
    - registry가 없으면 meter를 만들지 않음
    - 반복 호출이 meter identity를 재사용함
    - cancellation이 `cancelled`를 사용하고 다시 던져짐
  - concurrency/isolation smoke:
    - 테스트마다 고유한 H2 DB 이름을 JDBC/R2DBC 모두에 사용하거나, 모든 테이블 lifecycle을 JDBC `withTables`/`withTablesSuspending` 및 R2DBC `withTables`로 감싸 저장소 fixture 정리를 입증함
    - `MultithreadingTester`, `SuspendedJobTester`, `StructuredTaskScopeTester`를 우선 사용한다. Ktor `testApplication` 때문에 부적합하면 coroutine/job 기반 smoke를 추가하고 근거를 lesson에 문서화함
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:cleanTest :bluetape4k-exposed-ktor:test --no-parallel --no-build-cache --no-configuration-cache --no-daemon`
  - 예상 결과: 모듈 테스트를 순차 실행해 통과하며 rollback, cancellation, redaction, health/readiness, metrics, concurrency, isolation을 다룬다.

- [ ] **Task 8 - 실행 가능한 Ktor 예제를 추가한다(중간 규모, Kotlin/docs, `$bluetape4k-code-patterns` 사용).**
  - `examples/ktor-exposed-demo/` 아래의 파일
  - 다음 특성을 갖는 최소 Ktor application을 추가한다.
    - demo/test에 한해 호출자가 소유하는 로컬 H2 JDBC/R2DBC resource를 생성한다.
    - demo에서 생성한 `DataSource`, `ConnectionFactory`, dispatcher resource를 `try/finally` 또는 Ktor lifecycle hook으로 닫는다.
    - `installBluetape4kKtorCore(Bluetape4kKtorCoreConfig(installStatusPages = false))`를 설치한다.
    - `install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }`로 구성한다.
    - `installBluetape4kExposedKtor(...)` 또는 직접 exposed health/transaction 헬퍼를 설치한다.
    - 호출자가 선택한 context를 사용해 JDBC blocking 격리를 보여 준다.
    - 실제 username/password/token/hostname을 포함하지 않는다.
  - health/readiness와 하나의 transaction route를 실행하는 `testApplication` smoke를 추가한다.
  - example 소유 dispatcher/pool/resource가 누수되지 않음을 입증하는 code-review checklist 또는 smoke assertion을 추가한다.
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:compileKotlin :examples-ktor-exposed-demo:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:cleanTest :examples-ktor-exposed-demo:test --no-build-cache --no-configuration-cache --no-daemon`
  - 예상 결과: 예제가 compile/test되고 Spring 방식 auto-magic 없이 명시적인 구성을 보여 준다.

- [ ] **Task 9 - 문서와 로컬 agent guide를 갱신한다(중간 규모, 문서).**
  - 파일: `README.md`, `README.ko.md`, `AGENTS.md`, `exposed/bom/README.md`, `exposed/bom/README.ko.md`
  - README/README.ko 내용:
    - `bluetape4k-exposed-ktor`의 모듈 행과 dependency snippet
    - 호출자가 소유하는 명시적인 JDBC/R2DBC 설정 예제
    - 호출자가 StatusPages, health route, 직접 transaction 헬퍼를 선택하지 않으면 기본 `installBluetape4kExposedKtor()`가 의도적으로 아무 작업도 하지 않음
    - core status pages를 비활성화하고 두 mapping을 하나의 block에 넣는 StatusPages 구성 예제
    - 독립적인 `installStatusPages = true`는 이 모듈이 일반 Ktor core/content negotiation을 설치하지 않으므로 호출자가 소유하는 Ktor JSON/content negotiation이 필요함
    - JDBC blocking 주의 사항과 R2DBC suspend 예제
    - status/readiness 비활성화, 원시 Exposed `transaction`/`suspendTransaction`으로 rollback, `/readyz/exposed` DOWN/timeout triage, 호출자 소유 resource, 목표가 아닌 항목에 대한 runbook
    - readiness triage에서 `DOWN`과 `timeout`을 구분하고, 설정된 backend key를 설명하며, dispatcher saturation을 언급하고, `jdbcQueryTimeout`과 route timeout을 구분하며, response/log 출력에서 secret이 포함된 세부 정보를 의도적으로 생략한다고 명시함
  - AGENTS 내용:
    - layout 행 `ktor/exposed`
    - module naming 행 `:bluetape4k-exposed-ktor`
    - 집중 test 명령
  - BOM README 내용:
    - 공개 artifact 범주에 Ktor 통합을 추가함
  - 명령:
    - `rg -n "bluetape4k-exposed-ktor|installBluetape4kExposedKtor|bluetape4kExposedErrors|exposedJdbcTransaction|exposedR2dbcTransaction|/readyz/exposed" README.md README.ko.md AGENTS.md exposed/bom/README.md exposed/bom/README.ko.md`
    - `rg -n "password|passwd|token|secret|apikey|api_key|authorization|bearer|jdbc:postgresql://|jdbc:mysql://|r2dbc:postgresql://|r2dbc:mysql://|r2dbc:pool:|\\.env" README.md README.ko.md examples/ktor-exposed-demo`
  - 예상 결과: 문서가 실제 소스 이름과 일치하고 실제 secret/connection-string 예제를 포함하지 않는다. secret scan은 gate다. 모든 검색 결과를 제거하거나 안전한 placeholder임을 lesson에 명시적으로 문서화해야 한다.

- [ ] **Task 10 - CI/Nightly와 coverage를 연결한다(중간 규모, YAML).**
  - 파일: `.github/workflows/ci.yml`, `.github/workflows/nightly-tests.yml`
  - CI 갱신:
    - `changes.outputs.ktor`를 추가한다.
    - `ktor/exposed/**`, `examples/ktor-exposed-demo/**`, `settings.gradle.kts`, root build 파일, `gradle/**`, `buildSrc/**`, workflow 파일에 대한 `dorny/paths-filter` 항목을 추가한다.
    - `:bluetape4k-exposed-ktor`의 compile/test와 Kover XML report를 실행하는 `test-ktor-exposed` job을 추가한다.
    - example은 기존 examples job이 담당한다. `test-ktor-exposed`에서 중복 실행하지 말고 기존 job에 `:examples-ktor-exposed-demo:test`와 Kover를 추가한다.
    - `test-results-ktor-exposed`, `coverage-ktor-exposed` 같은 test/coverage artifact 이름을 추가한다.
    - job을 `coverage-report.needs`와 `ci-status.needs`에 추가한다.
  - Nightly 갱신:
    - Docker 전용 가정 없이 smoke/full 배치에 Ktor 통합과 example을 포함한다.
    - coverage artifact를 upload하고 `coverage-report.needs`와 `nightly-status.needs`에 추가한다.
  - 명령:
    - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
    - `rg -n "ktor|test-ktor-exposed|coverage-ktor-exposed|examples-ktor-exposed-demo" .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - 예상 결과: workflow 문법이 통과하고 status job이 새 Ktor job에 의존한다.

- [ ] **Task 11 - publish/BOM/catalog 준비 상태를 검증한다(중간 규모, Gradle/release).**
  - 파일: Gradle metadata 조정이 필요하지 않으면 소스 수정 없음
  - 검사:
    - `:bluetape4k-exposed-ktor`가 BOM constraint에 포함됨
    - 비공개 모듈인 `examples-ktor-exposed-demo`가 BOM/NMCP aggregation에서 제외됨
    - 생성된 Maven metadata 또는 로컬 BOM POM에 `bluetape4k-exposed-ktor`가 포함됨
    - runtime/compile dependency scope가 공개 signature type을 노출하고 test driver를 test scope에 유지함
    - 직접 고정한 version literal을 추가하지 않음
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-bom:generatePomFileForBluetapeExposedPublication --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:generatePomFileForBluetapeExposedPublication --no-configuration-cache --no-daemon`
    - `rg -n "bluetape4k-exposed-ktor" exposed/bom/build/publications/BluetapeExposed/pom-default.xml`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml dependencies --configuration nmcpAggregation --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencyInsight --dependency ktor-server-core --configuration compileClasspath --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencyInsight --dependency exposed-core --configuration compileClasspath --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:dependencies --configuration runtimeClasspath --no-configuration-cache --no-daemon`
    - `rg -n 'version = "|:[0-9]+\\.[0-9]+' ktor/exposed/build.gradle.kts examples/ktor-exposed-demo/build.gradle.kts`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml dependencies --configuration nmcpAggregation --no-configuration-cache --no-daemon | tee /tmp/issue-275-nmcpAggregation.txt`
    - `rg -n "bluetape4k-exposed-ktor" /tmp/issue-275-nmcpAggregation.txt`
    - `! rg -n "examples-ktor-exposed-demo" /tmp/issue-275-nmcpAggregation.txt`
  - 공유 catalog 작업:
    - 구현 브랜치가 조정된 후속 작업으로 sibling 저장소도 갱신하지 않는다면 `bluetape4k-exposed-ktor` alias를 위한 release-blocking 이슈를 `bluetape4k-dependencies`에 생성하고 연결한다.
    - PR DoD에 이슈 URL 또는 sibling commit을 기록한다.
  - 예상 결과: publish metadata가 정확하고 dependency-catalog release gap에 명시적으로 추적되는 근거가 있다.

- [ ] **Task 12 - 최종 로컬 검증을 수행한다(대규모, 전체 gate).**
  - 명령:
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml -q projects --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:compileKotlin :bluetape4k-exposed-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :bluetape4k-exposed-ktor:cleanTest :bluetape4k-exposed-ktor:test --no-parallel --no-build-cache --no-configuration-cache --no-daemon`
    - `./gradlew -Pbluetape4kDependenciesCatalogPath=$(pwd)/../../../bluetape4k-dependencies/gradle/libs.versions.toml :examples-ktor-exposed-demo:compileKotlin :examples-ktor-exposed-demo:compileTestKotlin :examples-ktor-exposed-demo:cleanTest :examples-ktor-exposed-demo:test --no-build-cache --no-configuration-cache --no-daemon`
    - `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
    - `git diff --check`
  - 추가 정적 검사:
    - `! rg -n "newFixedThreadPool|newSingleThreadContext|Executors\\.new|asCoroutineDispatcher|limitedParallelism|Dispatchers\\.(IO|Default|VT)|shutdownHook|GlobalScope|Metrics\\.globalRegistry|HikariDataSource\\(" ktor/exposed/src/main`
    - `! rg -n "printStackTrace|localizedMessage|cause\\.message|message\\s*\\?:|sqlState|vendorCode|SQLState|constraint|table|column|schema|jdbc:|r2dbc:|log\\.(trace|debug|info|warn|error)\\([^\\n]*(cause|throwable|exception|ex)" ktor/exposed/src/main`
    - `rg -n "bluetape4k-exposed-ktor|coverage-ktor-exposed|test-ktor-exposed" README.md README.ko.md AGENTS.md exposed/bom/README.md exposed/bom/README.ko.md .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
  - 예상 결과: 모든 검증 명령이 통과한다. 실패하면 검토 전에 각각 수정한다.

- [ ] **Task 13 - Step 6-R 검토, lesson, PR, 이슈 종료를 수행한다(중간 규모, workflow).**
  - 검토:
    - 독립적인 성능, 안정성, 보안, 운영자, 개발자/API, 사용자/호출자 관점에서 Step 6-R을 실행한다.
    - 모든 P0/P1 finding을 수정하고 P0/P1 = 0이 될 때까지 영향받은 lane을 재실행한다.
    - `.omx/artifacts/step-6r-review-issue-275-ktor-exposed.md`에 저장한다.
  - Lesson:
    - 설계 변경점, 검증 근거, dependency/catalog 결정, concurrency test 근거를 `docs/superpowers/lessons/2026-06-23-issue-275-ktor-exposed.md`에 작성한다.
  - Commit/PR:
    - Lore protocol에 따라 커밋한다.
    - `feat/issue-275-ktor-integration`을 push한다.
    - `--body-file`로 PR을 생성한다.
    - `gh pr view <number> --json body`로 라이브 PR 본문을 검증한다.
    - PR 본문의 마지막 `##` section은 `## DoD Status`여야 한다.
  - 종료:
    - CI를 기다린다.
    - 검사가 통과하고 workflow policy에서 허용할 때만 merge한다.
    - 로컬 `develop`을 동기화하고 worktree와 브랜치를 제거한 뒤 이슈 #275가 닫혔는지 확인한다.
  - 예상 결과: PR이 merge되고 이슈 #275가 닫히며 root `develop`이 clean하고 동기화되어 있다.

## 중단 조건

- Gradle이 공유 `bt4k` Ktor alias/BOM을 resolve하지 못하고 catalog-safe fallback을 입증할 수 없으면 구현을 중단한다.
- 공개 signature가 `api`로 안전하게 표현할 수 없는 숨겨진 runtime dependency를 요구하면 구현을 중단한다.
- CI가 실패하거나 라이브 PR 본문 검증에서 필수 DoD를 확인할 수 없으면 merge 전에 중단한다.
- 일반적인 compile/test 실패 때문에 중단하지 않는다. 관련 gate를 수정하고 재실행한다.
