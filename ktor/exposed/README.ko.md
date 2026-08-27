# bluetape4k-exposed-ktor

[English](./README.md) | [한국어](./README.ko.md)

bluetape4k Ktor 애플리케이션에서 호출자가 소유한 JetBrains Exposed JDBC/R2DBC
resource를 사용하기 위한 Ktor helper입니다.

## 기능

- `installBluetape4kExposedKtor()`로 health/readiness route를 명시적으로 opt-in 설치합니다.
- `ApplicationCall.exposedJdbcTransaction()`으로 blocking JDBC 작업을 호출자 소유 dispatcher에서 실행합니다.
- `ApplicationCall.exposedR2dbcTransaction()`으로 coroutine-native R2DBC 작업을 실행합니다.
- `StatusPagesConfig.bluetape4kExposedErrors()`로 클라이언트에 안전한 Exposed 오류 응답을 등록합니다.
- 호출자 소유 `Database`, `R2dbcDatabase` 기반 `/healthz/exposed`, `/readyz/exposed` route helper를 제공합니다.

기본 `installBluetape4kExposedKtor()` 호출은 no-op입니다. Status page, health
route, content negotiation, database pool, dispatcher, meter registry, 일반
bluetape4k Ktor core를 설치하지 않습니다.

## 의존성

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

Exposed BOM을 사용할 때:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

새 서비스는 compatibility aggregator 대신 실제로 사용하는 backend별
아티팩트만 선택하세요.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-core")
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor-jdbc")
    // 이 서비스가 사용하면 -r2dbc 및/또는 -cache도 선택합니다.
}
```

`bluetape4k-exposed-ktor`는 compatibility aggregator로 유지됩니다. 2.0
migration window 동안 기존 import는 계속 동작하므로 backend별로 하나씩 옮겨
소비자 classpath를 선택형으로 유지하세요.

## 호출자 소유 Resource

Database resource는 이 모듈 밖에서 만들고 닫습니다. 준비된 `Database`,
`R2dbcDatabase`, JDBC dispatcher를 Ktor에 넘깁니다.

```kotlin
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.util.concurrent.Executors

val jdbcDatabase: Database = Database.connect(dataSource)
val jdbcDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

val r2dbcDatabase: R2dbcDatabase = R2dbcDatabase.connect(
    databaseConfig = R2dbcDatabaseConfig {
        setUrl(applicationConfig.property("database.r2dbc.url").getString())
    }
)
```

JDBC dispatcher는 blocking JDBC 호출을 Ktor event-loop thread에서 분리합니다.
JDBC pool을 닫는 lifecycle에서 dispatcher도 함께 닫으세요. R2DBC에는 blocking
dispatcher가 필요하지 않습니다.

## 설치

Ktor core status page를 비활성화한 뒤, core와 Exposed mapping을 하나의
`StatusPages` block에 조합합니다. Ktor plugin은 한 번만 설치할 수 있습니다.

```kotlin
import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.bluetape4kExposedErrors
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.bluetape4kErrorResponses
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import kotlin.time.Duration.Companion.seconds

fun Application.module() {
    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installStatusPages = false,
            installHealthRoutes = false,
        )
    )

    install(StatusPages) {
        bluetape4kErrorResponses()
        bluetape4kExposedErrors()
    }

    installBluetape4kExposedKtor(
        Bluetape4kExposedKtorConfig(
            jdbcDatabase = jdbcDatabase,
            jdbcBlockingDispatcher = jdbcDispatcher,
            r2dbcDatabase = r2dbcDatabase,
            installHealthRoutes = true,
            installStatusPages = false,
            readinessProbeTimeout = 2.seconds,
        )
    )
}
```

Standalone `installStatusPages = true`는 `StatusPages`가 아직 설치되지 않은
경우에만 사용할 수 있습니다. Exposed 오류 응답은 표준 bluetape4k JSON payload를
사용하므로, 호출자가 content negotiation도 직접 설치해야 합니다.

```kotlin
import io.bluetape4k.exposed.ktor.Bluetape4kExposedKtorConfig
import io.bluetape4k.exposed.ktor.installBluetape4kExposedKtor
import io.bluetape4k.ktor.core.Bluetape4kKtorJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

fun Application.standaloneExposedErrorsOnly() {
    install(ContentNegotiation) {
        json(Bluetape4kKtorJson.defaultJson())
    }

    installBluetape4kExposedKtor(
        Bluetape4kExposedKtorConfig(
            installStatusPages = true,
            installHealthRoutes = false,
        )
    )
}
```

## Route 트랜잭션

JDBC 호출은 blocking입니다. 항상 전용 blocking dispatcher를 넘기세요.

```kotlin
import io.bluetape4k.exposed.ktor.exposedJdbcTransaction
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.selectAll

routing {
    get("/users/{id}") {
        val id = requireNotNull(call.parameters["id"]).toLong()
        val row = call.exposedJdbcTransaction(
            db = jdbcDatabase,
            blockingDispatcher = jdbcDispatcher,
        ) {
            Users.selectAll()
                .where { Users.id eq id }
                .singleOrNull()
        }

        call.respond(mapOf("found" to (row != null)))
    }
}
```

R2DBC 호출은 suspend-native로 유지되며 blocking dispatcher가 필요하지 않습니다.

```kotlin
import io.bluetape4k.exposed.ktor.exposedR2dbcTransaction
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.r2dbc.selectAll

routing {
    get("/users/{id}/r2dbc") {
        val id = requireNotNull(call.parameters["id"]).toLong()
        val row = call.exposedR2dbcTransaction(db = r2dbcDatabase) {
            Users.selectAll()
                .where { Users.id eq id }
                .singleOrNull()
        }

        call.respond(mapOf("found" to (row != null)))
    }
}
```

모듈 계약을 바꾸지 않고 raw Exposed 호출로 rollback할 수 있습니다.

```kotlin
withContext(jdbcDispatcher) {
    transaction(db = jdbcDatabase) {
        // JDBC 작업
    }
}

suspendTransaction(db = r2dbcDatabase) {
    // R2DBC 작업
}
```

## Transaction Timeout Contract

`exposedJdbcTransaction`과 `exposedR2dbcTransaction`은 호출자가 소유한 Exposed
database 설정의 statement timeout을 사용합니다. Database를 만들 때 초 단위
정수로 설정하세요. `0`은 Exposed driver 기본값을 유지하며 statement timeout을
두지 않습니다. `defaultQueryTimeout`은 `0` 또는 양수 초를 허용하지만 Ktor의
`readinessProbeTimeout`과 `jdbcQueryTimeout`은 positive Duration이어야 합니다.

```kotlin
import org.jetbrains.exposed.v1.core.DatabaseConfig

val jdbcDatabase = Database.connect(dataSource, databaseConfig = DatabaseConfig {
    defaultQueryTimeout = 5
})

val r2dbcDatabase = R2dbcDatabase.connect(
    databaseConfig = R2dbcDatabaseConfig {
        setUrl(applicationConfig.property("database.r2dbc.url").getString())
        defaultQueryTimeout = 7
    }
)
```

Transaction receiver는 호출자가 소유한 database default를 상속합니다. receiver의
`queryTimeout` override가 우선하며 해당 transaction에만 적용됩니다. 값은 driver의
초 단위 정수이고, 지원하지 않는 driver는 statement timeout을 무시할 수 있습니다.

`/readyz/exposed`에서 `readinessProbeTimeout`은 coroutine wall-clock 예산입니다.
JDBC readiness는 항상 `jdbcQueryTimeout`을 적용하고 sub-second Duration은 초 단위로
버립니다(최소 1초). 따라서 `DatabaseConfig.defaultQueryTimeout`보다 우선합니다.
R2DBC readiness는 `R2dbcDatabaseConfig.defaultQueryTimeout`을 상속하며 별도의 Ktor
query-timeout 설정을 제공하지 않습니다. Database, pool, dispatcher를 만들고 닫는
주체는 계속 호출자입니다.

## Cache Readiness Contributor

고정된 운영용 component 이름을 사용하세요. 이름은
`[a-z][a-z0-9_-]{0,62}`와 일치해야 하며, 설정 하나에는 서로 다른 contributor를
`1..16`개 넣을 수 있습니다. Tenant, cache key, URL, endpoint, namespace,
credential, secret을 component에 넣으면 안 됩니다. Supplier는 기존 메모리 상태를
읽기만 하는 side-effect-free O(1) 함수여야 합니다.

<!-- example:jdbc-report:start -->
```kotlin
fun jdbcCacheContributor(
    report: () -> CacheHealthReport,
): ExposedKtorCacheContributor =
    ExposedKtorCacheContributor.jdbcRepository("orders", report)
```
<!-- example:jdbc-report:end -->

<!-- example:r2dbc-report:start -->
```kotlin
fun r2dbcCacheContributor(
    report: suspend () -> CacheHealthReport,
): ExposedKtorCacheContributor =
    ExposedKtorCacheContributor.r2dbcRepository("sessions", report)
```
<!-- example:r2dbc-report:end -->

<!-- example:snapshot:start -->
```kotlin
fun snapshotContributor(
    failureBuffer: SnapshotCacheFailureBuffer,
): ExposedKtorCacheContributor =
    ExposedKtorCacheContributor.snapshot("snapshots", failureBuffer)
```
<!-- example:snapshot:end -->

<!-- example:custom-status:start -->
```kotlin
fun customContributor(
    probe: suspend () -> ExposedKtorCacheStatus,
): ExposedKtorCacheContributor =
    ExposedKtorCacheContributor.custom("redis", probe)
```
<!-- example:custom-status:end -->

JDBC report는 일반 메모리 조회입니다. R2DBC와 custom supplier는 suspend 함수이며
blocking 없이 cancellation에 협력해야 합니다. Blocking, cancellation-insensitive,
database, cache, network, file I/O는 지원하지 않습니다. Coroutine timeout은 blocking
thread나 process를 종료할 수 없으므로 이런 supplier는 request deadline 뒤에도 남을 수
있습니다.
Request가 여전히 active인 동안 supplier가 `CancellationException`을 던지면 `DOWN`으로
정제하고 다음 contributor를 계속 실행합니다. Request context 자체가 취소되면 예외를 다시
던지고 readiness 처리를 중단합니다.

## 설치와 보안

Cache-only 설치에는 database가 필요하지 않습니다.

<!-- example:cache-only-installer:start -->
```kotlin
fun Application.installCacheOnlyReadiness(
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    installBluetape4kExposedKtor(
        config = Bluetape4kExposedKtorConfig(installHealthRoutes = true),
        cacheReadiness = cacheReadiness,
    )
}
```
<!-- example:cache-only-installer:end -->

Installer는 애플리케이션 routing tree의 root에 route를 넣습니다. Ingress 또는 network
policy가 probe path를 제한할 때만 다음 형태를 사용하세요.

<!-- example:ingress-root-route:start -->
```kotlin
fun Application.installIngressProtectedReadiness(
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    // Restrict /healthz/exposed and /readyz/exposed with ingress or network policy.
    installBluetape4kExposedKtor(
        config = Bluetape4kExposedKtorConfig(installHealthRoutes = true),
        cacheReadiness = cacheReadiness,
    )
}
```
<!-- example:ingress-root-route:end -->

애플리케이션 인증을 적용하려면 installer route를 끄고 direct overload를 호출자 소유
인증 block 안에 한 번만 설치합니다.

<!-- example:authenticated-direct-route:start -->
```kotlin
fun Application.installAuthenticatedReadiness(
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    installBluetape4kExposedKtor(
        config = Bluetape4kExposedKtorConfig(installHealthRoutes = false),
        cacheReadiness = cacheReadiness,
    )
    routing {
        authenticate("ops") {
            bluetape4kExposedHealthRoutes(
                jdbcDatabase = null,
                jdbcBlockingDispatcher = null,
                r2dbcDatabase = null,
                cacheReadiness = cacheReadiness,
            )
        }
    }
}
```
<!-- example:authenticated-direct-route:end -->

인증되지 않은 두 번째 route를 설치하면 안 됩니다. 인증, 인가, request concurrency,
rate limiting은 호출자가 담당합니다.

`healthPath`와 `readinessPath`는 trailing slash를 제거한 뒤에도 서로 달라야 합니다. 충돌하면 route 등록 전에
거부하므로 probe-free liveness handler가 readiness 실패를 가릴 수 없습니다.

## Readiness 의미와 시간 예산

| Path 또는 상태 | Ktor 결과 |
|---|---|
| `/healthz/exposed` | Probe를 실행하지 않는 liveness입니다. `exposed=UP`을 반환하며 database나 cache supplier를 호출하지 않습니다. |
| `/readyz/exposed` | Traffic readiness입니다. JDBC, R2DBC, cache contributor 설정 순서로 실행합니다. |
| Flush error가 없는 repository `NOT_APPLICABLE`, `IDLE`, `RUNNING` | `cache.<component>=UP` |
| Repository `DRAINING`, `FAILED`, `STOPPED` 또는 flush error | `cache.<component>=DOWN`, 전체 응답 HTTP 503 |
| Snapshot pending, dropped, observer-failure count | 측정값일 뿐이며 이 값만으로 readiness를 실패시키지 않습니다. |

Ktor 응답에는 `OUT_OF_SERVICE` 상태가 없습니다. `DRAINING`과 `STOPPED` repository는
traffic을 받을 준비가 되지 않았으므로 `DOWN`으로 매핑합니다. Spring Actuator는
`DRAINING`과 `STOPPED`에 management 전용 `OUT_OF_SERVICE` 구분을 유지합니다.

응답 detail은 허용된 `jdbc`, `r2dbc`, `cache.<component>`와 `UP`, `DOWN`,
`timeout`만 담습니다. Supplier exception, message, cause, key, SQL, URL,
credential은 반환하지 않습니다.

`R`을 `readinessProbeTimeout`이라고 하겠습니다. Cache contributor는 각자 `R`을
받지 않고 cache phase 하나의 deadline을 공유합니다. 다음 식을 보수적인 계획값으로
사용하세요.

```text
T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead
```

JDBC query timeout은 초 단위로 버린 뒤 최소 1초를 적용합니다.
`J_effective = max(1 second, jdbcQueryTimeout.inWholeSeconds)`입니다. 세 phase를
모두 사용하고 `R=2s`, `jdbcQueryTimeout=1500ms`라면 계획값은
`(2+1)+2+2 = 7s`에 overhead를 더한 값입니다. Driver가 포화됐거나 지원하지 않는
blocking probe를 사용하면 이 식은 보장값이 아닙니다.

```yaml
readinessProbe:
  httpGet:
    path: /readyz/exposed
    port: 8080
  timeoutSeconds: 10
  periodSeconds: 15
  failureThreshold: 3
```

계획값을 올림하고 여유를 더하세요. 한 번 느린 probe 때문에 곧바로 traffic을 빼지
않도록 `periodSeconds > timeoutSeconds`, `failureThreshold >= 3`을 유지합니다.

## Metrics

다음 dotted name은 Micrometer meter ID입니다. 고정된 Prometheus 또는 OpenTelemetry
series 이름이 아닙니다.

| Meter ID | Tag | Base unit / 의미 |
|---|---|---|
| `bluetape4k.exposed.ktor.cache.readiness` | `component`, `kind`, `operation=readiness`, `outcome=success|error|timeout|cancelled` | timer |
| `bluetape4k.exposed.ktor.cache.queue.depth` | `component`, `kind` | `entries` |
| `bluetape4k.exposed.ktor.cache.snapshot.pending` | `component`, `kind` | `events` |
| `bluetape4k.exposed.ktor.cache.snapshot.dropped` | `component`, `kind` | 누적 `events` |
| `bluetape4k.exposed.ktor.cache.snapshot.observer.failures` | `component`, `kind` | 누적 `events` |

Contributor 하나는 gauge 4개와 유한 outcome timer 4개를 등록하므로 최대 meter ID는
`16 * 8 = 128`개입니다. Export된 time-series 수와 suffix는 registry와 distribution
설정에 따라 달라집니다. Query를 쓰기 전에 실제 exporter 결과를 확인하세요. 누락되거나
생략됐거나 `NaN`인 gauge는 0이 아니라 unavailable입니다. Readiness와 timer outcome을
함께 확인해야 합니다. 누적 dropped/observer-failure counter에 `rate`/`increase`를
적용할 때는 process restart/reset도 고려하세요.

Meter identity는 registry lifecycle 동안 유지됩니다. 같은 identity가 있으면 설치는
`reason=identity_collision`으로 거부되고 새로 잡은 meter를 rollback합니다. Registry
하나에는 route 하나를 권장하며, 재설치하려면 새 registry를 사용하세요. 이전 route가
request를 처리할 수 있는 동안 colliding meter를 제거하면 안 됩니다.

설치 중 오류가 나면 현재 시도에서 claim된 meter만 역순으로 best-effort 제거합니다.
모든 제거를 시도하므로 하나의 `remove` 실패가 나머지 cleanup을 중단시키지 않습니다.
제거 실패는 안정적인 설치 오류의 suppressed structured diagnostic으로 보존되며,
`attempted`/`removed`/`notFound`/`failed`/`residual` 수와 meter identity별 실패 원인을
확인할 수 있습니다. Registry에 남은 `residual` meter가 있으면 해당 registry를 오염된
상태로 취급하고, traffic을 회수한 뒤 새 registry에서 재설치하세요. 성공한 rollback은
registry를 비우므로 같은 설정으로 결정적으로 재시도할 수 있습니다.

## Runbook

| 상황 | 조치 |
|---|---|
| Database `DOWN` | 호출자 소유 pool의 연결과 credential, schema 상태, SQL 오류를 확인합니다. 응답은 유한한 `jdbc` / `r2dbc` 상태만 노출합니다. |
| Database `timeout` | Pool 고갈, network latency, 느린 `SELECT 1`, 막힌 JDBC dispatcher thread, `readinessProbeTimeout`, `jdbcQueryTimeout`을 확인합니다. |
| Repository `DOWN` | `workerState`, queue depth, 호출자 소유 repository telemetry를 확인합니다. `DRAINING`은 traffic 회수 중 예상 상태이고, `FAILED`는 worker 장애, `STOPPED`는 종료 상태입니다. Exception message는 Ktor로 노출하지 않습니다. |
| Cache `timeout` | 공유 `R` 예산과 supplier의 cancellation 협력을 확인합니다. Backend I/O와 blocking 작업을 제거하세요. Helper는 이를 종료할 수 없습니다. |
| Snapshot 누적 counter 증가 | 호출자 소유 drain/observer 처리를 확인합니다. Counter는 측정값이며 restart/reset을 고려한 rate 또는 increase query를 사용합니다. |
| Gauge 누락, 생략, `NaN` | 0이 아니라 unavailable로 보고 최신 readiness와 timer outcome을 함께 확인합니다. |
| 잘못된 설정 | Component regex, 중복, 개수, unsafe data를 바로잡습니다. Runtime 값으로 component를 만들지 않습니다. |
| 지원하지 않는 custom probe | Side-effect-free O(1) 메모리 상태 조회로 바꾸고 backend 진단은 호출자 telemetry에 남깁니다. |
| Meter collision | 이전 route의 traffic을 먼저 회수한 뒤 application/registry를 닫거나 새 registry를 사용해 재설치합니다. |
| Exposed status mapping 비활성화 | `installStatusPages = false`를 유지하고 공유 `StatusPages` block에서 `bluetape4kExposedErrors()`를 제거합니다. |
| Route helper rollback | Ktor helper를 호출자 소유 raw Exposed transaction 호출로 교체합니다. |
| 종료 | Traffic을 회수하고 repository drain/close를 시작한 뒤 readiness가 `DRAINING`, 이어서 `STOPPED`가 되는지 확인합니다. 그다음 application을 멈추고 registry와 호출자 소유 pool/dispatcher를 닫습니다. Route probe는 관찰만 하며 아무것도 닫지 않습니다. |

## Non-goals

- 숨겨진 database pool, connection string, migration, schema 생성 없음.
- 자동 `ContentNegotiation` 또는 일반 bluetape4k Ktor core 설치 없음.
- 애플리케이션이 이미 소유한 `StatusPages`에 대한 두 번째 설치 없음.
- 인증, 인가, OpenAPI, tracing, logging 설정 없음.
- Spring Boot 방식 repository scanning 또는 자동 설정 없음.

## 검증

위 예제는 설정과 route 조각입니다. 공개 API 이름과 기본 동작은
`Bluetape4kExposedKtorTest`가 검증합니다. 실행 명령:

```bash
./gradlew :bluetape4k-exposed-ktor:test
```
