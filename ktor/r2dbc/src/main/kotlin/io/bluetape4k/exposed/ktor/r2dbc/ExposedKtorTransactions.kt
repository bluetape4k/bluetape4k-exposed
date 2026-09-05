package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * 호출자 소유 자원으로 coroutine-native Exposed R2DBC 트랜잭션을 실행합니다.
 * 취소와 [Error]는 그대로 전달하고, 일반 예외는 [ExposedKtorTransactionException]의 원인으로 보존합니다.
 * 실패 경로의 메트릭 기록 예외는 주원인의 `suppressed`에 추가합니다.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
suspend fun <T> ApplicationCall.exposedR2dbcTransaction(
    db: R2dbcDatabase,
    meterRegistry: MeterRegistry? = null,
    block: suspend R2dbcTransaction.() -> T,
): T {
    val started = meterRegistry?.let(Timer::start)
    return try {
        suspendTransaction(db = db) {
            block()
        }.also {
            started?.stopTransaction(meterRegistry, "success")
        }
    } catch (cancellation: CancellationException) {
        started?.stopFailedTransaction(meterRegistry, "cancelled", cancellation)
        throw cancellation
    } catch (failure: Error) {
        started?.stopFailedTransaction(meterRegistry, "error", failure)
        throw failure
    } catch (failure: Exception) {
        started?.stopFailedTransaction(meterRegistry, "error", failure)
        throw ExposedKtorTransactionException().also { it.initCause(failure) }
    }
}

/** 실패 경로의 metric 기록은 원래 취소·DB 예외를 대체하지 않는다. */
@Suppress("TooGenericExceptionCaught")
private fun Timer.Sample.stopFailedTransaction(
    registry: MeterRegistry?,
    outcome: String,
    primary: Throwable,
) {
    try {
        stopTransaction(registry, outcome)
    } catch (metricFailure: Throwable) {
        if (metricFailure !== primary) {
            primary.addSuppressed(metricFailure)
        }
    }
}

private fun Timer.Sample.stopTransaction(
    registry: MeterRegistry?,
    outcome: String,
) {
    registry ?: return
    stop(
        Timer.builder(CORE_TRANSACTION_METER_NAME)
            .tag("backend", ExposedKtorReadinessBackend.R2DBC.name.lowercase())
            .tag("operation", "transaction")
            .tag("outcome", outcome)
            .tag("component", "r2dbc")
            .description("Exposed Ktor transaction duration.")
            .register(registry),
    )
}

private const val CORE_TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.core.transaction"
