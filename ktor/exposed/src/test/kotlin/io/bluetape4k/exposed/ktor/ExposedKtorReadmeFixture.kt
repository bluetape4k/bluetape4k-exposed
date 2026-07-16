package io.bluetape4k.exposed.ktor

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.snapshot.SnapshotCacheFailureBuffer
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

@Suppress("unused")
private object ExposedKtorReadmeFixture {

    // example:jdbc-report:start
    fun jdbcCacheContributor(
        report: () -> CacheHealthReport,
    ): ExposedKtorCacheContributor =
        ExposedKtorCacheContributor.jdbcRepository("orders", report)
    // example:jdbc-report:end

    // example:r2dbc-report:start
    fun r2dbcCacheContributor(
        report: suspend () -> CacheHealthReport,
    ): ExposedKtorCacheContributor =
        ExposedKtorCacheContributor.r2dbcRepository("sessions", report)
    // example:r2dbc-report:end

    // example:snapshot:start
    fun snapshotContributor(
        failureBuffer: SnapshotCacheFailureBuffer,
    ): ExposedKtorCacheContributor =
        ExposedKtorCacheContributor.snapshot("snapshots", failureBuffer)
    // example:snapshot:end

    // example:custom-status:start
    fun customContributor(
        probe: suspend () -> ExposedKtorCacheStatus,
    ): ExposedKtorCacheContributor =
        ExposedKtorCacheContributor.custom("redis", probe)
    // example:custom-status:end

    // example:cache-only-installer:start
    fun Application.installCacheOnlyReadiness(
        cacheReadiness: ExposedKtorCacheReadinessConfig,
    ) {
        installBluetape4kExposedKtor(
            config = Bluetape4kExposedKtorConfig(installHealthRoutes = true),
            cacheReadiness = cacheReadiness,
        )
    }
    // example:cache-only-installer:end

    // example:ingress-root-route:start
    fun Application.installIngressProtectedReadiness(
        cacheReadiness: ExposedKtorCacheReadinessConfig,
    ) {
        // Restrict /healthz/exposed and /readyz/exposed with ingress or network policy.
        installBluetape4kExposedKtor(
            config = Bluetape4kExposedKtorConfig(installHealthRoutes = true),
            cacheReadiness = cacheReadiness,
        )
    }
    // example:ingress-root-route:end

    // example:authenticated-direct-route:start
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
    // example:authenticated-direct-route:end
}
