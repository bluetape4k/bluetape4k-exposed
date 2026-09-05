package io.bluetape4k.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.CopyOnWriteArrayList

/** Exposed `1.5.0` JDBC transaction cleanup 실패의 호출자 전달 계약을 검증한다. */
class JdbcTransactionCleanupFailureTest {

    enum class CleanupBoundary { ROLLBACK, STATEMENT_CLOSE, CONNECTION_CLOSE }

    companion object {
        @JvmStatic
        fun cases() = TestDB.enabledDialects()
            .filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
            .flatMap { db -> CleanupBoundary.entries.map { Arguments.of(db, it) } }
    }

    private class PrimaryFailure(val boundary: CleanupBoundary) : IllegalStateException(boundary.name)
    private class CleanupFailure(val boundary: CleanupBoundary) : IllegalStateException(boundary.name)

    @ParameterizedTest
    @MethodSource("cases")
    fun `Exposed 1_5_0 cleanup 실패는 주원인을 유지하지만 호출자에게 전달하지 않는다`(
        testDB: TestDB,
        boundary: CleanupBoundary,
    ) = runSuspendIO {
        val previousDatabase = TransactionManager.defaultDatabase
        val events = CopyOnWriteArrayList<String>()
        val primary = PrimaryFailure(boundary)
        val cleanup = CleanupFailure(boundary)
        Class.forName(testDB.driver)
        val database = instrumentedDatabase(testDB, boundary, events, primary, cleanup)

        try {
            val observed = assertFailsWith<PrimaryFailure> {
                suspendTransaction(database) { exec("SELECT 1") }
            }

            observed shouldBeSameInstanceAs primary
            observed.suppressed.shouldBeEmpty()
            events.count { it == "statement-execute" } shouldBeEqualTo 1
            events.count { it == "rollback" } shouldBeEqualTo 1
            events.count { it == "statement-close" } shouldBeEqualTo 1
            events.count { it == "connection-close" } shouldBeEqualTo 1
            events.count { it == "cleanup-failure" } shouldBeEqualTo 1
        } finally {
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previousDatabase
        }
    }

    private fun instrumentedDatabase(
        testDB: TestDB,
        boundary: CleanupBoundary,
        events: MutableList<String>,
        primary: Throwable,
        cleanup: Throwable,
    ): Database = Database.connect(
        getNewConnection = {
            val connection = DriverManager.getConnection(testDB.connection(), testDB.user, testDB.pass)
            proxy<Connection>(connection) { method, args ->
                when (method.name) {
                    "prepareStatement" -> {
                        val statement = invoke(connection, method, args) as PreparedStatement
                        proxy<PreparedStatement>(statement) { statementMethod, statementArgs ->
                            when (statementMethod.name) {
                                "executeQuery" -> {
                                    events.add("statement-execute")
                                    invoke(statement, statementMethod, statementArgs)
                                    throw primary
                                }
                                "close" -> {
                                    events.add("statement-close")
                                    val result = invoke(statement, statementMethod, statementArgs)
                                    if (boundary == CleanupBoundary.STATEMENT_CLOSE) {
                                        events.add("cleanup-failure")
                                        throw cleanup
                                    }
                                    result
                                }
                                else -> invoke(statement, statementMethod, statementArgs)
                            }
                        }
                    }
                    "rollback" -> {
                        events.add("rollback")
                        val result = invoke(connection, method, args)
                        if (boundary == CleanupBoundary.ROLLBACK) {
                            events.add("cleanup-failure")
                            throw cleanup
                        }
                        result
                    }
                    "close" -> {
                        events.add("connection-close")
                        val result = invoke(connection, method, args)
                        if (boundary == CleanupBoundary.CONNECTION_CLOSE) {
                            events.add("cleanup-failure")
                            throw cleanup
                        }
                        result
                    }
                    else -> invoke(connection, method, args)
                }
            }
        },
        databaseConfig = DatabaseConfig {
            explicitDialect = if (testDB == TestDB.H2) H2Dialect() else PostgreSQLDialect()
            defaultMaxAttempts = 1
        },
    )

    private inline fun <reified T : Any> proxy(target: T, crossinline call: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(target.javaClass.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method, args)
        } as T

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (cause: InvocationTargetException) {
        throw cause.targetException
    }
}
