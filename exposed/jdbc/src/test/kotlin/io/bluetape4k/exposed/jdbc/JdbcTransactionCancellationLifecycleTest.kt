package io.bluetape4k.exposed.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 실제 JDBC 자원을 유지한 채 acquisition·begin·statement 경계에서 Job을 취소한다. */
class JdbcTransactionCancellationLifecycleTest {

    enum class Boundary { ACQUIRED, BEGIN, STATEMENT, BEGIN_FAILURE }

    companion object {
        @JvmStatic
        fun cases() = TestDB.enabledDialects()
            .filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
            .flatMap { db -> Boundary.entries.map { Arguments.of(db, it) } }
    }

    // stacktrace recovery의 복사를 배제하고 transaction이 전달한 원인을 관측한다.
    private class BoundaryCancellation(val boundary: Boundary) : CancellationException(boundary.name)
    private class BeginFailure(val boundary: Boundary) : IllegalStateException(boundary.name)

    @ParameterizedTest
    @MethodSource("cases")
    fun `취소와 begin 실패에서 획득한 자원을 정확히 한번 정리한다`(testDB: TestDB, boundary: Boundary) = runSuspendIO {
        val previousDatabase = TransactionManager.defaultDatabase
        val events = CopyOnWriteArrayList<String>()
        val reached = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val cancellation = BoundaryCancellation(boundary)
        val beginFailure = BeginFailure(boundary)
        // JDBC의 동기 경계를 고정하므로 stress tester 대신 bounded latch와 실제 IO Job을 사용한다.
        fun pause(at: Boundary) {
            if (boundary == at) {
                reached.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "JDBC boundary was not released" }
            }
        }
        Class.forName(testDB.driver)
        val database = instrumentedDatabase(testDB, boundary, events, beginFailure, ::pause)
        try {
            withTimeout(15_000) {
                coroutineScope {
                    val observed = CompletableDeferred<Throwable>()
                    val job = launch {
                        try {
                            suspendTransaction(database) {
                                connection
                                currentCoroutineContext().ensureActive()
                                exec("SELECT 1")
                                // JDBC 자체가 coroutine 취소로 SQL을 중단한다고 가정하지 않는다.
                                currentCoroutineContext().ensureActive()
                            }
                            observed.complete(AssertionError("transaction unexpectedly completed"))
                        } catch (cause: Throwable) {
                            observed.complete(cause)
                            reached.completeExceptionally(cause)
                        }
                    }
                    if (boundary != Boundary.BEGIN_FAILURE) {
                        try {
                            reached.await()
                            job.cancel(cancellation)
                        } finally {
                            release.countDown()
                        }
                    }
                    job.join()
                    val expected = if (boundary == Boundary.BEGIN_FAILURE) beginFailure else cancellation
                    observed.await() shouldBeEqualTo expected
                }
            }
            events.count { it == "acquire" } shouldBeEqualTo 1
            events.count { it == "close" } shouldBeEqualTo 1
            events.count { it == "commit" } shouldBeEqualTo 0
            events.count { it == "rollback" } shouldBeEqualTo if (boundary == Boundary.BEGIN_FAILURE) 0 else 1
            events.count { it == "statement" } shouldBeEqualTo if (boundary == Boundary.STATEMENT) 1 else 0
            events.count { it == "statement-close" } shouldBeEqualTo if (boundary == Boundary.STATEMENT) 1 else 0
            if (boundary == Boundary.STATEMENT) {
                check(events.indexOf("statement-close") < events.indexOf("close"))
            }
            if (boundary != Boundary.BEGIN_FAILURE) {
                check(events.indexOf("rollback") < events.indexOf("close"))
            }
            events.last() shouldBeEqualTo "close"
        } finally {
            release.countDown()
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previousDatabase
        }
    }


    private fun instrumentedDatabase(
        testDB: TestDB,
        boundary: Boundary,
        events: MutableList<String>,
        beginFailure: Throwable,
        pause: (Boundary) -> Unit,
    ): Database = Database.connect(
        getNewConnection = {
            val connection = DriverManager.getConnection(testDB.connection(), testDB.user, testDB.pass)
            events.add("acquire")
            try {
                pause(Boundary.ACQUIRED)
            } catch (failure: Throwable) {
                // Exposed에 넘기기 전 실패한 자원은 fixture가 소유한다.
                connection.close()
                throw failure
            }
            proxy<Connection>(connection) { method, args ->
                when (method.name) {
                    "setAutoCommit" -> {
                        if (args?.firstOrNull() == false) {
                            events.add("begin")
                            if (boundary == Boundary.BEGIN_FAILURE) throw beginFailure
                            pause(Boundary.BEGIN)
                        }
                        invoke(connection, method, args)
                    }
                    "prepareStatement" -> {
                        val statement = invoke(connection, method, args) as PreparedStatement
                        events.add("statement")
                        proxy<PreparedStatement>(statement) { statementMethod, statementArgs ->
                            if (statementMethod.name == "close") events.add("statement-close")
                            val result = invoke(statement, statementMethod, statementArgs)
                            if (statementMethod.name == "executeQuery") pause(Boundary.STATEMENT)
                            result
                        }
                    }
                    "rollback", "commit", "close" -> {
                        events.add(method.name)
                        invoke(connection, method, args)
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
