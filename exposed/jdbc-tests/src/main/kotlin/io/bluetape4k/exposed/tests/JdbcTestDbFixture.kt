// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.tests

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.concurrent.Semaphore

/**
 * 호출자가 소유한 연결 공급원을 재사용하는 JDBC 테스트 fixture입니다.
 *
 * 같은 물리 DB의 호출은 같은 fixture를 공유해야 합니다. 생성 callback은 pool/container를
 * 새로 소유하지 않는 Exposed wrapper를 반환해야 합니다. fixture의 수명은 테스트 JVM입니다.
 * [database]는 초기화와 종료 hook 등록에 성공한 기본 wrapper이며 첫 호출 전에는 null입니다.
 */
class JdbcTestDbFixture<K> internal constructor(
    val key: K,
    private val createDatabase: (DatabaseConfig.Builder.() -> Unit) -> Database,
    private val onShutdown: () -> Unit,
    private val registerShutdown: (() -> Unit) -> Unit,
) {

    companion object: KLogging()

    @Volatile
    var database: Database? = null
        private set

    internal val semaphore = Semaphore(1, true)
    internal var legacy: TestDB? = null

    internal fun initialize(): Database {
        database?.let { return it }
        val created = createDatabase({})
        try {
            registerShutdown(onShutdown)
        } catch (failure: Throwable) {
            try {
                TransactionManager.closeAndUnregister(created)
            } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            throw failure
        }
        database = created
        log.info { "JDBC fixture initialized" }
        return created
    }

    internal fun configured(configure: (DatabaseConfig.Builder.() -> Unit)?): Database {
        val baseline = initialize()
        if (configure == null) return baseline
        val temporary = createDatabase(configure)
        require(temporary !== baseline) { "Temporary JDBC wrapper must differ from baseline" }
        return temporary
    }

    internal fun unregisterTemporary(selected: Database, failure: Throwable?) {
        if (selected === database) return
        try {
            TransactionManager.closeAndUnregister(selected)
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    }
}

/**
 * custom [key]와 caller-owned 연결 공급원으로 테스트 fixture를 만듭니다.
 * [createDatabase]는 전달받은 구성 callback을 생성할 wrapper에 한 번 적용합니다.
 * [onShutdown]은 최초 초기화 성공 시 JVM 종료 hook에 한 번 등록됩니다.
 * provider는 외부 pool/container를 닫지 않습니다.
 */
fun <K> jdbcTestDbFixture(
    key: K,
    createDatabase: (DatabaseConfig.Builder.() -> Unit) -> Database,
    onShutdown: () -> Unit = {},
): JdbcTestDbFixture<K> = JdbcTestDbFixture(key, createDatabase, onShutdown) { callback ->
    Runtimex.addShutdownHook { callback() }
}
