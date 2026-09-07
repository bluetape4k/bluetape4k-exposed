package io.bluetape4k.exposed.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal const val JDBC_BACKEND: String = "jdbc"
internal const val R2DBC_BACKEND: String = "r2dbc"
internal const val TRANSACTION_OPERATION: String = "transaction"
internal const val READINESS_OPERATION: String = "readiness"
internal const val SUCCESS_OUTCOME: String = "success"
internal const val ERROR_OUTCOME: String = "error"
internal const val TIMEOUT_OUTCOME: String = "timeout"
internal const val CANCELLED_OUTCOME: String = "cancelled"

private const val TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.transaction"
private const val READINESS_METER_NAME = "bluetape4k.exposed.ktor.readiness"

internal suspend fun <T> MeterRegistry?.recordExposedKtorTransaction(
    backend: String,
    block: suspend () -> T,
): T = recordExposedKtor(
    meterName = TRANSACTION_METER_NAME,
    backend = backend,
    operation = TRANSACTION_OPERATION,
    block = block,
)

internal suspend fun <T> MeterRegistry?.recordExposedKtorReadiness(
    backend: String,
    block: suspend () -> T,
): T = recordExposedKtor(
    meterName = READINESS_METER_NAME,
    backend = backend,
    operation = READINESS_OPERATION,
    block = block,
)

private suspend fun <T> MeterRegistry?.recordExposedKtor(
    meterName: String,
    backend: String,
    operation: String,
    block: suspend () -> T,
): T {
    val registry = this ?: return block()

    val sample = Timer.start(registry)
    val result = try {
        block()
    } catch (e: ExposedKtorReadinessTimeoutException) {
        sample.stopFailedMetric(registry, meterName, backend, operation, TIMEOUT_OUTCOME, e)
        throw e
    } catch (e: CancellationException) {
        sample.stopFailedMetric(registry, meterName, backend, operation, CANCELLED_OUTCOME, e)
        throw e
    } catch (e: Throwable) {
        sample.stopFailedMetric(registry, meterName, backend, operation, ERROR_OUTCOME, e)
        throw e
    }
    sample.stopSuccessfulMetric(registry, meterName, backend, operation)
    return result
}

/**
 * 성공 후 metric 기록의 일반 [Exception]은 이미 완료된 operation 결과를 변경하지 않는다.
 * JVM [Error]는 복구 불가능한 fatal 신호로 간주하여 전파한다.
 */
@Suppress("TooGenericExceptionCaught")
private fun Timer.Sample.stopSuccessfulMetric(
    registry: MeterRegistry,
    meterName: String,
    backend: String,
    operation: String,
) {
    try {
        stop(registry.exposedKtorTimer(meterName, backend, operation, SUCCESS_OUTCOME))
    } catch (metricFailure: Exception) {
        ExposedKtorMetricsLog.log.warn(metricFailure) {
            "Exposed Ktor metric recording failed after a successful operation. " +
                "backend=$backend, operation=$operation, exceptionType=${metricFailure::class.qualifiedName}"
        }
    }
}

/** 실패 metric 기록은 원래 timeout·취소·operation 예외를 대체하지 않는다. */
@Suppress("TooGenericExceptionCaught")
private fun Timer.Sample.stopFailedMetric(
    registry: MeterRegistry,
    meterName: String,
    backend: String,
    operation: String,
    outcome: String,
    primary: Throwable,
) {
    try {
        stop(registry.exposedKtorTimer(meterName, backend, operation, outcome))
    } catch (metricFailure: Throwable) {
        if (metricFailure !== primary) {
            primary.addSuppressed(metricFailure)
        }
    }
}

private fun MeterRegistry.exposedKtorTimer(
    meterName: String,
    backend: String,
    operation: String,
    outcome: String,
): Timer =
    Timer.builder(meterName)
        .tag("backend", backend)
        .tag("operation", operation)
        .tag("outcome", outcome)
        .register(this)

internal fun MeterRegistry?.recordExposedKtorReadinessTimeout(
    backend: String,
    elapsedNanos: Long,
) {
    this ?: return
    exposedKtorTimer(READINESS_METER_NAME, backend, READINESS_OPERATION, TIMEOUT_OUTCOME)
        .record(elapsedNanos, TimeUnit.NANOSECONDS)
}

private object ExposedKtorMetricsLog : KLogging()
