package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.TimeUnit

/**
 * 호출자가 제공한 dispatcher에서 blocking Exposed JDBC transaction을 실행합니다.
 * dispatcher와 database lifecycle은 호출자가 계속 소유합니다.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
suspend fun <T> ApplicationCall.exposedJdbcTransaction(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    meterRegistry: MeterRegistry? = null,
    block: JdbcTransaction.() -> T,
): T {
    val started = meterRegistry?.let(Timer::start)
    val result = try {
        runInterruptible(blockingDispatcher) {
            transaction(db = db) { block() }
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
    started?.stopSuccessfulTransaction(meterRegistry)
    return result
}

/**
 * 성공 후 metric 기록의 일반 [Exception]은 이미 commit된 transaction 결과를 변경하지 않는다.
 * JVM [Error]는 복구 불가능한 fatal 신호로 간주하여 전파한다.
 */
@Suppress("TooGenericExceptionCaught")
private fun Timer.Sample.stopSuccessfulTransaction(registry: MeterRegistry?) {
    try {
        stopTransaction(registry, "success")
    } catch (metricFailure: Exception) {
        TransactionMetricLog.log.warn(metricFailure) {
            "Exposed Ktor transaction metric recording failed after a successful transaction. " +
                "backend=jdbc, exceptionType=${metricFailure::class.qualifiedName}"
        }
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
            .tag("backend", ExposedKtorReadinessBackend.JDBC.name.lowercase())
            .tag("operation", "transaction")
            .tag("outcome", outcome)
            .tag("component", "jdbc")
            .description("Exposed Ktor transaction duration.")
            .register(registry),
    )
}

private object TransactionMetricLog : KLogging()

private const val CORE_TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.core.transaction"
