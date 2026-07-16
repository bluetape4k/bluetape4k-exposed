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

## Cache Readiness Contributors

Use a fixed operational component name. It must match `[a-z][a-z0-9_-]{0,62}`;
one configuration accepts `1..16` unique contributors. Never put a tenant, cache
key, URL, endpoint, namespace, credential, or secret in the component. Suppliers
must be side-effect-free O(1) reads of existing in-memory state.

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

JDBC reports are ordinary in-memory reads; R2DBC and custom suppliers are
suspending, non-blocking, and cancellation-cooperative. Blocking,
cancellation-insensitive, database, cache, network, or file I/O is unsupported.
A coroutine timeout cannot terminate a blocking thread or process, so such a
supplier may outlive the request deadline.

## Installation and Security

Cache-only installation needs no database:

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

The installer places root routes in the application routing tree. Use this
shape only when ingress or network policy restricts the probe paths:

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

To apply application authentication, disable installer-owned routes and mount
the direct overload once inside the caller-owned authentication block:

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

Do not install a second unprotected route. The caller owns authentication,
authorization, request concurrency, and rate limiting.

## Readiness Semantics and Budget

| Path or state | Ktor result |
|---|---|
| `/healthz/exposed` | Probe-free liveness: `UP` with `exposed=UP`; it never invokes database or cache suppliers. |
| `/readyz/exposed` | Traffic readiness. JDBC runs first, then R2DBC, then cache contributors in configuration order. |
| Repository `NOT_APPLICABLE`, `IDLE`, or `RUNNING` with no flush error | `cache.<component>=UP` |
| Repository `DRAINING`, `FAILED`, or `STOPPED`, or any flush error | `cache.<component>=DOWN` and aggregate HTTP 503 |
| Snapshot pending, dropped, or observer-failure count | Measurements only; they never make readiness fail by themselves. |

Ktor has no `OUT_OF_SERVICE` response state: draining and stopped repositories
are not ready for traffic, so they map to `DOWN`. Spring Actuator keeps the
management-specific `OUT_OF_SERVICE` distinction for `DRAINING` and `STOPPED`.

Responses contain only allowlisted `jdbc`, `r2dbc`, and `cache.<component>`
details with `UP`, `DOWN`, or `timeout`. Supplier exceptions, messages, causes,
keys, SQL, URLs, and credentials are never returned.

Let `R` be `readinessProbeTimeout`. Cache contributors share one cache-phase
deadline, rather than receiving `R` each. Use this conservative planning bound:

```text
T_endpoint = I_jdbc * (R + J_effective) + I_r2dbc * R + I_cache * R + overhead
```

The JDBC query timeout is truncated to whole seconds with a minimum of one
second: `J_effective = max(1 second, jdbcQueryTimeout.inWholeSeconds)`. With all
three phases enabled, `R=2s`, and `jdbcQueryTimeout=1500ms`, the planned bound is
`(2+1)+2+2 = 7s` plus overhead. This is not a hard guarantee for saturated
drivers or unsupported blocking probes.

```yaml
readinessProbe:
  httpGet:
    path: /readyz/exposed
    port: 8080
  timeoutSeconds: 10
  periodSeconds: 15
  failureThreshold: 3
```

Round the bound up and add margin. Keep `periodSeconds > timeoutSeconds` and
`failureThreshold >= 3` so one slow probe does not immediately withdraw traffic.

## Metrics

These dotted names are Micrometer meter IDs, not fixed Prometheus or OpenTelemetry
series names:

| Meter ID | Tags | Base unit / meaning |
|---|---|---|
| `bluetape4k.exposed.ktor.cache.readiness` | `component`, `kind`, `operation=readiness`, `outcome=success|error|timeout|cancelled` | timer |
| `bluetape4k.exposed.ktor.cache.queue.depth` | `component`, `kind` | `entries` |
| `bluetape4k.exposed.ktor.cache.snapshot.pending` | `component`, `kind` | `events` |
| `bluetape4k.exposed.ktor.cache.snapshot.dropped` | `component`, `kind` | cumulative `events` |
| `bluetape4k.exposed.ktor.cache.snapshot.observer.failures` | `component`, `kind` | cumulative `events` |

Each contributor registers four gauges and four finite-outcome timers: at most
`16 * 8 = 128` meter IDs. Exported time-series counts and suffixes depend on the
registry and its distribution configuration; inspect the actual exporter before
writing queries. A missing, omitted, or `NaN` gauge is unavailable, not zero.
Correlate it with readiness and timer outcome. Apply `rate`/`increase` to
cumulative dropped or observer-failure counters with process restart/reset
awareness.

Meter identities live for the registry lifetime. Installation rejects an
existing matching identity with `reason=identity_collision` and rolls back newly
claimed meters. Prefer one route per registry, or use a fresh registry. Never
remove colliding meters while an older route may still serve requests.

## Runbook

| Situation | Action |
|---|---|
| Database `DOWN` | Check caller-owned pool connectivity and credentials, schema availability, and SQL errors. The response intentionally exposes only the finite `jdbc` / `r2dbc` state. |
| Database `timeout` | Check pool exhaustion, network latency, slow `SELECT 1`, blocked JDBC dispatcher threads, `readinessProbeTimeout`, and `jdbcQueryTimeout`. |
| Repository `DOWN` | Inspect `workerState`, queue depth, and caller-owned repository telemetry. `DRAINING` is expected during withdrawal; `FAILED` is a worker failure; `STOPPED` is terminal. Do not expose exception messages through Ktor. |
| Cache `timeout` | Check the shared `R` budget and supplier cooperation. Remove backend I/O or blocking work; the helper cannot terminate it. |
| Snapshot cumulative counters rise | Inspect caller-owned drain/observer handling. Counters are measurements only; use restart/reset-aware rate or increase queries. |
| Gauge is missing, omitted, or `NaN` | Treat it as unavailable, not zero, and correlate with the latest readiness and timer outcome. |
| Invalid configuration | Fix component regex/uniqueness/count or unsafe data. Do not derive component names from runtime values. |
| Unsupported custom probe | Replace it with a side-effect-free O(1) in-memory status read; keep backend diagnostics in caller telemetry. |
| Meter collision | Keep the older route serving until traffic is withdrawn, then close its application/registry or use a fresh registry before reinstalling. |
| Disable Exposed status mapping | Keep `installStatusPages = false` and omit `bluetape4kExposedErrors()` from the shared `StatusPages` block. |
| Roll back route helpers | Replace the Ktor helpers with caller-owned raw Exposed transaction calls. |
| Shutdown | Withdraw traffic, start repository drain/close, observe readiness enter `DRAINING` and then `STOPPED`, stop the application, then close the registry and other caller-owned pools/dispatchers. Route probes only observe; they close nothing. |

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
