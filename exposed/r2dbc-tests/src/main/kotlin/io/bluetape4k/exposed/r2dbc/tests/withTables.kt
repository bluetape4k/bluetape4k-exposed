// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.r2dbc.tests

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import java.util.concurrent.CancellationException

/** enum fixture의 테이블을 생성·정리합니다. 본문 취소에는 cleanup suppressed를 추가하지 않습니다. */
suspend fun withTables(
    testDB: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    dropTables: Boolean = true,
    statement: suspend R2dbcTransaction.(TestDB) -> Unit,
) = withTables(r2dbcFixtureFor(testDB), *tables, configure = configure, dropTables = dropTables, statement = statement)

/**
 * 호출자가 소유한 요청 테이블을 생성하고 필수 cleanup을 수행합니다.
 * 부분 생성 실패도 정리하되 [dropTables]가 false이면 종료 정리를 생략합니다.
 * 일반 본문 실패에는 drop/recovery 실패를 순서대로 추가하고 본문 취소의 suppressed는 유지합니다.
 */
suspend fun <K> withTables(
    fixture: R2dbcTestDbFixture<K>,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    dropTables: Boolean = true,
    statement: suspend R2dbcTransaction.(K) -> Unit,
) {
    withDb(fixture, configure) { key ->
        r2dbcPreDrop { SchemaUtils.drop(*tables) }
        var failure: Throwable? = null
        try {
            if (tables.isNotEmpty()) SchemaUtils.create(*tables)
            statement(key)
            commit()
        } catch (thrown: CancellationException) {
            failure = thrown
            throw thrown
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            if (dropTables && tables.isNotEmpty()) {
                cleanupR2dbcFixture(failure, recover = true, suppressOnCancellation = false) {
                    SchemaUtils.drop(*tables)
                }
            }
        }
    }
}
