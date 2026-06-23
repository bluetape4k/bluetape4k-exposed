package io.bluetape4k.exposed.ktor

import io.bluetape4k.ktor.core.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.Duration
import kotlin.time.measureTimedValue

/**
 * Adds Exposed-specific liveness and readiness routes.
 */
fun Route.bluetape4kExposedHealthRoutes(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    healthPath: String = Bluetape4kExposedKtorConfig.DEFAULT_HEALTH_PATH,
    readinessPath: String = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PATH,
    readinessProbeTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_READINESS_PROBE_TIMEOUT,
    jdbcQueryTimeout: Duration = Bluetape4kExposedKtorConfig.DEFAULT_JDBC_QUERY_TIMEOUT,
    meterRegistry: MeterRegistry? = null,
) {
    healthPath.requireAbsoluteExposedKtorPath("healthPath")
    readinessPath.requireAbsoluteExposedKtorPath("readinessPath")
    readinessProbeTimeout.requirePositiveDuration("readinessProbeTimeout")
    jdbcQueryTimeout.requirePositiveDuration("jdbcQueryTimeout")
    require(jdbcDatabase != null || r2dbcDatabase != null) {
        "At least one of jdbcDatabase or r2dbcDatabase is required for Exposed readiness routes."
    }
    require(jdbcDatabase == null || jdbcBlockingDispatcher != null) {
        "jdbcBlockingDispatcher is required for JDBC readiness routes."
    }

    get(healthPath) {
        call.respond(HealthResponse.up(mapOf("exposed" to HealthResponse.UP)))
    }

    get(readinessPath) {
        val details = linkedMapOf<String, String>()
        jdbcDatabase?.let { db ->
            details[JDBC_BACKEND] = probeJdbcReadiness(
                db = db,
                blockingDispatcher = jdbcBlockingDispatcher!!,
                readinessProbeTimeout = readinessProbeTimeout,
                jdbcQueryTimeout = jdbcQueryTimeout,
                meterRegistry = meterRegistry,
            )
        }
        r2dbcDatabase?.let { db ->
            details[R2DBC_BACKEND] = probeR2dbcReadiness(
                db = db,
                readinessProbeTimeout = readinessProbeTimeout,
                meterRegistry = meterRegistry,
            )
        }

        val response = if (details.values.all { it == HealthResponse.UP }) {
            HealthResponse.up(details)
        } else {
            HealthResponse.down(details)
        }
        val status = if (response.status == HealthResponse.UP) {
            HttpStatusCode.OK
        } else {
            HttpStatusCode.ServiceUnavailable
        }
        call.respond(status, response)
    }
}

private suspend fun probeJdbcReadiness(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    readinessProbeTimeout: Duration,
    jdbcQueryTimeout: Duration,
    meterRegistry: MeterRegistry?,
): String {
    val timedValue = measureTimedValue {
        withTimeoutOrNull(readinessProbeTimeout) {
            try {
                meterRegistry.recordExposedKtorReadiness(JDBC_BACKEND) {
                    withContext(blockingDispatcher) {
                        transaction(db = db) {
                            queryTimeout = jdbcQueryTimeout.inWholeSeconds.coerceAtLeast(1).toInt()
                            exec("SELECT 1") { resultSet ->
                                resultSet.next()
                            }
                        }
                    }
                }
                HealthResponse.UP
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                HealthResponse.DOWN
            }
        }
    }
    if (timedValue.value == null) {
        meterRegistry.recordExposedKtorReadinessTimeout(JDBC_BACKEND, timedValue.duration.inWholeNanoseconds)
        return TIMEOUT_OUTCOME
    }
    return timedValue.value ?: TIMEOUT_OUTCOME
}

private suspend fun probeR2dbcReadiness(
    db: R2dbcDatabase,
    readinessProbeTimeout: Duration,
    meterRegistry: MeterRegistry?,
): String {
    val timedValue = measureTimedValue {
        withTimeoutOrNull(readinessProbeTimeout) {
            try {
                meterRegistry.recordExposedKtorReadiness(R2DBC_BACKEND) {
                    suspendTransaction(db = db) {
                        exec("SELECT 1")
                    }
                }
                HealthResponse.UP
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                HealthResponse.DOWN
            }
        }
    }
    if (timedValue.value == null) {
        meterRegistry.recordExposedKtorReadinessTimeout(R2DBC_BACKEND, timedValue.duration.inWholeNanoseconds)
        return TIMEOUT_OUTCOME
    }
    return timedValue.value ?: TIMEOUT_OUTCOME
}
