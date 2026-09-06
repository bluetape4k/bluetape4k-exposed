package io.bluetape4k.exposed.tests

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.transactions.nullableTransactionScope
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

// 기존 JVM 진입점을 보존한다. 실행 상태는 fixture가 소유한다.
internal val registeredOnShutdown = ConcurrentHashMap.newKeySet<TestDB>()
internal val testDbSemaphores = ConcurrentHashMap<TestDB, Semaphore>()

/** 현재 legacy enum 트랜잭션의 DB 식별자입니다. */
var currentTestDB by nullableTransactionScope<TestDB>()

/** 기존 enum 식별자를 commit 후에도 보존하는 호환 interceptor입니다. */
object CurrentTestDBInterceptor: StatementInterceptor {
    override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
        userData.filterValues { it is TestDB }
}

/**
 * enum별 fixture를 공유하여 트랜잭션을 직렬 실행합니다.
 * [configure]는 첫 호출에서도 별도 일시 wrapper에만 적용합니다.
 * 같은 fixture의 중첩 실행은 거부하며 본문은 재시도하지 않습니다.
 */
fun withDb(
    testDB: TestDB,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: JdbcTransaction.(TestDB) -> Unit,
) = jdbcFixtureFor(testDB).executeBlocking(configure, statement)

/**
 * 같은 [fixture] 호출을 직렬화하고 트랜잭션 receiver와 custom key를 본문에 전달합니다.
 * [configure]는 현재 호출의 별도 wrapper에만 적용하며 종료 후 등록을 해제합니다.
 * 동일한 물리 DB에는 같은 fixture 인스턴스를 공유해야 합니다.
 */
fun <K> withDb(
    fixture: JdbcTestDbFixture<K>,
    configure: (DatabaseConfig.Builder.() -> Unit)? = null,
    statement: JdbcTransaction.(K) -> Unit,
) = fixture.executeBlocking(configure, statement)
