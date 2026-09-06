// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.r2dbc.tests

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.transactionManager
import java.util.concurrent.CancellationException

/** 직접 받은 helper 실패만 순서대로 보존하며 자기 자신은 suppressed로 추가하지 않는다. */
internal fun retainR2dbcFailure(primary: Throwable?, cleanup: Throwable): Throwable {
    if (primary == null) return cleanup
    if (primary !== cleanup) primary.addSuppressed(cleanup)
    return primary
}

/** 요청 대상의 사전 정리 실패 중 취소만 전파한다. */
internal suspend fun r2dbcPreDrop(drop: suspend () -> Unit) {
    try {
        drop()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        // 사전 정리 실패는 뒤따르는 생성 단계에서 실제 상태를 검증한다.
    }
}

/** 취소 후 필수 정리만 보호하며 withTables의 기존 취소 예외 정책을 유지한다. */
internal suspend fun R2dbcTransaction.cleanupR2dbcFixture(
    primary: Throwable?,
    recover: Boolean,
    suppressOnCancellation: Boolean,
    drop: suspend R2dbcTransaction.() -> Unit,
) {
    var result = primary
    val retain = primary !is CancellationException || suppressOnCancellation
    withContext(NonCancellable) {
        var cleanupFailure: Throwable? = null
        try {
            drop()
            commit()
        } catch (failure: CancellationException) {
            cleanupFailure = failure
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }
        cleanupFailure?.let { cleanup ->
            if (retain) result = retainR2dbcFailure(result, cleanup)
            if (recover) {
                try {
                    val database = db
                    inTopLevelSuspendTransaction(
                        db = database,
                        transactionIsolation = checkNotNull(database.transactionManager.defaultIsolationLevel),
                    ) {
                        maxAttempts = 1
                        drop()
                    }
                } catch (failure: CancellationException) {
                    if (retain) result = retainR2dbcFailure(result, failure)
                } catch (failure: Throwable) {
                    if (retain) result = retainR2dbcFailure(result, failure)
                }
            }
        }
    }
    if (primary == null) result?.let { throw it }
}
