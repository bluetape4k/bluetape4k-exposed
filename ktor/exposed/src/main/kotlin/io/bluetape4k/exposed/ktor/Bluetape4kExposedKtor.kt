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
        config.validateHealthRoutes()
        routing {
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
        }
    }
}
