// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tests

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils

/** enum fixture의 요청 테이블을 생성·정리하며 본문 실패를 cleanup 실패보다 우선합니다. */
fun withTables(
    testDB: TestDB,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: JdbcTransaction.(TestDB) -> Unit,
) = withTables(jdbcFixtureFor(testDB), *tables, configure = configure, dropTables = dropTables, statement = statement)

/**
 * 호출자가 소유한 [tables]만 생성·정리합니다. 같은 물리 DB에는 같은 [fixture]를 공유합니다.
 * 부분 생성 실패에도 정리를 시도하며 [dropTables]가 false이면 종료 정리를 하지 않습니다.
 * 본문 실패는 유지하고 drop/recovery 실패를 발생 순서대로 suppressed에 추가합니다.
 */
fun <K> withTables(
    fixture: JdbcTestDbFixture<K>,
    vararg tables: Table,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: JdbcTransaction.(K) -> Unit,
) {
    withDb(fixture, configure) { key ->
        jdbcPreDrop { SchemaUtils.drop(*tables) }
        var failure: Throwable? = null
        try {
            if (tables.isNotEmpty()) SchemaUtils.create(*tables)
            statement(key)
            commit()
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            if (dropTables && tables.isNotEmpty()) {
                cleanupJdbcFixture(failure, recover = true) { SchemaUtils.drop(*tables) }
            }
        }
    }
}
