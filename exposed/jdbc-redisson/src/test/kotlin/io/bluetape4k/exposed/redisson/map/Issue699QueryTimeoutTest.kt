package io.bluetape4k.exposed.redisson.map

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withDb
import io.bluetape4k.exposed.tests.withDbSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.SQLException

/** PostgreSQL에서 loader statement timeout과 후속 transaction 성공을 검증합니다. */
class Issue699QueryTimeoutTest: AbstractExposedTest() {

    @Test
    @EnabledIfEnvironmentVariable(
        named = "EXPOSED_ISSUE_699_STATEMENT_TIMEOUT_TEST",
        matches = "true",
    )
    fun `PostgreSQL synchronous loader timeout은 원인을 전달하고 transaction을 정리한다`() = runSuspendIO {
        assumePostgreSQLNightly()
        withDb(TestDB.POSTGRESQL) {}
        val database = checkNotNull(TestDB.POSTGRESQL.db) { "PostgreSQL database must be initialized" }

        withDefaultDatabase(database) {
            val failure = runCatching {
                EntityMapLoader<Long, Unit>(
                    loadByIdFromDB = { null },
                    loadAllIdsFromDB = {
                        // synchronous loader는 기본 retry 정책을 사용하므로 timeout 후 재시도를 막습니다.
                        TransactionManager.current().maxAttempts = 1
                        TransactionManager.current().exec("SELECT pg_sleep(31)")
                        emptyList()
                    },
                ).loadAllKeys()
            }.exceptionOrNull()

            assertPostgreSQLStatementTimeout(failure)
            check(TransactionManager.currentOrNull() == null) {
                "loader timeout must close the current JDBC transaction"
            }
        }

        withDb(TestDB.POSTGRESQL) {
            TransactionManager.current().exec("SELECT 1") { resultSet ->
                resultSet.next() && resultSet.getInt(1) == 1
            }.shouldBeTrue()
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(
        named = "EXPOSED_ISSUE_699_STATEMENT_TIMEOUT_TEST",
        matches = "true",
    )
    fun `PostgreSQL suspended loader timeout은 AsyncIterator 원인을 전달하고 transaction을 정리한다`() = runSuspendIO {
        assumePostgreSQLNightly()
        withDbSuspending(TestDB.POSTGRESQL) {}
        val database = checkNotNull(TestDB.POSTGRESQL.db) { "PostgreSQL database must be initialized" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            withDefaultDatabase(database) {
                val failure = try {
                    SuspendedEntityMapLoader<Long, Unit>(
                        loadByIdFromDB = { null },
                        loadAllIdsFromDB = {
                            TransactionManager.current().exec("SELECT pg_sleep(31)")
                        },
                        scope = scope,
                    ).loadAllKeys().hasNext().await()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }

                assertPostgreSQLStatementTimeout(failure)
                awaitLoaderChildren(scope)
                check(TransactionManager.currentOrNull() == null) {
                    "loader timeout must close the current suspended JDBC transaction"
                }
            }
        } finally {
            scope.cancel()
        }

        withDbSuspending(TestDB.POSTGRESQL) {
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

    private suspend fun <T> withDefaultDatabase(database: Database, block: suspend () -> T): T {
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
        val sqlState = chain.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
        check(sqlState == "57014") {
            "unexpected PostgreSQL timeout failure: $failure"
        }
    }
}
