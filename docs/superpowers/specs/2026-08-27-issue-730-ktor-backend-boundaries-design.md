# Issue #730 Ktor backend-selective artifact 경계 설계

## 상태와 범위

- 대상 이슈: [#730](https://github.com/bluetape4k/bluetape4k-exposed/issues/730)
- 기준 저장소: `bluetape4k/bluetape4k-exposed`
- 기준 ref: `origin/develop` (`c5e9d499d9c1baeb6f92a531345d184c16febc27`)
- 작업 branch: `refactor/issue-730-ktor-boundaries`
- 작업 worktree: `.worktrees/refactor/issue-730-ktor-boundaries`
- 승인 상태: 아키텍처와 공개 계약을 사용자에게 승인받음
- 이 명세의 범위: Ktor 연동 artifact의 backend 선택 경계, 호환 aggregator, 테스트·문서·catalog/CI 전환
- 제외 범위: database/pool/dispatcher/registry 생성·종료, Ktor core 일반 기능의 재설계, PR·merge·release

## 문제

현재 `ktor/exposed` 하나의 artifact가 JDBC, R2DBC, cache, Micrometer와 그에
필요한 Ktor 기능을 모두 API 의존성으로 노출한다. JDBC만 사용하는 호출자도
R2DBC와 cache 경계를 함께 받으며, R2DBC만 사용하는 호출자도 JDBC artifact를
끌어온다. 이 구조는 선택적 소비자 classpath와 책임 경계를 검증하기 어렵게
만든다.

동시에 기존 `bluetape4k-exposed-ktor`를 바로 제거하면 다음 공개 계약이
깨진다.

- `io.bluetape4k.exposed.ktor` 패키지의 installer/config/transaction/route/API
- 통합 `StatusPagesConfig.bluetape4kExposedErrors()` 호출
- 기존 BOM, README/manual 예제와 Ktor demo
- 현재 JVM descriptor를 확인하는 `ExposedKtorAbiCompatibilityTest`

목표는 backend별 artifact를 독립적으로 사용할 수 있게 하면서도 기존
aggregator 소비자의 source와 binary 호환을 유지하는 것이다.

## 현재 근거

다음 파일과 검증 결과를 기준으로 설계했다.

| 근거 | 현재 사실 |
|---|---|
| `ktor/exposed/build.gradle.kts` | 하나의 모듈이 Ktor core, cache, JDBC, R2DBC, coroutine, Micrometer를 `api`로 노출한다. |
| `settings.gradle.kts` | `ktor/exposed`만 `:bluetape4k-exposed-ktor`로 등록한다. |
| `Bluetape4kExposedKtorConfig.kt` | 하나의 config가 nullable JDBC/R2DBC database와 공통 health/status/metrics 옵션을 함께 받는다. |
| `ExposedKtorTransactions.kt` | JDBC와 R2DBC transaction helper가 같은 패키지에 있다. |
| `ExposedKtorHealthRoutes.kt` | JDBC/R2DBC probe와 cache readiness를 하나의 route API가 조합한다. |
| `ExposedKtorStatusPages.kt` | Exposed JDBC, `SQLException`, `R2dbcException`을 한 extension이 매핑한다. |
| `ExposedKtorCacheReadiness.kt` | cache contributor와 report adapter가 Ktor 모듈에 포함되어 있다. |
| `examples/ktor-exposed-demo` | legacy aggregator를 직접 의존한다. |
| `docs/manual/manifest.yaml` | 현재 Ktor manual entry가 하나이며 source/test 경로도 `ktor/exposed` 하나다. |
| baseline | `EXPOSED_TEST_DB=H2 ./gradlew :bluetape4k-exposed-ktor:test ...` 결과 63개 테스트 PASS. |

기존 installer는 resource를 생성하거나 닫지 않는다. database, pool,
dispatcher, repository, `MeterRegistry`, authentication과 shutdown은
호출자가 소유한다. 이 계약은 분할 후에도 변경하지 않는다.

## 선택한 구조

### 모듈과 책임

| 디렉터리 | Gradle module | 소유 책임 | 금지되는 backend 의존성 |
|---|---|---|---|
| `ktor/core` | `:bluetape4k-exposed-ktor-core` | Exposed 비의존 Ktor route/probe 조합, 공통 metrics, 공통 오류 응답과 예외 | `exposed-jdbc`, `exposed-r2dbc`, `bluetape4k-exposed-cache` |
| `ktor/jdbc` | `:bluetape4k-exposed-ktor-jdbc` | JDBC transaction helper, JDBC readiness probe, JDBC SQL/Exposed 오류 매핑 | R2DBC와 cache artifact |
| `ktor/r2dbc` | `:bluetape4k-exposed-ktor-r2dbc` | R2DBC transaction helper, R2DBC readiness probe와 오류 매핑 | JDBC와 cache artifact |
| `ktor/cache` | `:bluetape4k-exposed-ktor-cache` | `ExposedKtorCacheContributor`, cache readiness/metrics adapter | JDBC와 R2DBC Ktor adapter |
| `ktor/exposed` | `:bluetape4k-exposed-ktor` | 기존 통합 config/installer/route/error extension과 호환 forwarding | 선택 모듈을 `api`로 재노출하는 것 외의 새 backend 로직 |

각 선택 모듈은 `bluetape4k-exposed-ktor-core`를 `api`로 사용한다. JDBC와
R2DBC adapter는 필요한 JetBrains Exposed backend만 직접 의존한다. cache
adapter는 `:bluetape4k-exposed-cache`만 직접 의존하며 cache 모듈 자체의
JDBC/R2DBC Ktor artifact 의존성을 만들지 않는다.

### Core probe 계약

core는 concrete backend 구현과 dependency를 알지 못하고, metrics에 필요한
고정 metadata만 표현하는 backend-neutral 계약을 제공한다.

```kotlin
package io.bluetape4k.exposed.ktor.core

enum class ExposedKtorReadinessBackend {
    JDBC,
    R2DBC,
    CACHE,
}

enum class ExposedKtorReadinessOutcome {
    UP,
    DOWN,
    TIMEOUT,
}

interface ExposedKtorReadinessProbe {
    val component: String
    val backend: ExposedKtorReadinessBackend

    suspend fun probe(timeout: Duration): ExposedKtorReadinessOutcome
}

/**
 * Ktor event-loop를 점유하지 않고 cancellation에 협력하는 caller-owned
 * 구현이라는 신뢰 경계를 선언한다. JDBC처럼 caller dispatcher에서 blocking
 * I/O를 수행하는 내장 adapter도 이 marker를 구현할 수 있지만, 반드시
 * `runInterruptible`을 사용하고 caller가 interruptible driver 계약을
 * 보증해야 한다.
 */
interface ExposedKtorCooperativeReadinessProbe : ExposedKtorReadinessProbe

internal sealed interface ProbeAttempt<out T> {
    data class Value<T>(val value: T) : ProbeAttempt<T>
    data object OuterTimeout : ProbeAttempt<Nothing>
}

fun Route.bluetape4kExposedHealthRoutes(
    probes: List<ExposedKtorReadinessProbe>,
    healthPath: String = "/healthz/exposed",
    readinessPath: String = "/readyz/exposed",
    readinessProbeTimeout: Duration = 1.seconds,
    meterRegistry: MeterRegistry? = null,
)
```

core route는 `List<ExposedKtorReadinessProbe>`를 등록 시 검증하고 immutable
`RegisteredProbe(component, backend, delegate)`로 defensive snapshot한다. 이후
response와 metric tag에는 snapshot된 component/backend만 사용하며 delegate의
가변 getter를 다시 읽지 않는다. route는 health/readiness response, 경로 검증,
timeout 경계, 유한 outcome sanitization과 metrics 태그를 책임진다. 등록되는
probe 목록은 비어 있지 않아야 하며 1..16개 범위만 허용한다.
readiness probe는 등록 입력 순서대로 한 번에 하나씩 순차 실행하며, 내부
`async` fan-out·parallel dispatcher·probe 동시 실행은 허용하지 않는다. 따라서
동시에 실행 중인 probe는 항상 최대 1개이고, 앞 probe의 반환/timeout/cancellation
처리가 끝난 뒤에만 다음 probe를 시작한다. 전체 deadline이 소진되면 남은
probe는 호출하지 않고 `TIMEOUT`으로 채운다.
모든 probe는 `ExposedKtorCooperativeReadinessProbe` marker를 구현해야 하며,
marker가 없는 probe는 등록 시 거부한다. Ktor event-loop를 blocking하거나
timeout을 무시하는 marker 구현과 JDBC driver capability는 caller-owned
신뢰 계약이다. marker는 구현자의 계약을 선언할 뿐 임의의 blocking thread를
강제로 중단하지는 않으며, route는 보상
thread/dispatcher/scope를 생성하지 않는다. JDBC 내장 adapter는 예외적으로
caller-provided `blockingDispatcher`에서 `runInterruptible`로 실행하고,
statement timeout과 thread interruption을 지원하는 driver를 caller가
선택해야 한다. library는 connection을 열어 capability를 추정하거나 driver를
자동 감지하지 않으며, 미지원 driver 등록을 runtime에서 보장해 거부하지도
않는다. 그런 driver를 request-bound route에 등록하는 것은 unsupported caller
계약이고 hard wall-clock bound를 주장할 수 없다. library는 취소 후 orphan
transaction·dispatcher 작업이나 보상 worker를 만들지 않는다. caller는 route
authentication, concurrency limit과 rate limit을 소유한다.

`healthPath`와 `readinessPath`는 설치 시 non-blank absolute literal path로
검증한다. control character, `{}`, `*`, query/fragment와 256자를 초과하는
값은 거부하고, trailing slash는 root(`/`)를 제외하고 하나로 정규화한다.
정규화한 두 경로가 같으면 거부하며, path parameter나 wildcard selector를
등록할 수 없다. 이 규칙은 route 설치 회귀 테스트에서 고정한다.

`probe`의 `timeout`은 전체 route monotonic deadline에서 계산한 남은 시간이며,
각 probe마다 원래 timeout으로 재설정하지 않는다. deadline은 absolute
nanosecond를 만들지 않고 elapsed/remaining 비교와 saturating 계산을 사용해
`Duration`/`Long` overflow를 피한다. `readinessProbeTimeout`과 모든 child
timeout은 `isFinite() && isPositive()`를 함께 만족해야 하며 `INFINITE`, zero,
negative 값은 등록/설치 시 즉시 거부한다. 각 호출은 반드시
production에서는 monotonic elapsed clock만 사용하고, 계산 helper는 내부
`ReadinessClock` seam을 주입할 수 있게 해 경계값 테스트가 wall-clock에
의존하지 않도록 한다. finite timeout의 운영 상한은 caller가 route 설치
정책으로 정하며, library는 큰 finite 값도 overflow 없이 처리하되 장시간
점유를 자동으로 보상 worker로 해결하지 않는다.
`withTimeoutOrNull(remaining) { probe.probe(remaining) }`로 감싸
`ProbeAttempt.Value` 또는 `ProbeAttempt.OuterTimeout`으로 변환한다. Kotlin
wrapper가 소유한 timeout identity만 `OuterTimeout`/`TIMEOUT`으로 매핑하며,
wrapper 밖에서 probe가 직접 던진 `TimeoutCancellationException`은 generic
catch 전에 probe cancellation 규칙을 적용한다. caller/request Job이 inactive이면
재전파하고 active이면 `DOWN`으로 정규화한다. 다른 `CancellationException`도
같은 active/inactive 규칙을 따르고, cancellation 계열이 아닌 모든 일반
`Exception`은 active caller에서 message/cause를 버린 `DOWN` outcome과 단일
`error` metric으로 정규화한다. inactive caller
cancellation과 `Error`는 재전파하며 library는 재전파 경로에서 raw throwable을
로그로 남기지 않는다. deadline이 소진되면
아직 실행하지 않은 probe도 `TIMEOUT`으로 채우고 호출하지 않는다. probe가
값을 반환하더라도 반환 시점의 remaining이 0 이하이면 외부 deadline이
우선하여 `TIMEOUT`으로 덮어쓴다. 단, 결과 반환과 caller cancellation이
경쟁하면 inactive caller cancellation을 먼저 확인해 재전파한다. 따라서
response와 metric에는 `UP`, `DOWN`, `TIMEOUT`만 나타난다.

readiness 계측의 단일 소유자는 core route다. core의 meter 이름은
transaction `bluetape4k.exposed.ktor.core.transaction`, readiness
`bluetape4k.exposed.ktor.core.readiness`로 고정하고, 기존 aggregator의
`bluetape4k.exposed.ktor.readiness`와 cache의
`bluetape4k.exposed.ktor.cache.readiness`는 그대로 둔다. child probe adapter는
readiness meter를 만들거나 기록하지 않으며, core는 route 설치 시 고정된
metadata와 재사용 가능한 meter reference를 한 번만 등록한다. 동일 이름·동일
tag/type의 기존 core meter는 재사용할 수 있지만, 이름은 같고 tag key/type이
다른 meter가 이미 있으면 설치를 즉시 실패시킨다. 따라서 legacy와 core를
동시에 설치해도 meter family 충돌이 없고 registry별 collision fixture로
검증한다. probe 호출 하나당 sample은
정확히 하나만 기록하고, 실행되지 않은 probe에 합성한 `TIMEOUT`도 probe별
sample 하나를 기록한다. `UP/DOWN/TIMEOUT`은 각각
`success/error/timeout`으로, caller cancellation 재전파는 `cancelled`로
매핑한다. 예외 재전파 경로에서도 core가 최종 outcome을 한 번만 결정하며,
child와 legacy metric surface에는 core readiness sample을 추가하지 않는다.

JDBC adapter의 query timeout은 `min(jdbcQueryTimeout, remaining)`을 계산한 뒤
`runInterruptible(blockingDispatcher)` 안에서 Exposed/JDBC whole-second API에
`floor(seconds).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE)`로 변환한다.
dispatcher 대기 시간이 remaining에 포함되므로, 실제 JDBC statement를
호출하기 직전에 monotonic elapsed를 다시 계산한다. 그 시점의 remaining이
0 이하이면 statement를 시작하지 않고 wrapper timeout으로 종료하며, 양수인
경우에만 재계산한 `min(jdbcQueryTimeout, remaining)`을 statement timeout으로
적용한다.
sub-second remaining은 coroutine deadline이 우선한다. JDBC driver가 statement
timeout 또는 thread interruption을 무시하는 경우 hard wall-clock 보장은
적용되지 않는 unsupported caller 구성으로 기록한다. `runInterruptible`은
best-effort이며 library는 orphan worker/dispatcher나 무제한 보상 작업을
시작하지 않는다. `jdbcQueryTimeout`도 finite positive인지 검증한다.
component는 등록 시
고유성·최대 16개와 `[a-z][a-z0-9_.-]{0,62}` 형식을 검증하며 tenant, key, URL,
SQL, namespace, secret을 인코딩할 수 없다. component는 deployment-static opaque
label이어야 하며 request/entity/tenant/key namespace에서 파생된 값을 금지한다.
선택 adapter는 다음 정확한 공개 API를 제공한다.

- `io.bluetape4k.exposed.ktor.jdbc.exposedKtorJdbcReadinessProbe(db: Database, blockingDispatcher: CoroutineDispatcher, jdbcQueryTimeout: Duration = 1.seconds, component: String = "jdbc"): ExposedKtorReadinessProbe`
- `io.bluetape4k.exposed.ktor.r2dbc.exposedKtorR2dbcReadinessProbe(db: R2dbcDatabase, component: String = "r2dbc"): ExposedKtorReadinessProbe`
- `io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheContributor`와
  `io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheReadinessConfig`를 받고
  `exposedKtorCacheReadinessProbes(config: ExposedKtorCacheReadinessConfig): List<ExposedKtorReadinessProbe>`를 제공한다.

core의 정확한 FQCN은 `io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessOutcome`,
`ExposedKtorReadinessBackend`, `ExposedKtorReadinessProbe`,
`ExposedKtorCooperativeReadinessProbe` 및
`io.bluetape4k.exposed.ktor.core.ExposedKtorHealthRoutesKt`,
`ExposedKtorCoreErrorResponse`, `ExposedKtorCoreErrorCode`,
`ExposedKtorCoreStatusPagesKt`이다. child와 core가 공유하는 예외의 정확한
owner와 생성자는 `io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException()`
및 `io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessTimeoutException()`이며,
두 클래스 모두 fixed message의 `RuntimeException`이고 raw cause를 받지 않는다.
JDBC/R2DBC/cache
factory는 각각 `ExposedKtorCooperativeReadinessProbe`를 구현한 immutable
adapter를 반환하고 backend enum을 `JDBC`, `R2DBC`, `CACHE`로 고정한다. cache
child의 factory와 기존 contributor companion factory는 다음 signature를
compile fixture에 고정한다.

```kotlin
class ExposedKtorCacheContributor {
    // Actual members inside the companion; no @JvmStatic bridge is added.
    companion object {
        fun jdbcRepository(
            component: String,
            report: () -> CacheHealthReport,
        ): ExposedKtorCacheContributor

        fun r2dbcRepository(
            component: String,
            report: suspend () -> CacheHealthReport,
        ): ExposedKtorCacheContributor

        fun snapshot(
            component: String,
            buffer: SnapshotCacheFailureBuffer,
        ): ExposedKtorCacheContributor

        fun custom(
            component: String,
            probe: suspend () -> ExposedKtorCacheStatus,
        ): ExposedKtorCacheContributor
    }
}

fun exposedKtorCacheReadinessProbes(
    config: ExposedKtorCacheReadinessConfig,
): List<ExposedKtorReadinessProbe>
```

위 factory는 extension function이 아니라 child와 legacy class의 실제
`companion object` 내부 member로 선언한다. 따라서 JVM owner는 각각
`io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheContributor$Companion`과
`io.bluetape4k.exposed.ktor.ExposedKtorCacheContributor$Companion`이고,
`@JvmStatic`을 추가해 static owner를 바꾸지 않는다. child/legacy companion의
method descriptor와 fixed return type은 각 `.api` 및 `javap -p -s` fixture로
고정한다.

child/legacy의 top-level 함수 owner도 source file의 `@file:JvmName`으로
고정한다. JDBC transaction/status 함수는 각각
`io.bluetape4k.exposed.ktor.jdbc.ExposedKtorTransactionsKt`와
`io.bluetape4k.exposed.ktor.jdbc.ExposedKtorStatusPagesKt`, R2DBC는
`io.bluetape4k.exposed.ktor.r2dbc.ExposedKtorTransactionsKt`와
`io.bluetape4k.exposed.ktor.r2dbc.ExposedKtorStatusPagesKt`, cache
readiness는 `io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheReadinessKt`로
고정한다. suspend 함수는 `Continuation`/`Object` descriptor와 `$default`
bridge를, non-suspend 함수는 exact parameter/return descriptor를
`javap -p -s`, `.api`, clean Java/Kotlin consumer fixture로 확인한다.

child cache의 정확한 FQCN은
`io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheContributor`,
`ExposedKtorCacheContributor$Companion`,
`io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheReadinessConfig`,
`io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheStatus`와
`io.bluetape4k.exposed.ktor.cache.ExposedKtorCacheReadinessKt.exposedKtorCacheReadinessProbes`이다.
companion factory의 JVM return descriptor는 child `ExposedKtorCacheContributor`
로 고정한다. legacy aggregator의 동일한 이름은
`io.bluetape4k.exposed.ktor.ExposedKtorCacheContributor`,
`ExposedKtorCacheReadinessConfig`, `ExposedKtorCacheStatus`에 남기며 child 타입을
반환하는 re-export로 대체하지 않는다.

cache contributor의 `report`와 `probe` supplier는 설치 후 route 호출마다
O(1)·non-blocking이어야 하며 JDBC/R2DBC/네트워크 I/O, `transaction`,
`runBlocking`, 임의 dispatcher 전환을 수행하지 않는 caller-owned 계약이다.
blocking consistency check가 필요하면 호출자가 별도 readiness adapter의
dispatcher/timeout 경계에서 수행해야 한다. 이 계약과 event-loop 침범 금지는
blocking supplier 회귀 fixture로 검증한다.

suspend fun <T> ApplicationCall.exposedJdbcTransaction(db: Database,
blockingDispatcher: CoroutineDispatcher, meterRegistry: MeterRegistry? = null,
block: JdbcTransaction.() -> T): T`를, R2DBC child는
`suspend fun <T> ApplicationCall.exposedR2dbcTransaction(db: R2dbcDatabase,
meterRegistry: MeterRegistry? = null, block: suspend R2dbcTransaction.() -> T): T`를
제공한다. child transaction/readiness failure는 위 core package의 정확한
예외 타입을 사용하고 child consumer는 해당 FQCN을 catch할 수 있다. 각 child는
`StatusPagesConfig.bluetape4kExposedJdbcErrors()` 또는
`bluetape4kExposedR2dbcErrors()`를 제공하고 core는
`bluetape4kExposedCoreErrors()`를 제공한다. legacy 통합 route는 기존
JDBC/R2DBC probe와 cache phase의 세부 budget 및 response key를 그대로
유지한다.

### 오류 매핑

core는 cancellation 재전파,
`io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException`,
`io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessTimeoutException`과 path를
포함하지 않는 전용 API error payload만 등록한다. 정확한 core 응답 계약은
다음과 같다.

```kotlin
@Serializable
data class ExposedKtorCoreErrorResponse(
    val error: String,
    val message: String,
    val status: Int,
)

enum class ExposedKtorCoreErrorCode {
    TRANSACTION,
    READINESS_TIMEOUT,
    DATABASE_UNAVAILABLE,
    INTERNAL,
}

suspend fun ApplicationCall.respondExposedKtorCoreError(
    error: ExposedKtorCoreErrorCode,
)
```

core error catalog는 다음 값만 허용하며 status·error·message는 구현과
JSON characterization fixture에서 exact equality로 검증한다.

| code | status | error | message |
|---|---:|---|---|
| `TRANSACTION` | `500` | `EXPOSED_TRANSACTION_FAILED` | `Exposed transaction failed` |
| `READINESS_TIMEOUT` | `503` | `EXPOSED_READINESS_TIMEOUT` | `Exposed readiness probe timed out` |
| `DATABASE_UNAVAILABLE` | `503` | `EXPOSED_DATABASE_UNAVAILABLE` | `Exposed database operation failed` |
| `INTERNAL` | `500` | `EXPOSED_INTERNAL_ERROR` | `Exposed operation failed` |

`respondExposedKtorCoreError`는 호출자가 임의 `HttpStatusCode`를 전달할 수
없게 하고 `ExposedKtorCoreErrorCode`에서 catalog status를 파생한다. 내부
helper가 status를 받더라도 catalog status와 다르면 즉시 실패하며, 예를 들어
`DATABASE_UNAVAILABLE`을 `200 OK`로 응답하는 경로는 허용하지 않는다.
직접 발생한 `ExposedSQLException`, `SQLException`와 `R2dbcException`은
`DATABASE_UNAVAILABLE`로, child transaction wrapper가 그 내부에서 포착한
SQL/Exposed/R2DBC 예외까지 감싼 최종 오류는 wrapper 경계가 우선해
`TRANSACTION`으로, wrapper 밖에서 직접 발생한 SQL/R2DBC 예외는
`DATABASE_UNAVAILABLE`으로, wrapper-owned timeout은 `READINESS_TIMEOUT`으로
매핑한다. catalog 밖의 값은 거부한다. 이 precedence와 status mismatch는
각각 characterization/negative response test로 고정한다.

`respondExposedKtorCoreError`는 `request.path()`와 기존
`io.bluetape4k.ktor.core.respondApiError`를 호출하지 않는다. child/core의
StatusPages extension은 이 DTO만 직렬화하고 JSON에 `path` key를 만들지 않는다.
`error`와 `message`는 위 enum에 매핑된 고정 catalog에서만 선택하며
`cause.message`, SQL, URL, credential을 직접 전달하는 overload는 제공하지
않는다.
child adapter는 예외 message/cause를 `DOWN` 또는 `TIMEOUT` outcome으로만
변환하며 `Error`와 caller cancellation은 재전파한다. JDBC module은
`ExposedSQLException`·`SQLException`, R2DBC module은 `R2dbcException`을
각각 등록한다. 통합 aggregator의 `bluetape4kExposedErrors()`는 세
extension을 한 번에 조합해 기존 호출을 보존한다. 예외 message, cause, SQL,
URL, credential은 response 또는 metric tag에 노출하지 않는다. 새 core error
payload와 metrics에는 request path를 포함하지 않으며, legacy aggregator의
기존 HTTP error payload에서만 path를 호환성 필드로 유지한다. legacy path에도
secret을 넣지 않는 것은 caller 계약으로 명시하고 secret-bearing path 회귀
테스트를 둔다.

timeout 예외 판정은 `TIMEOUT` 우선순위가 고정된 별도 contract test로 검증한다.
외부 wrapper timeout, probe가 직접 던진 `TimeoutCancellationException`, active
probe cancellation, inactive caller cancellation을 각각 구분하며, wrapper
timeout을 generic `CancellationException` catch에서 `DOWN`으로 바꾸는 구현은
실패로 판정한다. legacy aggregator의 기존 overload는 core route로 단순
forwarding하지 않고 `installLegacyExposedHealthRoutes` 경계를 유지한다. 이
경계는 JDBC/R2DBC/cache의 기존 독립 phase budget과 response key를 보존하고,
새 child/core route만 단일 전체 deadline contract를 사용한다.

### 호환 aggregator

`bluetape4k-exposed-ktor`는 제거하지 않는다. 다음을 유지한다.

- `Bluetape4kExposedKtorConfig`의 생성자와 기본값
- `Application.installBluetape4kExposedKtor` 두 overload의 JVM descriptor
- 통합 `Route.bluetape4kExposedHealthRoutes` overload
- `ApplicationCall.exposedJdbcTransaction`/`exposedR2dbcTransaction`
- `ExposedKtorCacheContributor`와 `ExposedKtorCacheReadinessConfig`의 binary 이름과 생성자/factory
- `StatusPagesConfig.bluetape4kExposedErrors()`와 공통 예외 binary 이름

aggregator에는 위 legacy FQCN의 실제 JVM class와 생성자/`$default` bridge를
계속 둔다. Kotlin `typealias`, 단순 re-export, child FQCN만 남기는 방식은
호환 전략으로 허용하지 않는다. 구현은 새 module 기능을 호출하는 얇은
forwarding으로 바꾸되 facade class의 descriptor와 old response semantics를
보존한다. combined config/installer와 통합 extension에는
`@Deprecated(level = WARNING)`와 선택 module migration KDoc을 추가하되,
`ERROR` 또는 `HIDDEN`으로 바꾸지 않는다. 제거 시점은 별도 major migration
결정 없이는 정하지 않는다.

aggregator forwarding이 child/core 예외를 밖으로 전달할 때는
`io.bluetape4k.exposed.ktor.ExposedKtorTransactionException` 또는
`io.bluetape4k.exposed.ktor.ExposedKtorReadinessTimeoutException`으로
재래핑해 기존 `catch`와 legacy `StatusPages` mapping의 class identity를
보존한다. child module을 직접 사용하는 소비자는 child 예외를 관찰할 수
있지만 aggregator 소비자에게 child FQCN을 HTTP/log/metric 경계로 누출하지
않는다. 기존 ABI와 관찰 가능한 cause 계약을 위해 legacy transaction wrapper는
기존 `(Throwable)` 생성자와 cause chain을 유지하고, timeout wrapper는 기존
`(String)` 생성자를 유지한다. 이 cause는 compatibility-only이며 logger에는
고정된 legacy error code와 status만 기록한다. 원본 exception message/cause,
SQL, URL, credential과 child FQCN은 response, structured log, metric tag와
새 serialization DTO 어느 곳에도 복사하지 않는다. HTTP redaction,
legacy-cause compatibility 및 captured-log 회귀 테스트를 각각 고정한다.

legacy ABI fixture는 다음 실제 JVM owner와 nested owner를 고정한다.

| legacy surface | JVM owner / 확인 방법 |
|---|---|
| config, constructor/default bridge | `io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig` |
| installer overload/default bridge | `io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorKt` |
| health route overload/default bridge | `io.bluetape4k.exposed.ktor.ExposedKtorHealthRoutesKt` |
| JDBC/R2DBC transaction/default bridge | `io.bluetape4k.exposed.ktor.ExposedKtorTransactionsKt` |
| StatusPages extension | `io.bluetape4k.exposed.ktor.ExposedKtorStatusPagesKt` |
| child/core exceptions | `io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException()`, `ExposedKtorReadinessTimeoutException()` |
| cache contributor/config/factories | `io.bluetape4k.exposed.ktor.ExposedKtorCacheContributor`, `$Companion`, `ExposedKtorCacheReadinessConfig` |
| common exceptions | `io.bluetape4k.exposed.ktor.ExposedKtorTransactionException`, `ExposedKtorReadinessTimeoutException` |

fixture는 `Class.forName`, `javap -p -s`, Kotlin `$default` descriptor와
실제 binary consumer compile/run을 모두 사용한다. legacy aggregator는
`api/bluetape4k-exposed-ktor.api` 전체를 baseline으로 삼아 선택된 owner뿐
아니라 Config의 static field/getter와 Companion, cache config getter,
enum `values/valueOf/entries`, 모든 public method/field descriptor가
추가·삭제·변경되지 않았는지 whole-file 비교한다. child module의 동일 이름
facade만으로 legacy ABI를 대체하지 않는다.

`$default` 검증 범위는 legacy뿐 아니라 모든 신규 public default 인자를
포함한다. 즉 core route의 `healthPath`/`readinessPath`/
`readinessProbeTimeout`/`meterRegistry`, JDBC·R2DBC readiness factory의
`component`/timeout 기본값, child/legacy installer와 status helper의 기본값을
각 owner의 descriptor와 함께 고정한다. suspend 함수는
`Continuation`/`Object` bridge와 `$default`를, non-suspend 함수도 생성되는
`$default` bridge를 `javap`, `.api`, Java/Kotlin consumer fixture에서 모두
확인한다.

## 데이터 흐름

```text
선택 소비자
  -> ktor-core route + (jdbc | r2dbc | cache) probe
  -> shared health/readiness response + metrics

legacy 소비자
  -> ktor aggregator config/installer
  -> jdbc/r2dbc/cache child adapter
  -> 기존 package/JVM descriptor와 response semantics
```

설치 helper는 resource를 만들거나 닫지 않는다. JDBC block은 호출자가 준
blocking dispatcher에서만 실행하고, R2DBC block은 `suspendTransaction`에서
실행한다. route가 이미 설치된 `StatusPages`를 덮어쓰지 않는 기존 검사를
유지한다.

## 실패 모드와 대응

1. **선택 모듈 classpath에 다른 backend가 새어 나옴**  
   각 child module의 compile/runtime variant, published POM와 Gradle module
   metadata를 clean 외부 consumer fixture로 검사하는
   `checkKtor*DependencyBoundary` task를 둔다. forbidden artifact coordinate가
   있으면 build를 실패시킨다.

   모듈별 허용·금지 좌표는 다음 목록을 source와 published metadata 검사에
   동일하게 사용한다.

   | module | allowed coordinate | forbidden coordinate |
   |---|---|---|
   | `ktor-core` | Ktor server/core, Micrometer, Kotlin coroutines, `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm`, `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm`, `io.github.bluetape4k:bluetape4k-ktor-core` | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor` |
   | `ktor-jdbc` | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core` | `io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor` |
   | `ktor-r2dbc` | `io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core` | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor` |
   | `ktor-cache` | `io.github.bluetape4k.exposed:bluetape4k-exposed-cache`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core` | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc`, `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor` |

   열거된 좌표 외에도 `io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-*`
   namespace의 artifact는 각 module의 명시된 allowlist에 포함된 경우에만
   허용한다. 새 backend 또는 sibling Ktor artifact를 목록에 추가하지 않은
   상태로 통과시키지 않으며, source graph·compile/runtime classpath·POM·Gradle
   metadata 검사 모두 이 namespace deny-by-default 정책을 적용한다.

   aggregator만 세 backend 좌표와 `ktor-core`/Ktor adapter 좌표를 `api`로
   재노출할 수 있다. 검사는 source dependency graph, compile/runtime
   classpath, published POM와 Gradle module metadata의 fully-qualified
   `group:name`을 각각 비교하며 누락된 variant도 실패시킨다.

   `ktor/core` module은 `org.jetbrains.kotlin.plugin.serialization` plugin을
   실제로 적용한다(`apply false` 선언만으로는 불충분). core의 direct/compile
   allowlist는 다음 fully-qualified coordinate를 variant별로 고정한다:
   `io.ktor:ktor-server-core-jvm`, `io.ktor:ktor-server-status-pages-jvm`,
   `io.ktor:ktor-server-content-negotiation-jvm`,
   `io.ktor:ktor-serialization-kotlinx-json-jvm`,
   `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm`,
   `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm`,
   `io.micrometer:micrometer-core`, `org.jetbrains.kotlinx:kotlinx-coroutines-core`,
   `io.github.bluetape4k:bluetape4k-ktor-core` 및 해당 BOM이다. 이미
   `bluetape4k-ktor-core`가 제공하는 좌표는 direct dependency로 중복 추가하지
   않되, published POM/Gradle metadata의 transitive edge로 나타나는 위
   allowlist는 허용한다. JVM artifact와 metadata variant를 정규화한 뒤
   direct graph와 transitive closure를 각각 검사하고, allowlist 밖 좌표 또는
   `kotlinx-serialization-*`의 다른 variant가 나타나면 fail-closed 한다.
2. **legacy JVM descriptor 또는 default argument bridge가 사라짐**
   기존 ABI compatibility test를 aggregator 대상으로 유지하고, 두
   installer overload와 route overload의 descriptor 및 Kotlin `$default`
   bridge를 확인한다.
3. **partial readiness에서 timeout/error 정보가 노출됨**  
   core가 등록 시 snapshot한 고유 component와 `UP`/`DOWN`/`TIMEOUT`만
   직렬화하고, child adapter가 backend exception을 공통 sanitized outcome으로
   변환한다. route 전체 monotonic deadline, supplier cancellation/`Error`,
   중복·최대 16개 검증을 고정한다. SQL/cause와 cache key를 response/log
   tag에 넣지 않는다.
4. **old aggregator와 child 구현의 timeout semantics가 달라짐**  
   legacy route는 기존 통합 구현의 phase budget을 회귀 테스트로 고정하고,
   new child route는 전체 deadline/remaining budget과 미실행 probe의
   `TIMEOUT` 결과를 별도 probe contract test로 검증한다.
5. **BOM/manual/example만 일부 전환됨**  
   settings, BOM 자동 constraint, publishable module inventory(기존 38개와
   새 child 4개를 합한 `42/42` 반영), manifest의 release ref/commit pin, EN/KO manual,
   README, Ktor demo, CI path와 test job을 한 변경에서 점검한다. 생성
   manifest는 export task로 다시 만든다. aggregator 유지·child 전환·rollback
   절차를 운영 문서에 함께 둔다.

## 테스트와 수용 기준

### 테스트

- core: probe 순서, 전체 monotonic deadline/remaining budget, 미실행 timeout,
  library-owned timeout identity, `INFINITE`/zero/negative 거부와
  finite/overflow-safe duration. 계산 helper는 `elapsed >= budget`을 먼저
  확인하고 `budget - elapsed`를 saturating 방식으로 clamp하며 absolute
  `Long` deadline과 `Duration.inWholeNanoseconds` 변환을 사용하지 않는다.
  입력 순서 보존과 최대 동시 실행 1을 barrier probe로 검증하고, 내부
  `async` fan-out이 없음을 고정한다. readiness sample이 호출당 정확히 한 번
  기록되는지, route만 meter를 소유하고 child/legacy surface가 중복 계측하지
  않는지 확인한다. 주입한 `ReadinessClock`으로 elapsed 경계를 결정적으로
  재현하고, 큰 finite timeout에서도 overflow가 없는지 stress 검증한다.
  marker 없는
  non-cooperative probe의 등록 거부, wrapper timeout과 probe 직접
  `TimeoutCancellationException` 및 caller cancellation의 구분,
  cancellation/`Error`, 중복·최대 16개/static opaque component snapshot,
  path 없는 core error DTO와 exact fixed error-code/status/message catalog, raw exception/message
  redaction, response sanitization, fixed backend metrics outcome과 cooperative
  wall-clock bound. health/readiness path의 absolute literal·금지 문자·길이·
  trailing slash 정규화·중복 거부도 설치 테스트로 검증한다.
  child consumer가 정확한 core exception FQCN과 no-arg constructor를 compile/
  catch하고, aggregator는 legacy `(Throwable)`/`(String)` constructor와 cause
  호환성을 유지하는지 ABI fixture로 확인한다.
- JDBC child: H2 transaction/readiness/error mapping, `runInterruptible` blocking
  dispatcher와 interruptible-driver caller contract 검증. non-interruptible fake
  driver는 request-bound hard wall-clock 보장 대상이 아닌 unsupported로 기록하고
  orphan worker/dispatcher가 생성되지 않는지 확인한다. dispatcher queue 지연
  barrier 뒤 실제 statement 직전 remaining 재계산과 sub-second timeout을
  고정한다.
- R2DBC child: H2 transaction/readiness/error mapping과 cancellation 검증.
- cache child: contributor snapshot/failed report/metric redaction 검증.
- aggregator: 기존 63개 Ktor 테스트, ABI compatibility, cache readiness,
  legacy 독립 phase budget/response key, driver timeout, README parity를 그대로
  통과. characterization fixture는 JDBC → R2DBC → cache 순서, JDBC query
  timeout과 readiness timeout의 독립성, cache phase의 공유 예산, 기존 response
  key를 고정하고 aggregator가 core whole-deadline route를 호출하지 않는지
  확인한다.
  legacy exception 재래핑은 transaction cause 호환성을 유지하되 child
  FQCN/secret-bearing detail이 HTTP·log·metric·새 DTO에 붙지 않고 captured
  log가 고정 code/status만 포함하는지도 확인한다.
- consumer boundary: JDBC-only, R2DBC-only, cache-only consumer가 각각
  위에 명시된 정확한 public import/signature를 compile하고 금지 artifact가
  published POM, Gradle metadata, compile/runtime classpath에 없는지 확인.
- ABI/publication: 기존 aggregator의 legacy class/FQCN, constructor, route/
  transaction/status descriptor와 Kotlin `$default` bridge를 `Class.forName`,
  `javap`, binary consumer fixture로 확인한다. child transaction helper는
  `suspend fun`과 `Continuation`/`Object` descriptor 및 `$default` bridge를
  고정하고, child companion factory는 `$Companion` owner와 exact return
  descriptor를 고정한다. root publishable module count,
  신규 child `.api` baseline과 `checkProductionAbi`/publication inventory도
  함께 검증한다. root assertion과 CI inventory는 `42/42`가 아니면 fail-closed
  한다.
- metrics: 새 core/child readiness metric은 `backend ∈ {jdbc, r2dbc, cache}`,
  `operation ∈ {transaction, readiness}`, `outcome ∈ {success, error, timeout,
  cancelled}`와 immutable `component`만 사용한다. readiness outcome은
  `UP→success`, `DOWN→error`, `TIMEOUT→timeout`으로 매핑하고 caller
  cancellation만 `cancelled`로 기록한다. backend는 probe의 immutable
  registration metadata에서만 가져오며 arbitrary custom backend와 dynamic
  component를 거부한다. route 설치 시 timer를 한 번 등록하고 request path에서는
  cached reference만 사용하며, 반복 요청·재설치 후 시계열 수가 증가하지 않는지
  확인한다. 기존 aggregator의 JDBC/R2DBC metric은 현재
  `backend/operation/outcome` tag vocabulary를 그대로 유지하고, cache metric은
  현재 `component/kind/operation/outcome` vocabulary를 그대로 유지한다. 어느
  legacy surface에도 다른 surface의 tag를 추가하지 않는다. 두 metric surface를
  하나의 meter로 합치지 않는다.
- core/child error: exact `ExposedKtorCoreErrorCode` catalog table 밖의
  error/message/status와 raw exception detail을 거부하고 JSON에 `path` key가
  없는지 확인한다. SQL/Exposed/R2DBC exception mapping이
  `DATABASE_UNAVAILABLE`로 고정되는지도 확인한다. legacy
  aggregator만 기존 path field를 유지하며 secret-bearing path는 caller 계약상
  금지되고 회귀 fixture에서 명시적으로 차단한다.
- 통합 DB 검증은 H2 후 PostgreSQL, MySQL 순서로 순차 실행한다. Docker 기반
  검증은 healthy Colima를 재시작하지 않고 현재 환경을 사용한다.

### 수용 기준

1. 다섯 Gradle module이 settings에 등록되고 BOM에 자동 constraint로 포함되며,
   publishable inventory와 CI 기대 module 수가 갱신된다.
2. JDBC-only/R2DBC-only/cache-only consumer가 다른 backend Ktor artifact 없이
   필요한 API를 사용한다.
3. legacy aggregator의 실제 legacy class/FQCN, constructor/descriptor/
   `$default` bridge와 응답·caller-owned lifecycle 의미가 유지된다.
4. metrics 이름·backend/operation/outcome 태그와 오류 redaction이 변하지
   않는다.
5. EN/KO manual·README·manifest·example·CI path/job가 새 모듈 경계를
   설명하고 서로 일치한다.
6. core/child/aggregator 테스트와 선택 DB matrix, published dependency boundary,
   ABI/publication inventory 검사가 통과하며 `git diff --check`가 PASS한다.

## 문서와 migration

새 manual entry는 `bluetape4k-exposed-ktor-core`, `...-jdbc`, `...-r2dbc`,
`...-cache`에 대해 EN/KO 한 쌍씩 추가한다. 각 entry는
`docs/manual/manifest.yaml` 규칙대로 release ref와 commit을 고정한다. 기존
Ktor manual은 legacy aggregator compatibility 페이지로 남기고 다음 선택
규칙을 명시한다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-r2dbc")
implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-cache")
```

JDBC-only 예제는 `io.bluetape4k.exposed.ktor.jdbc`만, R2DBC-only 예제는
`io.bluetape4k.exposed.ktor.r2dbc`만, cache-only 예제는
`io.bluetape4k.exposed.ktor.cache`와 core를 import한다. 모든 dependency
예시는 `bluetape4k-dependencies` BOM을 사용하고 individual Bluetape library
version은 쓰지 않는다. Ktor demo는 child module 조합을 주 예제로 사용하며,
별도 binary/compile fixture는 legacy aggregator의 실제 FQCN과 descriptor를
계속 확인한다. release 전 child manual promotion이 불가능한 경우에는
manifest entry를 보류하고 aggregator rollback 절차를 따른다.

## 리뷰 보완 결정

6개 관점 독립 리뷰에서 확인된 P1을 다음과 같이 해소한다.

| 리뷰 항목 | 결정 | 검증 근거 |
|---|---|---|
| 전체 readiness timeout | child route는 단일 monotonic deadline과 probe별 remaining budget을 사용하며 cooperative probe에 대해서만 wall-clock bound를 보장한다. JDBC non-interruptible driver는 unsupported caller 구성으로 기록하고, legacy 통합 route의 기존 phase budget은 보존한다. | cooperative 전체 wall-clock, 미실행 `TIMEOUT`, non-interruptible orphan 부재, legacy budget 회귀 테스트 |
| probe 결과·취소·예외 | 공개 결과는 `ExposedKtorReadinessOutcome` 세 값으로 제한한다. library-owned wrapper timeout은 항상 `TIMEOUT`으로 먼저 판정하고, probe가 직접 던진 `TimeoutCancellationException`과 다른 cancellation은 active request에서만 `DOWN`, inactive caller cancellation과 `Error`는 재전파한다. marker 없는 비협조 probe는 등록 시 거부한다. | adversarial wrapper/direct-timeout/cancellation/error 및 redaction 테스트 |
| component 안전성 | 등록 시 immutable `RegisteredProbe(component, backend, delegate)` snapshot, 고유성, 최대 16개와 정규식 형식을 검증한다. delegate의 가변 getter와 arbitrary backend는 이후 읽지 않거나 거부한다. | duplicate, post-install mutation, backend allowlist, max-count 테스트 |
| legacy ABI | aggregator에 실제 old-package class/facade와 constructor/`$default` bridge를 유지하고 typealias는 사용하지 않는다. | `Class.forName`, `javap`, binary consumer 및 `.api` baseline |
| published boundary | child의 POM·Gradle metadata와 compile/runtime variant를 외부 consumer로 검사하고 publishable inventory/CI 기대 count를 갱신한다. | `checkKtor*DependencyBoundary`, `checkProductionAbi`, inventory 검증 |
| observability | 새 core/child readiness metric은 immutable registration metadata에서 `backend={jdbc,r2dbc,cache}`, `operation={transaction,readiness}`, `outcome={success,error,timeout,cancelled}`, frozen component만 사용한다. 기존 aggregator의 JDBC/R2DBC metric은 `backend/operation/outcome`, cache metric은 `component/kind/operation/outcome` tag vocabulary를 각각 보존하고 두 surface를 합치지 않는다. readiness `UP/DOWN/TIMEOUT`은 각 surface의 고정 vocabulary로 매핑하며 route 설치 시 meter를 1회 등록한다. SQL·URL·credential·key·cause는 response/log/tag에서 제외하고 legacy path는 기존 호환 필드로만 유지한다. | metric cardinality·재사용·재설치·legacy tag parity 및 secret-bearing exception/path capture 테스트 |

## DoD

- [ ] 승인된 모듈 경계와 public probe/error contract가 구현되었다.
- [ ] 선택 module의 compile/runtime dependency boundary가 자동 검사된다.
- [ ] legacy aggregator ABI, source 사용법, timeout/error semantics가 회귀
  테스트로 증명된다.
- [ ] core/JDBC/R2DBC/cache/aggregator와 example의 targeted tests가 PASS한다.
- [ ] H2 및 해당되는 PostgreSQL/MySQL 검증과 `git diff --check`가 PASS한다.
- [ ] BOM, manifest, EN/KO manual, README, example, CI path/job가 parity를
  이룬다.
- [ ] P0/P1 review finding이 없고, 공개 API·KDoc·migration 문서가
  `bluetape4k-kotlin-patterns`와 Korean writer gate를 통과한다.
- [ ] 이 worktree의 변경만 구현하며 PR·merge·release는 수행하지 않는다.

## 작성 게이트 (SPW)

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 이슈 URL, 기준 ref, worktree, 대상 독자(호출자·유지보수자), source ledger와 미지원 범위를 명시했다. |
| SPW-02 | PASS | 문제, 제약, 구조, 계약, 흐름, 실패 모드, 호환성, 테스트, 수용 기준과 DoD를 포함했다. |
| SPW-03 | PASS | Korean technical register와 `artifact`, `aggregator`, `readiness`, `caller-owned` 용어를 문맥별로 일관되게 사용했다. |
| SPW-04 | PASS | 현재 Gradle/source/test/manual 경로와 baseline 63 tests를 기준으로 주장과 migration 범위를 대조했다. |
| SPW-05 | PASS | Markdown을 다시 읽어 표·코드 fence·목록·링크를 확인했으며 미해결 기술 placeholder가 없다. |
