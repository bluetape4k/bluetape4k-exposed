package io.bluetape4k.exposed.ktor.jdbc

import io.bluetape4k.exposed.ktor.core.ExposedKtorReadinessBackend
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
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
    return try {
        runInterruptible(blockingDispatcher) {
            transaction(db = db) { block() }
        }.also {
            started?.stopTransaction(meterRegistry, "success")
        }
    } catch (cancellation: CancellationException) {
        started?.stopTransaction(meterRegistry, "cancelled")
        throw cancellation
    } catch (failure: Error) {
        started?.stopTransaction(meterRegistry, "error")
        throw failure
    } catch (failure: Exception) {
        started?.stopTransaction(meterRegistry, "error")
        throw ExposedKtorTransactionException().also { it.initCause(failure) }
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

private const val CORE_TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.core.transaction"
