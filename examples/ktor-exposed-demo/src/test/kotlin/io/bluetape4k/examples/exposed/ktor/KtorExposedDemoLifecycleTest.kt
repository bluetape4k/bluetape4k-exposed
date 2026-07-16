package io.bluetape4k.examples.exposed.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.examples.exposed.ktor.order.InMemoryOrderEventPublisher
import io.bluetape4k.examples.exposed.ktor.order.OrderCommandService
import io.bluetape4k.examples.exposed.ktor.order.OrderR2dbcCaffeineRepository
import io.mockk.mockk
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

class KtorExposedDemoLifecycleTest {

    @Test
    fun `acquisition failure closes completed resources in reverse order and keeps primary cause`() {
        val order = mutableListOf<String>()
        val primary = IllegalStateException("schema failed")
        val cleanupFailure = IllegalArgumentException("pool close failed")
        val acquirer = DemoResourceAcquirer()

        val failure = assertFailsWith<IllegalStateException> {
            acquirer.acquire {
                completed("lease") { order += "lease" }
                completed("jdbc") { order += "jdbc" }
                completed("dispatcher") { order += "dispatcher" }
                completed("pool") {
                    order += "pool"
                    throw cleanupFailure
                }
                throw primary
            }
        }

        assertSame(primary, failure)
        order shouldBeEqualTo listOf("pool", "dispatcher", "jdbc", "lease")
        primary.suppressed.single().message shouldBeEqualTo cleanupFailure.message
    }

    @Test
    fun `overlapping demo lifecycle is rejected and sequential reuse succeeds`() {
        val lease = DemoLifecycleLease(AtomicBoolean())

        val first = lease.acquire()
        assertFailsWith<IllegalStateException> { lease.acquire() }
        first.close()
        lease.acquire().close()
    }

    @Test
    fun `repeated close returns the original report without closing twice`() {
        val count = AtomicInteger()
        val resources = resources(NamedCloseAction("one") { count.incrementAndGet() })

        val first = resources.closeReport()
        val second = resources.closeReport()

        assertSame(first, second)
        first.isClean shouldBeEqualTo true
        count.get() shouldBeEqualTo 1
    }

    @Test
    fun `concurrent close returns one report and runs every closer once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val resources = resources(
            NamedCloseAction("held") {
                count.incrementAndGet()
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
            NamedCloseAction("later") { count.incrementAndGet() },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<DemoCleanupReport> { resources.closeReport() }
            entered.await(5, TimeUnit.SECONDS) shouldBeEqualTo true
            val second = executor.submit<DemoCleanupReport> { resources.closeReport() }
            release.countDown()

            val firstReport = first.get(5, TimeUnit.SECONDS)
            val secondReport = second.get(5, TimeUnit.SECONDS)
            assertSame(firstReport, secondReport)
            count.get() shouldBeEqualTo 2
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `engine start failure closes resources and returns exit one`() {
        val closeCount = AtomicInteger()
        val resources = resources(NamedCloseAction("resources") { closeCount.incrementAndGet() })
        val primary = IllegalStateException("bind failed password=secret")
        val diagnostics = RecordingDiagnosticSink()
        val stopArguments = mutableListOf<Pair<Long, Long>>()

        val result = runKtorExposedDemo(
            resourcesFactory = DemoResourcesFactory { resources },
            serverFactory = {
                object : DemoServer {
                    override fun start(wait: Boolean) = throw primary

                    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
                        stopArguments += gracePeriodMillis to timeoutMillis
                    }
                }
            },
            diagnosticSink = diagnostics,
        )

        result.status shouldBeEqualTo 1
        assertSame(primary, result.primaryFailure)
        closeCount.get() shouldBeEqualTo 1
        stopArguments shouldBeEqualTo listOf(1_000L to 5_000L)
        diagnostics.items.map { it.code } shouldBeEqualTo listOf("DEMO_STARTUP_FAILED")
        diagnostics.items.single().toString().contains("secret") shouldBeEqualTo false
    }

    @Test
    fun `resource cleanup failures aggregate once continue cleanup and return two`() {
        val order = mutableListOf<String>()
        val resources = resources(
            NamedCloseAction("repository") {
                order += "repository"
                throw IllegalStateException("repository failed")
            },
            NamedCloseAction("pool") {
                order += "pool"
                throw IllegalArgumentException("pool failed")
            },
            NamedCloseAction("lease") { order += "lease" },
        )
        val diagnostics = RecordingDiagnosticSink()

        val result = runKtorExposedDemo(
            resourcesFactory = DemoResourcesFactory { resources },
            serverFactory = {
                object : DemoServer {
                    override fun start(wait: Boolean) = Unit
                    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) = Unit
                }
            },
            diagnosticSink = diagnostics,
        )

        result.status shouldBeEqualTo 2
        result.cleanupReport.failures.map { it.message } shouldBeEqualTo
            listOf("repository failed", "pool failed")
        order shouldBeEqualTo listOf("repository", "pool", "lease")
        diagnostics.items.map { it.code } shouldBeEqualTo listOf("DEMO_SHUTDOWN_FAILED")
    }

    @Test
    fun `stderr diagnostic sink renders only the allowlisted key value record`() {
        val output = ByteArrayOutputStream()
        val sink = StderrDemoDiagnosticSink(PrintStream(output, true, Charsets.UTF_8))
        val correlationId = UUID.fromString("018f6f95-7f4a-7a20-8b52-70ad30c30f36").toString()

        sink.emit(
            io.bluetape4k.examples.exposed.ktor.order.DemoDiagnostic(
                code = "ORDER_READ_FAILED",
                correlationId = correlationId,
                component = "order-command",
                operation = "read",
                outcome = "failed",
            ),
        )

        output.toString(Charsets.UTF_8).trimEnd() shouldBeEqualTo
            "code=ORDER_READ_FAILED correlationId=$correlationId component=order-command operation=read outcome=failed"
    }

    private fun resources(vararg closeActions: NamedCloseAction): KtorExposedDemoResources =
        KtorExposedDemoResources(
            jdbcDatabase = mockk<Database>(),
            r2dbcDatabase = mockk<R2dbcDatabase>(),
            jdbcDispatcher = mockk<ExecutorCoroutineDispatcher>(),
            orderRepository = mockk<OrderR2dbcCaffeineRepository>(),
            eventPublisher = InMemoryOrderEventPublisher(),
            orderService = mockk<OrderCommandService>(),
            closeActions = closeActions.toList(),
        )

    private class RecordingDiagnosticSink : io.bluetape4k.examples.exposed.ktor.order.DemoDiagnosticSink {
        val items = mutableListOf<io.bluetape4k.examples.exposed.ktor.order.DemoDiagnostic>()

        override fun emit(diagnostic: io.bluetape4k.examples.exposed.ktor.order.DemoDiagnostic) {
            items += diagnostic
        }
    }
}
