package io.bluetape4k.exposed.ktor.core

import io.bluetape4k.ktor.core.HealthResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * backend-neutral health와 순차 readiness route를 설치합니다.
 *
 * 전달된 probe는 설치 시 상태를 고정합니다. readiness는 등록 순서와 하나의 공유
 * monotonic deadline을 사용하며 동시에 하나의 probe만 실행합니다. dispatcher,
 * worker, scope, resource를 생성하지 않습니다.
 */
fun Route.bluetape4kExposedHealthRoutes(
    probes: List<ExposedKtorReadinessProbe>,
    healthPath: String = DEFAULT_HEALTH_PATH,
    readinessPath: String = DEFAULT_READINESS_PATH,
    readinessProbeTimeout: Duration = DEFAULT_READINESS_PROBE_TIMEOUT,
    meterRegistry: MeterRegistry? = null,
) {
    val registered = validateReadinessProbes(probes)
    val normalizedHealthPath = healthPath.requireLiteralExposedKtorPath("healthPath")
    val normalizedReadinessPath = readinessPath.requireLiteralExposedKtorPath("readinessPath")
    requireDistinctExposedKtorCorePaths(normalizedHealthPath, normalizedReadinessPath)
    readinessProbeTimeout.requireFinitePositive("readinessProbeTimeout")
    val metrics = meterRegistry.installCoreReadinessMetrics(registered)

    get(normalizedHealthPath) {
        call.respond(HealthResponse.up(mapOf("exposed" to HealthResponse.UP)))
    }

    get(normalizedReadinessPath) {
        val details = evaluateExposedKtorReadiness(
            probes = registered,
            readinessProbeTimeout = readinessProbeTimeout,
            metrics = metrics,
        )
        val response = if (details.values.all { it == ExposedKtorReadinessOutcome.UP.name }) {
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

internal const val DEFAULT_HEALTH_PATH = "/healthz/exposed"
internal const val DEFAULT_READINESS_PATH = "/readyz/exposed"
internal val DEFAULT_READINESS_PROBE_TIMEOUT: Duration = 1.seconds

@Suppress("TooGenericExceptionCaught")
internal suspend fun evaluateExposedKtorReadiness(
    probes: List<RegisteredProbe>,
    readinessProbeTimeout: Duration,
    metrics: List<CoreReadinessMetricBinding> = probes.map { CoreReadinessMetricBinding.withoutRegistry() },
    clock: ReadinessClock = SYSTEM_READINESS_CLOCK,
): LinkedHashMap<String, String> {
    readinessProbeTimeout.requireFinitePositive("readinessProbeTimeout")
    require(metrics.size == probes.size) { "Readiness metrics must match probe registrations." }

    val details = LinkedHashMap<String, String>(probes.size)
    val startedNanos = clock.nowNanos()
    val budgetNanos = readinessProbeTimeout.toTimeoutNanosSaturated()

    probes.forEachIndexed { index, probe ->
        val binding = metrics[index]
        val remainingNanos = remainingReadinessNanos(startedNanos, clock.nowNanos(), budgetNanos)
        if (remainingNanos <= 0L) {
            details[probe.component] = ExposedKtorReadinessOutcome.TIMEOUT.name
            binding.record("timeout", 0L)
            return@forEachIndexed
        }

        val remaining = remainingNanos.nanoseconds
        val attemptStartedNanos = clock.nowNanos()
        val result = try {
            currentCoroutineContext().ensureActive()
            when (val attempt = withTimeoutOrNull(remaining) {
                ProbeAttempt.Value(probe.delegate.probe(remaining))
            }) {
                null -> ProbeAttempt.OuterTimeout
                else -> attempt
            }
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                binding.record("cancelled", elapsedNanos(clock, attemptStartedNanos))
                throw cancellation
            }
            ProbeAttempt.Value(ExposedKtorReadinessOutcome.DOWN)
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            ProbeAttempt.Value(ExposedKtorReadinessOutcome.DOWN)
        }

        // 반환값과 경쟁하는 호출자 취소는 반환값보다 우선합니다.
        currentCoroutineContext().ensureActive()
        val elapsed = elapsedNanos(clock, attemptStartedNanos)
        val deadlineExpired = remainingReadinessNanos(startedNanos, clock.nowNanos(), budgetNanos) <= 0L
        val outcome = when {
            deadlineExpired -> ExposedKtorReadinessOutcome.TIMEOUT
            result is ProbeAttempt.OuterTimeout -> ExposedKtorReadinessOutcome.TIMEOUT
            else -> (result as ProbeAttempt.Value).value
        }
        details[probe.component] = outcome.name
        binding.record(outcome.metricValue, elapsed)
    }
    return details
}

private fun elapsedNanos(clock: ReadinessClock, startedNanos: Long): Long {
    val now = clock.nowNanos()
    return if (now >= startedNanos) now - startedNanos else 0L
}

private val ExposedKtorReadinessOutcome.metricValue: String
    get() = when (this) {
        ExposedKtorReadinessOutcome.UP -> "success"
        ExposedKtorReadinessOutcome.DOWN -> "error"
        ExposedKtorReadinessOutcome.TIMEOUT -> "timeout"
    }
