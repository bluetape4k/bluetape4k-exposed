package io.bluetape4k.exposed.r2dbc.tests

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.transactions.nullableTransactionScope
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

internal var currentTestDB by nullableTransactionScope<TestDB>()

/**
 * enum별 fixture에서 트랜잭션을 한 번 실행합니다.
 * FIFO 대기 중 취소를 유지하며 첫 configure도 별도 일시 wrapper에만 적용합니다.
 * 같은 fixture의 활성 중첩 호출은 거부합니다.
 */
suspend fun withDb(
    testDB: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend R2dbcTransaction.(TestDB) -> Unit,
) = r2dbcFixtureFor(testDB).executeSuspending(configure, statement)

/**
 * 같은 [fixture] 호출을 직렬화하고 트랜잭션 receiver와 custom key를 전달합니다.
 * [configure]는 현재 호출의 일시 wrapper에만 적용하며 종료 후 등록을 해제합니다.
 * 같은 물리 DB에는 같은 fixture 인스턴스를 공유해야 합니다.
 */
suspend fun <K> withDb(
    fixture: R2dbcTestDbFixture<K>,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: suspend R2dbcTransaction.(K) -> Unit,
) = fixture.executeSuspending(configure, statement)
