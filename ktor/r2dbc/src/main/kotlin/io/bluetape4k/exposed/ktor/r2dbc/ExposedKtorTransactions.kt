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

/** 호출자 소유 resource로 coroutine-native Exposed R2DBC transaction을 실행합니다. */
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
            .tag("backend", ExposedKtorReadinessBackend.R2DBC.name.lowercase())
            .tag("operation", "transaction")
            .tag("outcome", outcome)
            .tag("component", "r2dbc")
            .description("Exposed Ktor transaction duration.")
            .register(registry),
    )
}

private const val CORE_TRANSACTION_METER_NAME = "bluetape4k.exposed.ktor.core.transaction"
