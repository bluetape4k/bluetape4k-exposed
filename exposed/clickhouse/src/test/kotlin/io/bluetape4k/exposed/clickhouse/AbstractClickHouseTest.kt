package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.database.ClickHouseServer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.time.Duration.Companion.seconds

/**
 * ClickHouse 컨테이너를 공유하는 통합 테스트의 기반 클래스.
 *
 * - [Execution]은 [ExecutionMode.SAME_THREAD]로 고정되어 있어, 동일 컨테이너에 대한
 *   동시 접근으로 인한 비결정성을 줄입니다.
 * - [BeforeAll]에서 단순 SELECT 쿼리를 통해 컨테이너 ready 상태를 polling 합니다.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("clickhouse-v2")
abstract class AbstractClickHouseTest {

    companion object: KLogging() {
        val clickhouse: ClickHouseServer by lazy { ClickHouseServer.Launcher.clickhouse }

        val db: Database by lazy {
            ClickHouseDatabase.connect(
                host = clickhouse.host,
                port = clickhouse.port,
                database = "default",
                user = clickhouse.username ?: "test",
                password = clickhouse.password ?: "test",
            )
        }
    }

    @BeforeAll
    fun waitForClickHouseReady() {
        var attempts = 0
        await atMost 10.seconds withPollInterval 1.seconds until {
            runCatching {
                transaction(db) {
                    exec("SELECT 1") { rs ->
                        rs.next();
                        rs.getInt(1)
                    }
                }
            }.onFailure {
                log.warn { "ClickHouse not ready attempt=${attempts++}" }
            }.isSuccess
        }
    }
}
