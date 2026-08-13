package io.bluetape4k.exposed.r2dbc.tests

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.transactionManager
import java.util.concurrent.CancellationException

/**
 * 테스트 실행 전후에 [tables]를 생성/삭제하고 [statement]를 실행합니다.
 *
 * [statement]에서 발생한 예외는 호출자에게 그대로 전파되어 테스트 실패로 반영됩니다.
 *
 * ## 동작/계약
 * - 실행 전 기존 테이블 드롭을 시도하고, 지정 테이블을 생성합니다.
 * - [dropTables]가 `true`면 종료 시 테이블 삭제를 시도합니다.
 * - 드롭 실패 시 top-level suspend transaction으로 한 번 더 정리합니다.
 * - statement가 취소되면 cleanup/recovery 실패를 suppressed로 추가하지 않고 취소를 보존합니다.
 * - 일반 statement 실패가 있으면 cleanup/recovery 실패를 suppressed로 추가합니다.
 *
 * ```kotlin
 * withTables(TestDB.H2, UtilityTable) {
 *     // 테스트 실행
 * }
 * // 기본값이면 종료 시 UtilityTable 정리
 * ```
 */
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
suspend fun withTables(
    testDB: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    dropTables: Boolean = true,
    statement: suspend R2dbcTransaction.(TestDB) -> Unit,
) {
    withDb(testDB, configure = configure) {
        try {
            SchemaUtils.drop(*tables)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Stale tables may not exist yet; their cleanup failure is safe to ignore.
        }

        if (tables.isNotEmpty()) {
            SchemaUtils.create(*tables)
        }

        var statementFailure: Throwable? = null
        try {
            statement(testDB)
            commit()  // Need commit to persist data before drop tables
        } catch (failure: CancellationException) {
            statementFailure = failure
            throw failure
        } catch (failure: Throwable) {
            statementFailure = failure
            throw failure
        } finally {
            if (dropTables) {
                cleanupTables(
                    testDB = testDB,
                    tables = tables,
                    statementFailure = statementFailure,
                )
            }
        }
    }
}

// Cleanup policy keeps cancellation, statement, and recovery failures distinct.
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
private suspend fun R2dbcTransaction.cleanupTables(
    testDB: TestDB,
    tables: Array<out Table>,
    statementFailure: Throwable?,
) {
    if (tables.isEmpty()) return

    val cancellationRequested = !currentCoroutineContext().isActive
    var cleanupFailure: Throwable? = null
    var recoveryFailure: Throwable? = null

    @Suppress("ThrowsCount")
    suspend fun cleanup() {
        try {
            SchemaUtils.drop(*tables)
            commit()
        } catch (failure: CancellationException) {
            if (statementFailure is CancellationException) throw statementFailure
            throw failure
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }

        if (cleanupFailure != null) {
            recoveryFailure = try {
                recoverTableCleanup(testDB, tables)
            } catch (failure: CancellationException) {
                if (statementFailure is CancellationException) throw statementFailure
                throw failure
            }
        }
    }

    if (cancellationRequested || statementFailure is CancellationException) {
        withContext(NonCancellable) {
            cleanup()
        }
    } else {
        cleanup()
    }

    if (statementFailure == null && cancellationRequested) {
        currentCoroutineContext().ensureActive()
    }

    when {
        cleanupFailure == null -> Unit
        statementFailure is CancellationException -> Unit
        statementFailure != null -> {
            statementFailure.addSuppressed(cleanupFailure)
            recoveryFailure?.let(statementFailure::addSuppressed)
        }
        else -> {
            recoveryFailure?.let(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun recoverTableCleanup(
    testDB: TestDB,
    tables: Array<out Table>,
): Throwable? {
    return try {
        val database = checkNotNull(testDB.db) { "testDB.db must be initialized for $testDB" }
        val defaultIsolationLevel = checkNotNull(database.transactionManager.defaultIsolationLevel) {
            "defaultIsolationLevel must be initialized for $testDB"
        }
        inTopLevelSuspendTransaction(
            transactionIsolation = defaultIsolationLevel,
            db = database,
        ) {
            maxAttempts = 1
            SchemaUtils.drop(*tables)
        }
        null
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        failure
    }
}
