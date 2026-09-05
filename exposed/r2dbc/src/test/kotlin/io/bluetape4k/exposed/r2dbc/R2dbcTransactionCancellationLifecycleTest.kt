package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Statement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
import reactor.core.publisher.Sinks
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** 실제 연결과 제어 가능한 Publisher로 취소 시 구독 해제와 정리 완료를 확인한다. */
class R2dbcTransactionCancellationLifecycleTest {

    enum class Boundary { ACQUISITION, BEGIN_PENDING, STATEMENT_PENDING, BEGIN_FAILURE }

    companion object {
        @JvmStatic
        fun cases() = TestDB.enabledDialects()
            .filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
            .flatMap { db -> Boundary.entries.map { Arguments.of(db, it) } }
    }

    // 추가 상태는 coroutine stacktrace recovery의 예외 복사를 배제한다.
    private class BoundaryCancellation(val boundary: Boundary) : CancellationException(boundary.name)
    private class BeginFailure(val boundary: Boundary) : IllegalStateException(boundary.name)

    @ParameterizedTest
    @MethodSource("cases")
    fun `취소된 Publisher를 해제하고 획득한 연결을 한번만 정리한다`(testDB: TestDB, boundary: Boundary) = runSuspendIO {
        val previousDatabase = TransactionManager.defaultDatabase
        val events = CopyOnWriteArrayList<String>()
        val reached = CompletableDeferred<Unit>()
        val cancellation = BoundaryCancellation(boundary)
        val beginFailure = BeginFailure(boundary)
        val acquisitionRelease = Sinks.empty<Void>()
        fun <T : Any> pending(): Mono<T> = Mono.never<T>()
            .doOnSubscribe { reached.complete(Unit) }
            .doOnCancel { events.add("cancel") }
            .timeout(Duration.ofSeconds(5))

        val delegate = ConnectionFactories.get(testDB.connection())
        val factory = object : ConnectionFactory {
            override fun getMetadata() = delegate.metadata

            override fun create(): Publisher<out Connection> =
                (if (boundary == Boundary.ACQUISITION) {
                    // Exposed의 acquisition은 NonCancellable이다. 취소 요청 뒤 연결을 전달해 정리를 관측한다.
                    acquisitionRelease.asMono().timeout(Duration.ofSeconds(5))
                        .doOnSubscribe { reached.complete(Unit) }
                        .thenMany(Flux.from(delegate.create()))
                } else Flux.from(delegate.create())).map { connection ->
                    events.add("acquire")
                    proxy<Connection>(connection) { method, args ->
                        when (method.name) {
                            "beginTransaction" -> when (boundary) {
                                Boundary.BEGIN_PENDING -> pending<Void>()
                                Boundary.BEGIN_FAILURE -> Mono.error<Void>(beginFailure)
                                else -> invoke(connection, method, args)
                            }
                            "createStatement" -> {
                                val statement = invoke(connection, method, args) as Statement
                                proxy<Statement>(statement) { statementMethod, statementArgs ->
                                    if (statementMethod.name == "execute") pending<io.r2dbc.spi.Result>()
                                    else invoke(statement, statementMethod, statementArgs)
                                }
                            }
                            "commitTransaction", "rollbackTransaction", "close" -> {
                                @Suppress("UNCHECKED_CAST")
                                val publisher = invoke(connection, method, args) as Publisher<Void>
                                Mono.from(publisher)
                                    .doOnSubscribe { events.add(method.name) }
                                    .doOnSuccess { events.add("${method.name}-done") }
                            }
                            else -> invoke(connection, method, args)
                        }
                    }
                }
        }
        val database = R2dbcDatabase.connect(factory, R2dbcDatabaseConfig.Builder().apply {
            setUrl(testDB.connection())
            explicitDialect = if (testDB == TestDB.H2) H2Dialect() else PostgreSQLDialect()
            defaultR2dbcIsolationLevel = IsolationLevel.READ_COMMITTED
            defaultMaxAttempts = 1
        })
        try {
            // 단계별 구독 barrier가 필요하므로 stress tester 대신 실제 자식 Job을 취소한다.
            withTimeout(15_000) {
                coroutineScope {
                    val observed = CompletableDeferred<Throwable>()
                    val job = launch {
                        try {
                            suspendTransaction(database) { exec("SELECT 1") }
                            observed.complete(AssertionError("transaction unexpectedly completed"))
                        } catch (cause: Throwable) {
                            observed.complete(cause)
                            reached.completeExceptionally(cause)
                        }
                    }
                    if (boundary != Boundary.BEGIN_FAILURE) {
                        reached.await()
                        job.cancel(cancellation)
                        if (boundary == Boundary.ACQUISITION) acquisitionRelease.tryEmitEmpty()
                    }
                    job.join()
                    val expected = if (boundary == Boundary.BEGIN_FAILURE) beginFailure else cancellation
                    observed.await() shouldBeEqualTo expected
                }
            }
            events.count { it == "acquire" } shouldBeEqualTo 1
            events.count { it == "close" } shouldBeEqualTo 1
            events.count { it == "close-done" } shouldBeEqualTo 1
            events.count { it == "commitTransaction" } shouldBeEqualTo 0
            val rolledBack = if (boundary == Boundary.STATEMENT_PENDING) 1 else 0
            events.count { it == "rollbackTransaction" } shouldBeEqualTo rolledBack
            events.count { it == "rollbackTransaction-done" } shouldBeEqualTo rolledBack
            if (rolledBack == 1) {
                check(events.indexOf("rollbackTransaction-done") < events.indexOf("close"))
            }
            events.count { it == "cancel" } shouldBeEqualTo when (boundary) {
                Boundary.BEGIN_PENDING, Boundary.STATEMENT_PENDING -> 1
                else -> 0
            }
            events.last() shouldBeEqualTo "close-done"
        } finally {
            TransactionManager.closeAndUnregister(database)
            TransactionManager.defaultDatabase = previousDatabase
        }
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
