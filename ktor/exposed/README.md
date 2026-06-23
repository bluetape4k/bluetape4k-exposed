# bluetape4k-exposed-ktor

[English](./README.md) | [한국어](./README.ko.md)

Ktor helpers for using caller-owned JetBrains Exposed JDBC and R2DBC resources
inside bluetape4k applications.

## Features

- `installBluetape4kExposedKtor()` for explicit opt-in health/readiness route
  installation.
- `ApplicationCall.exposedJdbcTransaction()` for blocking JDBC work on a
  caller-owned dispatcher.
- `ApplicationCall.exposedR2dbcTransaction()` for coroutine-native R2DBC work.
- `StatusPagesConfig.bluetape4kExposedErrors()` for client-safe Exposed error
  responses.
- `/healthz/exposed` and `/readyz/exposed` route helpers backed by caller-owned
  `Database` and `R2dbcDatabase` instances.

The default `installBluetape4kExposedKtor()` call is a no-op. It does not
install status pages, health routes, content negotiation, database pools,
dispatchers, meter registries, or generic bluetape4k Ktor core.

## Dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

With the Exposed BOM:

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.exposed:bluetape4k-exposed-bom:<version>"))
    implementation("io.github.bluetape4k.exposed:bluetape4k-exposed-ktor")
}
```

## Caller-Owned Resources

Create and close database resources outside this module. Pass the ready
`Database`, `R2dbcDatabase`, and JDBC dispatcher into Ktor.

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

The JDBC dispatcher isolates blocking JDBC calls from Ktor event-loop threads.
Close it in the same lifecycle that closes the JDBC pool. R2DBC does not need a
blocking dispatcher.

## Installation

Disable Ktor core status pages, then compose core and Exposed mappings in one
`StatusPages` block. Ktor allows each plugin to be installed once.

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

Standalone `installStatusPages = true` is available only when `StatusPages` is
not already installed. Because Exposed error responses use the standard
bluetape4k JSON payload, the caller must still install content negotiation.

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

## Route Transactions

JDBC calls are blocking. Always pass the dedicated blocking dispatcher.

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

R2DBC calls stay suspend-native and do not need a blocking dispatcher.

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

You can roll back to raw Exposed calls without changing the module contract:

```kotlin
withContext(jdbcDispatcher) {
    transaction(db = jdbcDatabase) {
        // JDBC work
    }
}

suspendTransaction(db = r2dbcDatabase) {
    // R2DBC work
}
```

## Readiness

When `installHealthRoutes = true`, the module installs:

| Path | Success response |
|---|---|
| `/healthz/exposed` | `{"status":"UP","details":{"exposed":"UP"}}` |
| `/readyz/exposed` | `{"status":"UP","details":{"jdbc":"UP","r2dbc":"UP"}}` when configured backends pass |

If any configured backend returns `DOWN` or `timeout`, `/readyz/exposed` returns
HTTP 503 with `status = "DOWN"`.

## Runbook

| Situation | Action |
|---|---|
| Disable Exposed status mapping | Keep `installStatusPages = false` and omit `bluetape4kExposedErrors()` from the shared `StatusPages` block. |
| Disable Exposed readiness routes | Keep `installHealthRoutes = false`, or remove `jdbcDatabase` / `r2dbcDatabase` from the Exposed Ktor config. |
| Roll back route helpers | Replace `call.exposedJdbcTransaction()` with `withContext(jdbcDispatcher) { transaction(db = jdbcDatabase) { ... } }`, or replace `call.exposedR2dbcTransaction()` with `suspendTransaction(db = r2dbcDatabase) { ... }`. |
| `/readyz/exposed` returns `DOWN` | Check database connectivity, credentials in the caller-owned pool, schema availability, and SQL errors. The response intentionally exposes only `jdbc` / `r2dbc` state. |
| `/readyz/exposed` returns `timeout` | Check pool exhaustion, network latency, slow `SELECT 1`, blocked JDBC dispatcher threads, and `readinessProbeTimeout` / `jdbcQueryTimeout`. |
| Application shutdown | Close caller-owned pools, `R2dbcDatabase` resources, dispatchers, and metric registries from the application lifecycle. |

## Non-goals

- No hidden database pool, connection string, migration, or schema creation.
- No automatic `ContentNegotiation` or generic bluetape4k Ktor core install.
- No second `StatusPages` installation when the application already owns it.
- No authentication, authorization, OpenAPI, tracing, or logging setup.
- No Spring Boot-style repository scanning or auto-configuration.

## Verification

The examples above are configuration and route fragments. Public API names and
default behavior are covered by `Bluetape4kExposedKtorTest`; run:

```bash
./gradlew :bluetape4k-exposed-ktor:test
```
