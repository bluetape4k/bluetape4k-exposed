package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.logging.KLogging
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

        @JvmStatic
        @BeforeAll
        fun waitForCockroachReady() {
            repeat(30) { attempt ->
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
                    if (attempt == 29) {
                        throw e
                    }
                    log.warn("CockroachDB not ready (attempt {}/30), waiting 1s...", attempt + 1)
                    Thread.sleep(1000L)
                }
            }
        }
    }
}
