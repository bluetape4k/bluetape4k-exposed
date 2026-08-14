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

/**
 * 호출자가 제공한 dispatcher에서 blocking Exposed JDBC transaction을 실행합니다.
 *
 * transaction receiver의 `queryTimeout`은 호출자가 만든 [Database]의
 * `DatabaseConfig.defaultQueryTimeout`을 초 단위로 상속합니다. Block 안에서
 * receiver의 `queryTimeout`을 설정하면 해당 transaction에만 적용되는 override가
 * 우선합니다. Database, pool과 dispatcher의 생성·종료 lifecycle은 호출자가
 * 소유하며, driver가 statement timeout을 지원하지 않으면 해당 제한은 적용되지
 * 않을 수 있습니다.
 */
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

/**
 * coroutine-native Exposed R2DBC transaction을 실행합니다.
 *
 * transaction receiver의 `queryTimeout`은 호출자가 만든 [R2dbcDatabase]의
 * `R2dbcDatabaseConfig.defaultQueryTimeout`을 초 단위로 상속합니다. Block 안에서
 * receiver의 `queryTimeout`을 설정하면 해당 transaction에만 적용되는 override가
 * 우선합니다. Database, pool과 dispatcher의 생성·종료 lifecycle은 호출자가
 * 소유하며, driver가 statement timeout을 지원하지 않으면 해당 제한은 적용되지
 * 않을 수 있습니다.
 */
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
