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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.TimeSource

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
    installExposedHealthRoutes(
        jdbcDatabase = jdbcDatabase,
        jdbcBlockingDispatcher = jdbcBlockingDispatcher,
        r2dbcDatabase = r2dbcDatabase,
        healthPath = healthPath,
        readinessPath = readinessPath,
        readinessProbeTimeout = readinessProbeTimeout,
        jdbcQueryTimeout = jdbcQueryTimeout,
        meterRegistry = meterRegistry,
        cacheReadiness = null,
    )
}

/**
 * Adds Exposed liveness and readiness routes with explicit cache contributors.
 *
 * Database arguments may all be `null` for cache-only readiness. Cache contributors run sequentially after JDBC
 * and R2DBC under one shared monotonic [readinessProbeTimeout] deadline. Supported probes are caller-owned O(1)
 * in-memory, non-blocking, cancellation-cooperative observers; blocking, cancellation-insensitive, or backend-I/O
 * probes are unsupported and can outlive the coroutine deadline. The caller owns route authentication, request
 * concurrency and rate limiting, databases, dispatchers, repositories, registries, and their complete lifecycle.
 * This helper creates or closes no thread, dispatcher, scope, worker, database, repository, registry, or cache.
 * A supplier-thrown [CancellationException] becomes one sanitized `DOWN` result while an active request continues;
 * cancellation of the request context is rethrown and terminates readiness processing.
 * Responses expose only validated component names and finite `UP`, `DOWN`, or `timeout` values; supplier exception
 * messages, causes, cache keys, SQL, URLs, namespaces, credentials, and measurements are never returned or logged.
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
    cacheReadiness: ExposedKtorCacheReadinessConfig,
) {
    installExposedHealthRoutes(
        jdbcDatabase = jdbcDatabase,
        jdbcBlockingDispatcher = jdbcBlockingDispatcher,
        r2dbcDatabase = r2dbcDatabase,
        healthPath = healthPath,
        readinessPath = readinessPath,
        readinessProbeTimeout = readinessProbeTimeout,
        jdbcQueryTimeout = jdbcQueryTimeout,
        meterRegistry = meterRegistry,
        cacheReadiness = cacheReadiness,
    )
}

private fun Route.installExposedHealthRoutes(
    jdbcDatabase: Database?,
    jdbcBlockingDispatcher: CoroutineDispatcher?,
    r2dbcDatabase: R2dbcDatabase?,
    healthPath: String,
    readinessPath: String,
    readinessProbeTimeout: Duration,
    jdbcQueryTimeout: Duration,
    meterRegistry: MeterRegistry?,
    cacheReadiness: ExposedKtorCacheReadinessConfig?,
) {
    healthPath.requireAbsoluteExposedKtorPath("healthPath")
    readinessPath.requireAbsoluteExposedKtorPath("readinessPath")
    readinessProbeTimeout.requirePositiveDuration("readinessProbeTimeout")
    jdbcQueryTimeout.requirePositiveDuration("jdbcQueryTimeout")
    require(jdbcDatabase != null || r2dbcDatabase != null || cacheReadiness != null) {
        if (cacheReadiness == null) {
            "At least one of jdbcDatabase or r2dbcDatabase is required for Exposed readiness routes."
        } else {
            "At least one database or cache contributor is required for Exposed readiness routes."
        }
    }
    require(jdbcDatabase == null || jdbcBlockingDispatcher != null) {
        "jdbcBlockingDispatcher is required for JDBC readiness routes."
    }

    val cacheBindings = cacheReadiness
        ?.let { registerExposedKtorCacheMetrics(meterRegistry, it) }
        .orEmpty()

    get(healthPath) {
        call.respond(HealthResponse.up(mapOf("exposed" to HealthResponse.UP)))
    }

    get(readinessPath) {
        val details = aggregateExposedKtorReadiness(
            jdbcProbe = jdbcDatabase?.let { db ->
                {
                    probeJdbcReadiness(
                        db = db,
                        blockingDispatcher = requireNotNull(jdbcBlockingDispatcher),
                        readinessProbeTimeout = readinessProbeTimeout,
                        jdbcQueryTimeout = jdbcQueryTimeout,
                        meterRegistry = meterRegistry,
                    )
                }
            },
            r2dbcProbe = r2dbcDatabase?.let { db ->
                {
                    probeR2dbcReadiness(
                        db = db,
                        readinessProbeTimeout = readinessProbeTimeout,
                        meterRegistry = meterRegistry,
                    )
                }
            },
            cacheBindings = cacheBindings,
            cachePhaseTimeout = readinessProbeTimeout,
        )

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

internal suspend fun aggregateExposedKtorReadiness(
    jdbcProbe: (suspend () -> String)?,
    r2dbcProbe: (suspend () -> String)?,
    cacheBindings: List<ExposedKtorCacheMetricBinding>,
    cachePhaseTimeout: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): LinkedHashMap<String, String> {
    val details = linkedMapOf<String, String>()
    jdbcProbe?.let { details[JDBC_BACKEND] = it() }
    r2dbcProbe?.let { details[R2DBC_BACKEND] = it() }

    val cachePhaseStart = timeSource.markNow()
    cacheBindings.forEach { binding ->
        val key = "cache.${binding.contributor.component}"
        val remaining = cachePhaseTimeout - cachePhaseStart.elapsedNow()
        if (remaining <= ZERO) {
            val generation = binding.claimGeneration()
            binding.publishUnavailable(generation)
            details[key] = TIMEOUT_OUTCOME
            return@forEach
        }

        val generation = binding.claimGeneration()
        val attemptStart = timeSource.markNow()
        val requestContext = currentCoroutineContext()
        val terminal = try {
            withTimeoutOrNull(remaining) {
                probeCacheContributor(binding.contributor)
            } ?: CacheProbeTerminal.Timeout
        } catch (cancellation: CancellationException) {
            if (requestContext.isActive) {
                CacheProbeTerminal.Error
            } else {
                binding.publishUnavailable(generation)
                binding.record(CANCELLED_OUTCOME, attemptStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0L))
                throw cancellation
            }
        }

        details[key] = when (terminal) {
            is CacheProbeTerminal.Success -> {
                binding.publish(generation, terminal.sample)
                val outcome = if (terminal.sample.status == ExposedKtorCacheStatus.UP) {
                    SUCCESS_OUTCOME
                } else {
                    ERROR_OUTCOME
                }
                binding.record(outcome, attemptStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0L))
                terminal.sample.status.name
            }

            CacheProbeTerminal.Error -> {
                binding.publishUnavailable(generation)
                binding.record(ERROR_OUTCOME, attemptStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0L))
                HealthResponse.DOWN
            }

            CacheProbeTerminal.Timeout -> {
                binding.publishUnavailable(generation)
                binding.record(TIMEOUT_OUTCOME, attemptStart.elapsedNow().inWholeNanoseconds.coerceAtLeast(0L))
                TIMEOUT_OUTCOME
            }
        }
    }
    return details
}

private suspend fun probeCacheContributor(
    contributor: ExposedKtorCacheContributor,
): CacheProbeTerminal = try {
    CacheProbeTerminal.Success(contributor.probe())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    CacheProbeTerminal.Error
}

private sealed interface CacheProbeTerminal {
    data class Success(val sample: ExposedKtorCacheSample) : CacheProbeTerminal
    data object Error : CacheProbeTerminal
    data object Timeout : CacheProbeTerminal
}

internal suspend fun probeJdbcReadiness(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    readinessProbeTimeout: Duration,
    jdbcQueryTimeout: Duration,
    meterRegistry: MeterRegistry?,
): String = try {
    meterRegistry.recordExposedKtorReadiness(JDBC_BACKEND) {
        withTimeoutOrNull(readinessProbeTimeout) {
            withContext(blockingDispatcher) {
                transaction(db = db) {
                    queryTimeout = jdbcQueryTimeout.inWholeSeconds.coerceAtLeast(1).toInt()
                    exec("SELECT 1") { resultSet ->
                        resultSet.next()
                    }
                }
            }
            HealthResponse.UP
        } ?: throw ExposedKtorReadinessTimeoutException(JDBC_BACKEND)
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: ExposedKtorReadinessTimeoutException) {
    TIMEOUT_OUTCOME
} catch (_: Exception) {
    HealthResponse.DOWN
}

internal suspend fun probeR2dbcReadiness(
    db: R2dbcDatabase,
    readinessProbeTimeout: Duration,
    meterRegistry: MeterRegistry?,
): String = try {
    meterRegistry.recordExposedKtorReadiness(R2DBC_BACKEND) {
        withTimeoutOrNull(readinessProbeTimeout) {
            suspendTransaction(db = db) {
                exec("SELECT 1")
            }
            HealthResponse.UP
        } ?: throw ExposedKtorReadinessTimeoutException(R2DBC_BACKEND)
    }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: ExposedKtorReadinessTimeoutException) {
    TIMEOUT_OUTCOME
} catch (_: Exception) {
    HealthResponse.DOWN
}
