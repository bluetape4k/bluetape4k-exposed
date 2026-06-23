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
        val id = call.parameters["id"]!!.toLong()
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
        val id = call.parameters["id"]!!.toLong()
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

## Readiness

`installHealthRoutes = true`일 때 다음 route를 설치합니다.

| Path | 성공 응답 |
|---|---|
| `/healthz/exposed` | `{"status":"UP","details":{"exposed":"UP"}}` |
| `/readyz/exposed` | 설정된 backend가 통과하면 `{"status":"UP","details":{"jdbc":"UP","r2dbc":"UP"}}` |

설정된 backend 중 하나라도 `DOWN` 또는 `timeout`이면 `/readyz/exposed`는 HTTP
503과 `status = "DOWN"`을 반환합니다.

## Runbook

| 상황 | 조치 |
|---|---|
| Exposed status mapping 비활성화 | `installStatusPages = false`를 유지하고 공유 `StatusPages` block에서 `bluetape4kExposedErrors()`를 제거합니다. |
| Exposed readiness route 비활성화 | `installHealthRoutes = false`를 유지하거나 Exposed Ktor config에서 `jdbcDatabase` / `r2dbcDatabase`를 제거합니다. |
| Route helper rollback | `call.exposedJdbcTransaction()`을 `withContext(jdbcDispatcher) { transaction(db = jdbcDatabase) { ... } }`로, `call.exposedR2dbcTransaction()`을 `suspendTransaction(db = r2dbcDatabase) { ... }`로 교체합니다. |
| `/readyz/exposed`가 `DOWN` 반환 | Database 연결, 호출자 소유 pool의 credential, schema 상태, SQL 오류를 확인합니다. 응답은 의도적으로 `jdbc` / `r2dbc` 상태만 노출합니다. |
| `/readyz/exposed`가 `timeout` 반환 | Pool 고갈, network latency, 느린 `SELECT 1`, 막힌 JDBC dispatcher thread, `readinessProbeTimeout` / `jdbcQueryTimeout`을 확인합니다. |
| 애플리케이션 종료 | 호출자 소유 pool, `R2dbcDatabase` resource, dispatcher, metric registry를 애플리케이션 lifecycle에서 닫습니다. |

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
