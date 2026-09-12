package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.database.CockroachServer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Shared CockroachDB container fixture for JDBC smoke tests.
 */
@Execution(ExecutionMode.SAME_THREAD)
abstract class AbstractCockroachDbTest {

    companion object: KLogging() {

        val cockroach: CockroachServer by lazy { CockroachServer.Launcher.cockroach }

        val db: Database by lazy {
            CockroachDatabase.connect(
                jdbcUrl = cockroach.url,
                user = cockroach.username ?: CockroachServer.USERNAME,
                password = cockroach.password ?: CockroachServer.PASSWORD,
            )
        }

        private const val MAX_RETRY_COUNT = 30

        @JvmStatic
        @BeforeAll
        fun waitForCockroachReady() {
            repeat(MAX_RETRY_COUNT) { attempt ->
                runCatching {
                    transaction(db) {
                        exec("SELECT 1") { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                }.onSuccess {
                    return
                }.onFailure { e ->
                    if (attempt == MAX_RETRY_COUNT - 1) {
                        throw e
                    }
                    log.warn(e) { "CockroachDB not ready (attempt ${attempt + 1}/$MAX_RETRY_COUNT), waiting 1s..." }
                    Thread.sleep(1000L)
                }
            }
        }
    }
}
