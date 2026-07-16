package io.bluetape4k.exposed.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing

/**
 * Installs Exposed-specific Ktor helpers.
 *
 * This does not install bluetape4k Ktor core, content negotiation, generic
 * health routes, database pools, dispatchers, or meter registries.
 */
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig = Bluetape4kExposedKtorConfig(),
) {
    installBluetape4kExposedKtorInternal(config, cacheReadiness = null)
}

/**
 * Installs Exposed-specific Ktor helpers with explicit cache readiness contributors.
 *
 * When [Bluetape4kExposedKtorConfig.installHealthRoutes] is `true`, this overload supports cache-only readiness
 * with no JDBC or R2DBC database. Database probes run first, then cache contributors run sequentially under one
 * shared monotonic readiness deadline. Setting `installHealthRoutes` to `false` installs no route and invokes no
 * contributor, so callers can place the direct route overload inside caller-owned authentication and security.
 *
 * The caller owns authentication, authorization, request concurrency, rate limiting, databases, dispatchers,
 * repositories, meter registries, caches, and shutdown. This installer creates or closes no thread, dispatcher,
 * scope, worker, database, repository, registry, or cache. Contributor probes must be side-effect-free O(1)
 * in-memory reads that are non-blocking and cancellation-cooperative; blocking and backend-I/O probes are
 * unsupported and may outlive the coroutine deadline.
 */
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    installBluetape4kExposedKtorInternal(config, cacheReadiness = cacheReadiness)
}

private fun Application.installBluetape4kExposedKtorInternal(
    config: Bluetape4kExposedKtorConfig,
    cacheReadiness: ExposedKtorCacheReadinessConfig?,
) {
    if (config.installStatusPages) {
        require(pluginOrNull(StatusPages) == null) {
            "StatusPages is already installed. Compose mappings in one block: " +
                "install(StatusPages) { bluetape4kErrorResponses(); bluetape4kExposedErrors() }."
        }
        install(StatusPages) {
            bluetape4kExposedErrors()
        }
    }

    if (config.installHealthRoutes) {
        config.validateHealthRoutes(hasCacheContributors = cacheReadiness != null)
        routing {
            if (cacheReadiness == null) {
                bluetape4kExposedHealthRoutes(
                    jdbcDatabase = config.jdbcDatabase,
                    jdbcBlockingDispatcher = config.jdbcBlockingDispatcher,
                    r2dbcDatabase = config.r2dbcDatabase,
                    healthPath = config.healthPath,
                    readinessPath = config.readinessPath,
                    readinessProbeTimeout = config.readinessProbeTimeout,
                    jdbcQueryTimeout = config.jdbcQueryTimeout,
                    meterRegistry = config.meterRegistry,
                )
            } else {
                bluetape4kExposedHealthRoutes(
                    jdbcDatabase = config.jdbcDatabase,
                    jdbcBlockingDispatcher = config.jdbcBlockingDispatcher,
                    r2dbcDatabase = config.r2dbcDatabase,
                    healthPath = config.healthPath,
                    readinessPath = config.readinessPath,
                    readinessProbeTimeout = config.readinessProbeTimeout,
                    jdbcQueryTimeout = config.jdbcQueryTimeout,
                    meterRegistry = config.meterRegistry,
                    cacheReadiness = cacheReadiness,
                )
            }
        }
    }
}
