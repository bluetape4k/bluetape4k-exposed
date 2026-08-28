package io.bluetape4k.exposed.r2dbc.redisson.map

import io.bluetape4k.exposed.r2dbc.tests.TestDB
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/**
 * 테스트가 생성한 loader의 producer/transaction lifecycle을 항상 정리합니다.
 *
 * `withTables`가 테이블을 삭제하기 전에 loader의 child job이 끝나야 다음 테스트가
 * 공유 H2 데이터베이스를 안전하게 사용할 수 있습니다.
 */
internal suspend inline fun <ID: Any, E: Any, T> R2dbcEntityMapLoader<ID, E>.useLoader(
    block: suspend () -> T,
): T = try {
    block()
} finally {
    closeAndJoin()
}

/**
 * detached producer가 parameterless `suspendTransaction`을 사용할 때 fixture DB를 고정합니다.
 *
 * loader의 기본 coroutine scope는 호출자의 transaction context를 상속하지 않으므로,
 * [testDB]의 DB를 전역 default로 임시 설정하고 producer 종료까지 그 범위를 유지합니다.
 * 종료 후에는 이전 default를 복원해 다른 dialect fixture에 상태를 누출하지 않습니다.
 */
internal suspend inline fun <ID: Any, E: Any, T> R2dbcEntityMapLoader<ID, E>.useLoader(
    testDB: TestDB,
    block: suspend () -> T,
): T {
    val previousDefault = TransactionManager.defaultDatabase
    TransactionManager.defaultDatabase = checkNotNull(testDB.db) {
        "testDB.db must be initialized for $testDB"
    }
    return try {
        try {
            block()
        } finally {
            closeAndJoin()
        }
    } catch (cause: kotlinx.coroutines.CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        cause.addSuppressed(
            IllegalStateException("R2DBC loader fixture database=${testDB.name}"),
        )
        throw cause
    } finally {
        TransactionManager.defaultDatabase = previousDefault
    }
}
