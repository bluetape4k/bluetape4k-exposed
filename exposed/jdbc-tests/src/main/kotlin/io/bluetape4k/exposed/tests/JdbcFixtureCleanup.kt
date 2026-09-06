// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tests

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.util.concurrent.CancellationException

/** helper가 직접 받은 실패만 발생 순서대로 보존하며 자기 자신은 suppressed에 넣지 않는다. */
internal fun retainJdbcFailure(primary: Throwable?, cleanup: Throwable): Throwable {
    if (primary == null) return cleanup
    if (primary !== cleanup) primary.addSuppressed(cleanup)
    return primary
}

/** 요청 대상의 사전 정리만 시도한다. 취소는 누락된 테이블로 취급하지 않는다. */
internal fun jdbcPreDrop(drop: () -> Unit) {
    try {
        drop()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        // 사전 정리 실패는 생성 단계에서 실제 상태를 검증한다.
    }
}

/** 본문 실패를 유지하고 table cleanup 실패 시 별도 트랜잭션에서 한 번 복구한다. */
internal fun JdbcTransaction.cleanupJdbcFixture(
    primary: Throwable?,
    recover: Boolean,
    drop: JdbcTransaction.() -> Unit,
) {
    var result = primary
    try {
        drop()
        commit()
    } catch (cleanup: Throwable) {
        result = retainJdbcFailure(result, cleanup)
        if (recover) {
            try {
                val database = db
                inTopLevelTransaction(
                    db = database,
                    transactionIsolation = database.transactionManager.defaultIsolationLevel,
                ) {
                    maxAttempts = 1
                    drop()
                }
            } catch (recovery: Throwable) {
                result = retainJdbcFailure(result, recovery)
            }
        }
    }
    if (primary == null) result?.let { throw it }
}
