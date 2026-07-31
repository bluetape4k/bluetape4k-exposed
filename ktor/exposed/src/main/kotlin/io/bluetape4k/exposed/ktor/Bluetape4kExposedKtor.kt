package io.bluetape4k.exposed.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing

/**
 * Exposed 전용 Ktor helper를 설치합니다.
 *
 * bluetape4k Ktor core, content negotiation, generic health route, database pool,
 * dispatcher 또는 meter registry는 설치하지 않습니다.
 */
fun Application.installBluetape4kExposedKtor(
    config: Bluetape4kExposedKtorConfig = Bluetape4kExposedKtorConfig(),
) {
    installBluetape4kExposedKtorInternal(config, cacheReadiness = null)
}

/**
 * 명시적인 cache readiness contributor와 함께 Exposed 전용 Ktor helper를 설치합니다.
 *
 * [Bluetape4kExposedKtorConfig.installHealthRoutes]가 `true`이면 JDBC나 R2DBC database가 없는 cache-only
 * readiness를 지원합니다. database probe를 먼저 실행하고 cache contributor를 하나의 shared monotonic readiness
 * deadline 아래 순차 실행합니다. `installHealthRoutes=false`이면 route를 설치하거나 contributor를 호출하지 않으므로
 * 호출자가 직접 route overload를 자체 authentication과 security 안에 배치할 수 있습니다.
 *
 * 호출자는 authentication, authorization, request concurrency, rate limit, database, dispatcher, repository,
 * meter registry, cache와 shutdown을 소유합니다. installer는 어떤 resource도 생성하거나 닫지 않습니다.
 * contributor probe는 non-blocking, cancellation-cooperative인 side-effect-free O(1) in-memory 조회여야 합니다.
 * blocking과 backend-I/O probe는 지원하지 않으며 coroutine deadline보다 오래 실행될 수 있습니다.
 *
 * Contract: caller owns authentication, dispatchers, repositories, registries, and shutdown; this installer
 * creates or closes no resources.
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
