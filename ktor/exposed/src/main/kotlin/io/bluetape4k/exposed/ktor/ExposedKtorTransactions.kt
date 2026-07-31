package io.bluetape4k.exposed.ktor

import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** 호출자가 제공한 dispatcher에서 blocking Exposed JDBC transaction을 실행합니다. */
suspend fun <T> ApplicationCall.exposedJdbcTransaction(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    meterRegistry: MeterRegistry? = null,
    block: JdbcTransaction.() -> T,
): T =
    meterRegistry.recordExposedKtorTransaction(JDBC_BACKEND) {
        runJdbcTransaction(db, blockingDispatcher, block)
    }

private suspend fun <T> runJdbcTransaction(
    db: Database,
    blockingDispatcher: CoroutineDispatcher,
    block: JdbcTransaction.() -> T,
): T =
    try {
        withContext(blockingDispatcher) {
            transaction(db = db) {
                block()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ExposedKtorTransactionException(e)
    }

/** coroutine-native Exposed R2DBC transaction을 실행합니다. */
suspend fun <T> ApplicationCall.exposedR2dbcTransaction(
    db: R2dbcDatabase,
    meterRegistry: MeterRegistry? = null,
    block: suspend R2dbcTransaction.() -> T,
): T =
    try {
        suspendTransaction(db = db) {
            meterRegistry.recordExposedKtorTransaction(R2DBC_BACKEND) {
                block()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ExposedKtorTransactionException(e)
    }
