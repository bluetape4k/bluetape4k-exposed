package io.bluetape4k.exposed.ktor

import io.bluetape4k.support.requireNotBlank
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Explicit opt-in configuration for [installBluetape4kExposedKtor].
 *
 * The installer never creates or closes database resources, dispatchers, pools,
 * or meter registries. Applications own those lifecycles.
 */
class Bluetape4kExposedKtorConfig(
    val jdbcDatabase: Database? = null,
    val jdbcBlockingDispatcher: CoroutineDispatcher? = null,
    val r2dbcDatabase: R2dbcDatabase? = null,
    val installStatusPages: Boolean = false,
    val installHealthRoutes: Boolean = false,
    val healthPath: String = DEFAULT_HEALTH_PATH,
    val readinessPath: String = DEFAULT_READINESS_PATH,
    val readinessProbeTimeout: Duration = DEFAULT_READINESS_PROBE_TIMEOUT,
    val jdbcQueryTimeout: Duration = DEFAULT_JDBC_QUERY_TIMEOUT,
    val meterRegistry: MeterRegistry? = null,
) {

    init {
        healthPath.requireAbsoluteExposedKtorPath("healthPath")
        readinessPath.requireAbsoluteExposedKtorPath("readinessPath")
        readinessProbeTimeout.requirePositiveDuration("readinessProbeTimeout")
        jdbcQueryTimeout.requirePositiveDuration("jdbcQueryTimeout")
        require(jdbcDatabase != null || jdbcBlockingDispatcher == null) {
            "jdbcBlockingDispatcher requires jdbcDatabase."
        }
    }

    internal fun validateHealthRoutes(hasCacheContributors: Boolean) {
        require(jdbcDatabase != null || r2dbcDatabase != null || hasCacheContributors) {
            if (hasCacheContributors) {
                "At least one database or cache contributor is required when installHealthRoutes is true."
            } else {
                "At least one of jdbcDatabase or r2dbcDatabase is required when installHealthRoutes is true."
            }
        }
        require(jdbcDatabase == null || jdbcBlockingDispatcher != null) {
            "jdbcBlockingDispatcher is required when JDBC readiness routes are installed."
        }
    }

    companion object {
        const val DEFAULT_HEALTH_PATH: String = "/healthz/exposed"
        const val DEFAULT_READINESS_PATH: String = "/readyz/exposed"
        val DEFAULT_READINESS_PROBE_TIMEOUT: Duration = 1.seconds
        val DEFAULT_JDBC_QUERY_TIMEOUT: Duration = 1.seconds
    }
}

internal fun String.requireAbsoluteExposedKtorPath(parameterName: String): String {
    requireNotBlank(parameterName)
    require(startsWith("/")) { "$parameterName[$this] must start with '/'." }
    return this
}

internal fun Duration.requirePositiveDuration(parameterName: String): Duration {
    require(parameterName.isNotBlank()) { "parameterName must not be blank." }
    require(isPositive()) { "$parameterName[$this] must be positive." }
    return this
}
