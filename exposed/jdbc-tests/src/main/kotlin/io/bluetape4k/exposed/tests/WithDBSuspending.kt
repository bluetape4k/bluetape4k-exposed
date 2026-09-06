package io.bluetape4k.exposed.tests

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.coroutines.CoroutineContext

/**
 * [withDb]와 같은 enum별 fixture에서 suspend 트랜잭션을 실행합니다.
 * 취소 가능한 permit 대기와 일시 wrapper 정리를 보장하며 본문은 재시도하지 않습니다.
 * permit 대기와 연결 생성은 IO dispatcher에서, 본문은 [context]의 dispatcher에서 수행합니다.
 */
suspend fun withDbSuspending(
    testDB: TestDB,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend JdbcTransaction.(TestDB) -> Unit,
) = jdbcFixtureFor(testDB).executeSuspending(context, configure, statement)

/**
 * custom key의 [fixture]로 suspend 트랜잭션을 실행합니다.
 * 같은 fixture의 blocking 호출과 permit을 공유하며 활성 중첩 호출은 거부합니다.
 * 필수 정리를 제외하고 호출자의 취소를 유지합니다.
 */
suspend fun <K> withDbSuspending(
    fixture: JdbcTestDbFixture<K>,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend JdbcTransaction.(K) -> Unit,
) = fixture.executeSuspending(context, configure, statement)

@Deprecated(
    message = "Use withDbSuspending() instead.",
    replaceWith = ReplaceWith(
        "withDbSuspending(testDB, context, configure, statement)",
        "io.bluetape4k.exposed.tests.withDbSuspending"
    )
)
/** [withDbSuspending]의 기존 바이너리 호환 별칭입니다. */
suspend fun withSuspendedDb(
    testDB: TestDB,
    context: CoroutineContext? = Dispatchers.IO,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend JdbcTransaction.(TestDB) -> Unit,
) = withDbSuspending(testDB, context, configure, statement)
