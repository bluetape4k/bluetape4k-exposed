package io.bluetape4k.exposed.cockroachdb

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.database.CockroachServer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.time.Duration.Companion.seconds

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
    }

    @BeforeAll
    fun waitForCockroachReady() {
        var attempt = 0
        await atMost 30.seconds withPollInterval 1.seconds until {
            runCatching {
                attempt++
                selectOne()
            }.onFailure { e ->
                log.warn(e) { "CockroachDB not ready (attempt ${attempt + 1}/$MAX_RETRY_COUNT), waiting 1s..." }
            }.isSuccess
        }
    }

    private fun selectOne(): Int? =
        transaction(db) {
            exec("SELECT 1") { rs ->
                rs.next()
                rs.getInt(1)
            }
        }.shouldNotBeNull()
}
