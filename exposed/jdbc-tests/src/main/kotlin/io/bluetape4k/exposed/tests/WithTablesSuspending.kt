// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/** enum fixture에서 테이블을 생성하고 취소 후에도 요청 대상의 필수 정리를 수행합니다. */
suspend fun withTablesSuspending(
    testDB: TestDB,
    vararg tables: Table,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: suspend JdbcTransaction.(TestDB) -> Unit,
) = withTablesSuspending(
    jdbcFixtureFor(testDB), *tables,
    context = context, configure = configure, dropTables = dropTables, statement = statement,
)

/**
 * custom fixture에서 table lifecycle을 실행합니다.
 * 본문과 생성은 취소 가능하며 필수 cleanup만 NonCancellable로 실행합니다.
 * 원래 본문 예외/취소를 유지하고 직접 받은 cleanup 실패를 suppressed로 보존합니다.
 */
suspend fun <K> withTablesSuspending(
    fixture: JdbcTestDbFixture<K>,
    vararg tables: Table,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: suspend JdbcTransaction.(K) -> Unit,
) {
    withDbSuspending(fixture, context, configure) { key ->
        jdbcPreDrop { SchemaUtils.drop(*tables) }
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
                withContext(NonCancellable + Dispatchers.IO) {
                    cleanupJdbcFixture(failure, recover = true) { SchemaUtils.drop(*tables) }
                }
            }
        }
    }
}

@Deprecated(
    message = "Use withTablesSuspending() instead.",
    replaceWith = ReplaceWith(
        "withTablesSuspending(testDB, *tables, context = context, " +
                "configure = configure, dropTables = dropTables, statement = statement)",
        "io.bluetape4k.exposed.tests.withTablesSuspending"
    )
)
/** [withTablesSuspending]의 기존 바이너리 호환 별칭입니다. */
suspend fun withSuspendedTables(
    testDB: TestDB,
    vararg tables: Table,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = {},
    dropTables: Boolean = true,
    statement: suspend JdbcTransaction.(TestDB) -> Unit,
) = withTablesSuspending(
    testDB, *tables,
    context = context, configure = configure, dropTables = dropTables, statement = statement,
)
