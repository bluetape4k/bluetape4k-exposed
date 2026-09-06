// 호출자 callback의 Error까지 원형대로 전파하고 cleanup 실패가 덮지 않도록 포착한다.
@file:Suppress("TooGenericExceptionCaught")

package io.bluetape4k.exposed.r2dbc.tests

import io.bluetape4k.logging.info
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.Runtimex
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import java.util.concurrent.CancellationException

/**
 * caller-owned 연결 공급원을 재사용하는 R2DBC 테스트 fixture입니다.
 * 같은 물리 DB에는 같은 fixture를 공유해야 하며 JVM 수명으로 유지합니다.
 * callback은 외부 pool/container를 새로 소유하지 않는 Exposed wrapper를 반환해야 합니다.
 * [database]는 기본 wrapper이며 초기화와 종료 hook 등록 전에는 null입니다.
 */
class R2dbcTestDbFixture<K> internal constructor(
    val key: K,
    private val createDatabase: suspend (DatabaseConfig.Builder.() -> Unit) -> R2dbcDatabase,
    private val onShutdown: () -> Unit,
    private val registerShutdown: (() -> Unit) -> Unit,
) {
    companion object: KLogging()

    @Volatile
    var database: R2dbcDatabase? = null
        private set

    internal val semaphore = Semaphore(1)
    internal var legacy: TestDB? = null

    internal suspend fun initialize(): R2dbcDatabase {
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
        log.info { "R2DBC fixture initialized" }
        return created
    }

    internal suspend fun configured(configure: (DatabaseConfig.Builder.() -> Unit)?): R2dbcDatabase {
        val baseline = initialize()
        if (configure == null) return baseline
        val temporary = createDatabase(configure)
        require(temporary !== baseline) { "Temporary R2DBC wrapper must differ from baseline" }
        return temporary
    }

    internal fun unregisterTemporary(selected: R2dbcDatabase, failure: Throwable?) {
        if (selected === database) return
        try {
            TransactionManager.closeAndUnregister(selected)
        } catch (cleanup: CancellationException) {
            if (failure == null) throw cleanup
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
    }
}

/**
 * custom [key]로 R2DBC fixture를 생성합니다. 생성 callback은 suspend를 지원합니다.
 * [createDatabase]는 전달받은 구성을 wrapper 생성 시 한 번 적용합니다.
 * 최초 성공한 초기화에서 [onShutdown]을 JVM 종료 hook에 등록하며 외부 pool은 닫지 않습니다.
 */
fun <K> r2dbcTestDbFixture(
    key: K,
    createDatabase: suspend (DatabaseConfig.Builder.() -> Unit) -> R2dbcDatabase,
    onShutdown: () -> Unit = {},
): R2dbcTestDbFixture<K> = R2dbcTestDbFixture(key, createDatabase, onShutdown) { callback ->
    Runtimex.addShutdownHook { callback() }
}
