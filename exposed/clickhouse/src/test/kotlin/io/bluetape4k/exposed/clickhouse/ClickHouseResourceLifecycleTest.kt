package io.bluetape4k.exposed.clickhouse

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** diagnostics terminal event가 자원 정리 경계 뒤에 전달되는지 검증합니다. */
class ClickHouseResourceLifecycleTest: AbstractClickHouseTest() {

    private class MarkerFailure(message: String): IllegalStateException(message)
    private class MarkerCancellation(message: String): CancellationException(message)

    private object Numbers: Table("system.numbers") {
        val number = long("number")
    }

    @Test
    fun `성공 시 sink는 cleanup 이후 한 번 호출되고 원래 결과를 보존한다`() = runSuspendIO {
        val order = CopyOnWriteArrayList<String>()
        val config = ClickHouseQueryDiagnosticsConfig(
            listener = ClickHouseQueryListener { event -> order += "listener:${event.kind}" },
            sink = ClickHouseQueryDiagnosticsSink {
                order += "sink"
                it.outcome shouldBeEqualTo ClickHouseQueryOutcome.Success
            },
        )

        queryFlow(
            db,
            diagnostics = config,
            query = { Numbers.selectAll().limit(2) },
            mapper = { it[Numbers.number] },
        ).toList() shouldBeEqualTo listOf(0L, 1L)

        order.last() shouldBeEqualTo "sink"
        order.count { it == "sink" } shouldBeEqualTo 1
    }

    @Test
    fun `mapper 예외와 callback 예외가 원래 예외를 대체하지 않는다`() = runSuspendIO {
        val original = MarkerFailure("mapper-marker")
        val callbackFailures = AtomicInteger()
        val config = ClickHouseQueryDiagnosticsConfig(
            listener = ClickHouseQueryListener {
                callbackFailures.incrementAndGet()
                throw IllegalArgumentException("callback-secret")
            },
        )

        val failure = assertFailsWith<IllegalStateException> {
            queryFlow(
                db,
                diagnostics = config,
                query = { Numbers.selectAll().limit(1) },
                mapper = { throw original },
            ).collect()
        }

        failure.shouldBeSameInstanceAs(original)
        callbackFailures.get() shouldBeEqualTo 3
    }

    @Test
    fun `local cancellation은 CancellationException을 보존하고 재시도하지 않는다`() = runSuspendIO {
        val original = MarkerCancellation("local-cancel")
        val config = ClickHouseQueryDiagnosticsConfig()

        val failure = assertFailsWith<CancellationException> {
            queryFlow(
                db,
                diagnostics = config,
                query = { Numbers.selectAll().limit(1) },
                mapper = { throw original },
            ).collect()
        }

        failure.shouldBeSameInstanceAs(original)
    }
}
