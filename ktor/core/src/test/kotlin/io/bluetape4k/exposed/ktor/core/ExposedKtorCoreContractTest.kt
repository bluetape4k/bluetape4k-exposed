package io.bluetape4k.exposed.ktor.core

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExposedKtorCoreContractTest {

    @Test
    fun `core status pages keep the documented JVM facade owner`() {
        Class.forName("io.bluetape4k.exposed.ktor.core.ExposedKtorCoreStatusPagesKt")
            .name shouldBeEqualTo "io.bluetape4k.exposed.ktor.core.ExposedKtorCoreStatusPagesKt"
        ExposedKtorTransactionException::class.java.constructors
            .map { it.parameterTypes.toList() } shouldBeEqualTo listOf(emptyList())
    }

    @Test
    fun `core status pages expose only the fixed transaction error catalog`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { bluetape4kExposedCoreErrors() }
            routing {
                get("/internal/secret") {
                    throw ExposedKtorTransactionException()
                }
            }
        }

        val response = client.get("/internal/secret")
        response.status shouldBeEqualTo HttpStatusCode.InternalServerError
        val body = response.bodyAsText()
        body.contains("EXPOSED_TRANSACTION_FAILED").shouldBeTrue()
        body.contains("Exposed transaction failed").shouldBeTrue()
        body.contains("/internal/secret").shouldBeFalse()
    }

    @Test
    fun `registration requires cooperative probes and immutable safe components`() {
        val nonCooperative = object : ExposedKtorReadinessProbe {
            override val component: String = "orders"
            override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.JDBC
            override suspend fun probe(timeout: kotlin.time.Duration): ExposedKtorReadinessOutcome =
                ExposedKtorReadinessOutcome.UP
        }

        assertFailsWith<IllegalArgumentException> {
            validateReadinessProbes(listOf(nonCooperative))
        }
        assertFailsWith<IllegalArgumentException> {
            validateReadinessProbes(
                listOf(
                    cooperativeProbe("orders"),
                    cooperativeProbe("orders"),
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateReadinessProbes(listOf(cooperativeProbe("orders/{id}")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateReadinessProbes((0..MAX_READINESS_PROBES).map { cooperativeProbe("c$it") })
        }
    }

    @Test
    fun `readiness uses one monotonic budget and marks unstarted probes as timeout`() = runTest {
        var now = 0L
        val invocations = mutableListOf<String>()
        val probes = listOf(
            cooperativeProbe("first") {
                invocations += "first"
                now += 50.milliseconds.inWholeNanoseconds
                ExposedKtorReadinessOutcome.UP
            },
            cooperativeProbe("second") {
                invocations += "second"
                now += 60.milliseconds.inWholeNanoseconds
                ExposedKtorReadinessOutcome.UP
            },
            cooperativeProbe("third") {
                invocations += "third"
                ExposedKtorReadinessOutcome.UP
            },
        ).map { probe ->
            RegisteredProbe(probe.component, probe.backend, probe)
        }

        val details = evaluateExposedKtorReadiness(
            probes = probes,
            readinessProbeTimeout = 100.milliseconds,
            clock = ReadinessClock { now },
        )

        invocations shouldBeEqualTo listOf("first", "second")
        details shouldBeEqualTo linkedMapOf(
            "first" to ExposedKtorReadinessOutcome.UP.name,
            "second" to ExposedKtorReadinessOutcome.TIMEOUT.name,
            "third" to ExposedKtorReadinessOutcome.TIMEOUT.name,
        )
    }

    @Test
    fun `active probe timeout cancellation is down while caller cancellation is rethrown`() = runTest {
        val directTimeout = cooperativeProbe("timeout") {
            kotlinx.coroutines.withTimeout(1.milliseconds) {
                kotlinx.coroutines.delay(10.seconds)
            }
            ExposedKtorReadinessOutcome.UP
        }
        val timeoutDetails = evaluateExposedKtorReadiness(
            probes = listOf(RegisteredProbe("timeout", ExposedKtorReadinessBackend.CACHE, directTimeout)),
            readinessProbeTimeout = 1.seconds,
        )
        timeoutDetails["timeout"] shouldBeEqualTo ExposedKtorReadinessOutcome.DOWN.name

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) {
                evaluateExposedKtorReadiness(
                    probes = listOf(
                        RegisteredProbe(
                            "cancelled",
                            ExposedKtorReadinessBackend.CACHE,
                            cooperativeProbe("cancelled") { awaitCancellation() },
                        )
                    ),
                    readinessProbeTimeout = 1.seconds,
                )
            }
        }
    }

    @Test
    fun `ordinary probe failures are redacted while Error is rethrown`() = runTest {
        val redacted = evaluateExposedKtorReadiness(
            probes = listOf(
                RegisteredProbe(
                    "failing",
                    ExposedKtorReadinessBackend.CACHE,
                    cooperativeProbe("failing") { throw IllegalStateException("secret") },
                ),
            ),
            readinessProbeTimeout = 1.seconds,
        )
        redacted["failing"] shouldBeEqualTo ExposedKtorReadinessOutcome.DOWN.name

        assertFailsWith<AssertionError> {
            evaluateExposedKtorReadiness(
                probes = listOf(
                    RegisteredProbe(
                        "fatal",
                        ExposedKtorReadinessBackend.CACHE,
                        cooperativeProbe("fatal") { throw AssertionError("fatal") },
                    ),
                ),
                readinessProbeTimeout = 1.seconds,
            )
        }
    }

    @Test
    fun `readiness records exactly one core sample per probe`() = runTest {
        val registry = SimpleMeterRegistry()
        val registered = listOf(
            RegisteredProbe(
                "orders",
                ExposedKtorReadinessBackend.CACHE,
                cooperativeProbe("orders"),
            ),
        )
        val metrics = registry.installCoreReadinessMetrics(registered)

        evaluateExposedKtorReadiness(
            probes = registered,
            readinessProbeTimeout = 1.seconds,
            metrics = metrics,
        )

        registry.find(CORE_READINESS_METER_NAME)
            .tag("component", "orders")
            .tag("outcome", "success")
            .timer()
            ?.count() shouldBeEqualTo 1L
    }

    @Test
    fun `large finite duration saturates without a negative deadline`() {
        Long.MAX_VALUE.seconds.toTimeoutNanosSaturated() shouldBeEqualTo Long.MAX_VALUE
        remainingReadinessNanos(0L, Long.MAX_VALUE, Long.MAX_VALUE) shouldBeEqualTo 0L
    }

    private fun cooperativeProbe(
        component: String,
        probe: suspend () -> ExposedKtorReadinessOutcome = { ExposedKtorReadinessOutcome.UP },
    ): ExposedKtorCooperativeReadinessProbe = object : ExposedKtorCooperativeReadinessProbe {
        override val component: String = component
        override val backend: ExposedKtorReadinessBackend = ExposedKtorReadinessBackend.CACHE
        override suspend fun probe(timeout: kotlin.time.Duration): ExposedKtorReadinessOutcome = probe()
    }
}
