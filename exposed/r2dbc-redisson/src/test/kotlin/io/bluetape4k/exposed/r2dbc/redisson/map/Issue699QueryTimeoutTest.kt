package io.bluetape4k.exposed.r2dbc.redisson.map

import io.r2dbc.spi.R2dbcException
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withDb
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** PostgreSQL R2DBC loader의 statement timeout과 transaction cleanup을 검증합니다. */
class Issue699QueryTimeoutTest: AbstractExposedR2dbcTest() {

    @Test
    @EnabledIfEnvironmentVariable(
        named = "EXPOSED_ISSUE_699_STATEMENT_TIMEOUT_TEST",
        matches = "true",
    )
    fun `PostgreSQL R2DBC loader timeout은 AsyncIterator 원인을 전달하고 transaction을 정리한다`() = runSuspendIO {
        assumePostgreSQLNightly()
        withDb(TestDB.POSTGRESQL) {}
        val database = checkNotNull(TestDB.POSTGRESQL.db) { "PostgreSQL database must be initialized" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            withDefaultDatabase(database) {
                val loader = R2dbcEntityMapLoader<Long, Unit>(
                    loadByIdFromDB = { null },
                    loadAllIdsFromDB = {
                        TransactionManager.current().exec("SELECT pg_sleep(31)")
                    },
                    scope = scope,
                )
                try {
                    val failure = try {
                        loader.loadAllKeys().hasNext().toCompletableFuture().await()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        e
                    }

                    assertPostgreSQLStatementTimeout(failure)
                    check(TransactionManager.currentOrNull() == null) {
                        "loader timeout must close the current R2DBC transaction"
                    }
                } finally {
                    loader.closeAndJoin()
                }

                awaitLoaderChildren(scope)
            }
        } finally {
            scope.cancel()
        }

        withDb(TestDB.POSTGRESQL) {
            // 예외 없이 후속 transaction이 실행되면 timeout transaction context가 정리된 것이다.
            TransactionManager.current().exec("SELECT 1")
        }
    }

    private fun assumePostgreSQLNightly() {
        assumeTrue(TestDB.POSTGRESQL in TestDB.enabledDialects()) {
            "PostgreSQL statement-timeout evidence requires EXPOSED_TEST_DB=POSTGRESQL"
        }
    }

    private suspend fun awaitLoaderChildren(scope: CoroutineScope) {
        val children = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        children.joinAll()
        check(children.none { it.isActive }) {
            "loader producer must finish after statement timeout"
        }
    }

    private suspend fun <T> withDefaultDatabase(database: R2dbcDatabase, block: suspend () -> T): T {
        val previousDefault = TransactionManager.defaultDatabase
        TransactionManager.defaultDatabase = database
        return try {
            block()
        } finally {
            TransactionManager.defaultDatabase = previousDefault
        }
    }

    private fun assertPostgreSQLStatementTimeout(failure: Throwable?) {
        checkNotNull(failure) { "the 31-second PostgreSQL statement must be cancelled" }
        val chain = generateSequence(failure) { it.cause }.toList()
        val sqlState = chain.filterIsInstance<R2dbcException>().firstNotNullOfOrNull { it.sqlState }
        check(sqlState == "57014") {
            "unexpected PostgreSQL timeout failure: $failure"
        }
    }
}
