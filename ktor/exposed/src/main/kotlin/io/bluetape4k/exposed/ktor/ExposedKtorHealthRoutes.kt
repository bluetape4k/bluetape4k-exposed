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

/** Exposed 전용 liveness와 readiness route를 추가합니다. */
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
 * 명시적인 cache contributor와 함께 Exposed liveness와 readiness route를 추가합니다.
 *
 * cache-only readiness에서는 database 인수를 모두 `null`로 둘 수 있습니다. cache contributor는 JDBC와 R2DBC 뒤에서
 * 하나의 shared monotonic [readinessProbeTimeout] deadline 아래 순차 실행됩니다. 지원하는 probe는 호출자 소유의
 * O(1) in-memory, non-blocking, cancellation-cooperative observer입니다. blocking, cancellation-insensitive 또는
 * backend-I/O probe는 지원하지 않으며 coroutine deadline보다 오래 실행될 수 있습니다. 호출자는 route authentication,
 * request concurrency와 rate limit, database, dispatcher, repository, registry와 전체 lifecycle을 소유합니다.
 * 이 helper는 thread, dispatcher, scope, worker, database, repository, registry, cache를 생성하거나 닫지 않습니다.
 * 활성 request 중 supplier가 던진 [CancellationException]은 정제된 `DOWN` 결과가 되며 request context cancellation은
 * 다시 던져 readiness 처리를 종료합니다. response는 검증된 component 이름과 유한 `UP`, `DOWN`, `timeout` 값만 노출하며
 * supplier exception message, cause, cache key, SQL, URL, namespace, credential, measurement는 반환하거나 기록하지 않습니다.
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
