package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Statement
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Exposed `1.5.0` R2DBC transaction cleanup 실패의 호출자 전달 계약을 검증한다. */
class R2dbcTransactionCleanupFailureTest {

    enum class CleanupBoundary { ROLLBACK, CONNECTION_CLOSE }

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
        val database = instrumentedDatabase(testDB, boundary, events, primary, cleanup)

        try {
            val observed = assertFailsWith<PrimaryFailure> {
                suspendTransaction(database) { exec("SELECT 1") }
            }

            observed shouldBeSameInstanceAs primary
            observed.suppressed.shouldBeEmpty()
            events.count { it == "statement-execute" } shouldBeEqualTo 1
            events.count { it == "rollback" } shouldBeEqualTo 1
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
    ): R2dbcDatabase {
        val delegate = ConnectionFactories.get(testDB.connection())
        val factory = object : ConnectionFactory {
            override fun getMetadata() = delegate.metadata

            override fun create(): Publisher<out Connection> = Flux.from(delegate.create()).map { connection ->
                proxy<Connection>(connection) { method, args ->
                    when (method.name) {
                        "createStatement" -> {
                            val statement = invoke(connection, method, args) as Statement
                            proxy<Statement>(statement) { statementMethod, statementArgs ->
                                if (statementMethod.name == "execute") {
                                    events.add("statement-execute")
                                    Flux.error<io.r2dbc.spi.Result>(primary)
                                } else {
                                    invoke(statement, statementMethod, statementArgs)
                                }
                            }
                        }
                        "rollbackTransaction" -> {
                            events.add("rollback")
                            @Suppress("UNCHECKED_CAST")
                            val publisher = invoke(connection, method, args) as Publisher<Void>
                            val completed = Mono.from(publisher)
                            if (boundary == CleanupBoundary.ROLLBACK) {
                                completed.then(cleanupFailure(events, cleanup))
                            } else {
                                completed
                            }
                        }
                        "close" -> {
                            events.add("connection-close")
                            @Suppress("UNCHECKED_CAST")
                            val publisher = invoke(connection, method, args) as Publisher<Void>
                            val completed = Mono.from(publisher)
                            if (boundary == CleanupBoundary.CONNECTION_CLOSE) {
                                completed.then(cleanupFailure(events, cleanup))
                            } else {
                                completed
                            }
                        }
                        else -> invoke(connection, method, args)
                    }
                }
            }
        }
        return R2dbcDatabase.connect(factory, R2dbcDatabaseConfig.Builder().apply {
            setUrl(testDB.connection())
            explicitDialect = if (testDB == TestDB.H2) H2Dialect() else PostgreSQLDialect()
            defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
            defaultMaxAttempts = 1
        })
    }

    private fun cleanupFailure(events: MutableList<String>, cleanup: Throwable): Mono<Void> = Mono.defer {
        events.add("cleanup-failure")
        Mono.error(cleanup)
    }

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
