package io.bluetape4k.exposed.ktor

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
    if (this == null) {
        return block()
    }

    val sample = Timer.start(this)
    return try {
        block().also {
            sample.stop(exposedKtorTimer(meterName, backend, operation, SUCCESS_OUTCOME))
        }
    } catch (e: ExposedKtorReadinessTimeoutException) {
        sample.stop(exposedKtorTimer(meterName, backend, operation, TIMEOUT_OUTCOME))
        throw e
    } catch (e: CancellationException) {
        sample.stop(exposedKtorTimer(meterName, backend, operation, CANCELLED_OUTCOME))
        throw e
    } catch (e: Throwable) {
        sample.stop(exposedKtorTimer(meterName, backend, operation, ERROR_OUTCOME))
        throw e
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
