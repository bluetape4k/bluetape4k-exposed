package io.bluetape4k.exposed.ktor.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.ktor.core.ExposedKtorTransactionException
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.server.application.ApplicationCall
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ExposedKtorR2dbcCancellationTest {

    companion object {
        @JvmStatic
        fun databaseAndFailure() = TestDB.enabledDialects()
            .filter { it == TestDB.H2 || it == TestDB.POSTGRESQL }
            .flatMap { database -> listOf(false, true).map { Arguments.of(database, it) } }
    }

    // 추가 상태가 있는 예외로 coroutine stacktrace recovery의 복사와 adapter의 재전파를 구분한다.
    private class RequestCancellation(val requestId: String) : CancellationException(requestId)
    private class RequestFailure(val requestId: String) : IllegalArgumentException(requestId)
    private class RequestError(val requestId: String) : AssertionError(requestId)

    @ParameterizedTest
    @MethodSource("databaseAndFailure")
    fun `metric 실패는 일반 예외의 cause와 Error 재전파 계약을 변경하지 않는다`(testDB: TestDB, fatal: Boolean) = runSuspendIO {
        val previousFixtureDatabase = testDB.db
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val database = testDB.connect()
        val primary = if (fatal) RequestError("transaction failed") else RequestFailure("transaction failed")
        val metricFailure = IllegalStateException("metric recording failed")
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id = throw metricFailure
        })
        try {
            val failure = assertFailsWith<Throwable> {
                mockk<ApplicationCall>().exposedR2dbcTransaction(database, registry) {
                    exec("SELECT 1")
                    throw primary
                }
            }
            if (fatal) {
                failure shouldBeEqualTo primary
            } else {
                failure.javaClass shouldBeEqualTo ExposedKtorTransactionException::class.java
                failure.cause shouldBeEqualTo primary
            }
            primary.suppressed.toList() shouldHaveSize 1
            primary.suppressed.single() shouldBeEqualTo metricFailure
        } finally {
            registry.close()
            TransactionManager.closeAndUnregister(database)
            testDB.db = previousFixtureDatabase
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }

    @ParameterizedTest
    @MethodSource("databaseAndFailure")
    fun `실제 Job 취소 원인은 metric 정리 실패보다 우선한다`(testDB: TestDB, failMetrics: Boolean) = runSuspendIO {
        val previousFixtureDatabase = testDB.db
        val previousDefaultDatabase = TransactionManager.defaultDatabase
        val database = testDB.connect()
        val metricFailure = IllegalStateException("metric recording failed")
        val registry = SimpleMeterRegistry()
        if (failMetrics) {
            registry.config().meterFilter(object : MeterFilter {
                override fun map(id: Meter.Id): Meter.Id = throw metricFailure
            })
        }
        try {
            // 단계별 barrier가 필요한 단일 취소 시나리오이므로 stress tester 대신 실제 자식 Job을 사용한다.
            withTimeout(10_000) {
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val observed = CompletableDeferred<Throwable>()
                    val cancellation = RequestCancellation("request cancelled")
                    val call = mockk<ApplicationCall>()
                    val job = launch {
                        try {
                            call.exposedR2dbcTransaction(database, registry) {
                                exec("SELECT 1")
                                entered.complete(Unit)
                                awaitCancellation()
                            }
                        } catch (failure: Throwable) {
                            // 테스트 부모를 취소하지 않고 adapter가 실제로 던진 원인을 관측한다.
                            observed.complete(failure)
                            // 연결 단계 실패를 barrier timeout으로 가리지 않는다.
                            entered.completeExceptionally(failure)
                        }
                    }
                    entered.await()
                    job.cancel(cancellation)
                    job.join()

                    val failure = observed.await()
                    failure shouldBeEqualTo cancellation
                    failure.suppressed.toList() shouldHaveSize if (failMetrics) 1 else 0
                    if (failMetrics) {
                        failure.suppressed.single() shouldBeEqualTo metricFailure
                    }
                }
            }
            // 취소된 transaction이 다음 transaction의 정상 실행을 막지 않아야 한다.
            suspendTransaction(database) { exec("SELECT 1") }
        } finally {
            registry.close()
            TransactionManager.closeAndUnregister(database)
            testDB.db = previousFixtureDatabase
            TransactionManager.defaultDatabase = previousDefaultDatabase
        }
    }
}
