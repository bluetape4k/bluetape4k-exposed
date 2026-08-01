package io.bluetape4k.exposed.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
import io.bluetape4k.ktor.testing.bluetape4kJsonClient
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExposedKtorCacheHealthRoutesTest {

    @Test
    fun `direct routes reject equivalent health and readiness paths`() = testApplication {
        application {
            routing {
                val error = assertFailsWith<IllegalArgumentException> {
                    bluetape4kExposedHealthRoutes(
                        jdbcDatabase = null,
                        jdbcBlockingDispatcher = null,
                        r2dbcDatabase = null,
                        healthPath = "/ops/status",
                        readinessPath = "/ops/status/",
                        cacheReadiness = cacheConfig(contributor("orders") { ExposedKtorCacheStatus.UP }),
                    )
                }

                error.message shouldContain "healthPath and readinessPath must be distinct"
            }
        }
    }

    @Test
    fun `cache-only readiness exposes deterministic finite details and liveness invokes no probe`() = testApplication {
        val invocations = AtomicInteger()
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
            )
            routing {
                bluetape4kExposedHealthRoutes(
                    jdbcDatabase = null,
                    jdbcBlockingDispatcher = null,
                    r2dbcDatabase = null,
                    cacheReadiness = cacheConfig(
                        contributor("orders") {
                            invocations.incrementAndGet()
                            ExposedKtorCacheStatus.UP
                        }
                    ),
                )
            }
        }

        val client = bluetape4kJsonClient()
        client.get("/healthz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>() shouldEqual
                HealthResponse.up(mapOf("exposed" to HealthResponse.UP))
        assertEquals(0, invocations.get())

        client.get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.OK)
            .decodeJsonBody<HealthResponse>() shouldEqual
                HealthResponse.up(mapOf("cache.orders" to HealthResponse.UP))
        assertEquals(1, invocations.get())
    }

    @Test
    fun `down and ordinary exception continue in installation order and return sanitized 503`() = testApplication {
        val order = mutableListOf<String>()
        val registry = SimpleMeterRegistry()
        val secrets = listOf(
            "cache-key=tenant/42",
            "SELECT * FROM payments",
            "jdbc:h2:mem:secret",
            "namespace=customer-private",
            "password=top-secret\nnext",
        )
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
            )
            routing {
                bluetape4kExposedHealthRoutes(
                    jdbcDatabase = null,
                    jdbcBlockingDispatcher = null,
                    r2dbcDatabase = null,
                    meterRegistry = registry,
                    cacheReadiness = cacheConfig(
                        contributor("first") {
                            order += "first"
                            ExposedKtorCacheStatus.DOWN
                        },
                        contributor("second") {
                            order += "second"
                            throw IllegalStateException(secrets.joinToString(" | "))
                        },
                        contributor("third") {
                            order += "third"
                            ExposedKtorCacheStatus.UP
                        },
                    ),
                )
            }
        }

        val response = bluetape4kJsonClient().get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
        val health = response.decodeJsonBody<HealthResponse>()
        health shouldEqual HealthResponse.down(
            linkedMapOf(
                "cache.first" to HealthResponse.DOWN,
                "cache.second" to HealthResponse.DOWN,
                "cache.third" to HealthResponse.UP,
            )
        )
        assertEquals(listOf("first", "second", "third"), order)
        val body = response.bodyAsText()
        secrets.forEach { assertFalse(body.contains(it), it) }
        val exportedTags = registry.meters.flatMap { meter -> meter.id.tags.map { it.key to it.value } }
        assertTrue(exportedTags.all { (key, _) -> key in setOf("component", "kind", "operation", "outcome") })
        secrets.forEach { secret ->
            assertTrue(exportedTags.none { (_, value) -> value.contains(secret) }, secret)
        }
        val production = healthRoutesSource()
        assertFalse(Regex("io\\.bluetape4k\\.logging|KLogging|logger\\.|log\\.(warn|error|info)").containsMatchIn(production))
    }

    @Test
    fun `supplier self-cancellation is sanitized error while request remains active and later probe runs`() =
        testApplication {
            val registry = SimpleMeterRegistry()
            val later = AtomicInteger()
            val secret = "cache-key=secret password=top-secret\nSELECT credentials"
            application {
                installBluetape4kKtorCore(
                    Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
                )
                routing {
                    bluetape4kExposedHealthRoutes(
                        jdbcDatabase = null,
                        jdbcBlockingDispatcher = null,
                        r2dbcDatabase = null,
                        meterRegistry = registry,
                        cacheReadiness = cacheConfig(
                            contributor("self_cancel") {
                                currentCoroutineContext().cancel(CancellationException(secret))
                                yield()
                                ExposedKtorCacheStatus.UP
                            },
                            contributor("later") {
                                later.incrementAndGet()
                                ExposedKtorCacheStatus.UP
                            },
                        ),
                    )
                }
            }

            val response = bluetape4kJsonClient().get("/readyz/exposed")
                .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
            response.decodeJsonBody<HealthResponse>() shouldEqual HealthResponse.down(
                linkedMapOf("cache.self_cancel" to HealthResponse.DOWN, "cache.later" to HealthResponse.UP)
            )
            assertFalse(response.bodyAsText().contains(secret))
            assertEquals(1, later.get())
            assertEquals(1L, timerCount(registry, "self_cancel", ERROR_OUTCOME))
            assertEquals(0L, timerCount(registry, "self_cancel", CANCELLED_OUTCOME))
        }

    @Test
    fun `active timeout returns one finite 503 detail and one timeout timer`() = testApplication {
        val registry = SimpleMeterRegistry()
        application {
            installBluetape4kKtorCore(
                Bluetape4kKtorCoreConfig(installStatusPages = false, installHealthRoutes = false)
            )
            routing {
                bluetape4kExposedHealthRoutes(
                    jdbcDatabase = null,
                    jdbcBlockingDispatcher = null,
                    r2dbcDatabase = null,
                    readinessProbeTimeout = 20.milliseconds,
                    meterRegistry = registry,
                    cacheReadiness = cacheConfig(
                        contributor("slow") {
                            delay(1.seconds)
                            ExposedKtorCacheStatus.UP
                        }
                    ),
                )
            }
        }

        val health = bluetape4kJsonClient().get("/readyz/exposed")
            .shouldHaveStatus(HttpStatusCode.ServiceUnavailable)
            .decodeJsonBody<HealthResponse>()
        health shouldEqual HealthResponse.down(mapOf("cache.slow" to TIMEOUT_OUTCOME))
        assertEquals(1L, timerCount(registry, "slow", TIMEOUT_OUTCOME))
        assertEquals(0L, timerCount(registry, "slow", CANCELLED_OUTCOME))
    }

    @Test
    fun `supplier cancellation while request remains active is one sanitized error and later probes continue`() = runTest {
        val registry = SimpleMeterRegistry()
        val later = AtomicInteger()
        val bindings = bindings(
            registry,
            contributor("cancel") {
                throw CancellationException("cache-key=secret password=top-secret")
            },
            contributor("later") {
                later.incrementAndGet()
                ExposedKtorCacheStatus.UP
            },
        )

        val details = aggregateExposedKtorReadiness(
            jdbcProbe = null,
            r2dbcProbe = null,
            cacheBindings = bindings,
            cachePhaseTimeout = 1.seconds,
            timeSource = testScheduler.timeSource,
        )

        assertEquals(
            linkedMapOf("cache.cancel" to HealthResponse.DOWN, "cache.later" to HealthResponse.UP),
            details,
        )
        assertEquals(1, later.get())
        assertEquals(1L, timerCount(registry, "cancel", ERROR_OUTCOME))
        assertEquals(0L, timerCount(registry, "cancel", CANCELLED_OUTCOME))
    }

    @Test
    fun `parent cancellation rethrows records cancelled once and clears only active newest generation`() = runTest {
        val registry = SimpleMeterRegistry()
        val entered = CompletableDeferred<Unit>()
        val binding = bindings(
            registry,
            contributor("active") {
                entered.complete(Unit)
                awaitCancellation()
            },
        ).single()
        val job = launch {
            aggregateExposedKtorReadiness(
                jdbcProbe = null,
                r2dbcProbe = null,
                cacheBindings = listOf(binding),
                cachePhaseTimeout = 10.seconds,
                timeSource = testScheduler.timeSource,
            )
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(binding.currentSample().queueDepth.isNaN())
        assertEquals(1L, timerCount(registry, "active", CANCELLED_OUTCOME))
        assertEquals(0L, timerCount(registry, "active", TIMEOUT_OUTCOME))
        assertEquals(0L, timerCount(registry, "active", ERROR_OUTCOME))
    }

    @Test
    fun `fatal JVM error propagates without a cache failure result`() = runTest {
        val registry = SimpleMeterRegistry()
        val binding = bindings(
            registry,
            contributor("fatal") { throw AssertionError("fatal-secret") },
        )

        val failure = runCatching {
            aggregateExposedKtorReadiness(
                jdbcProbe = null,
                r2dbcProbe = null,
                cacheBindings = binding,
                cachePhaseTimeout = 1.seconds,
                timeSource = testScheduler.timeSource,
            )
        }.exceptionOrNull()

        assertTrue(failure is AssertionError)
        assertEquals(0L, timerCount(registry, "fatal", ERROR_OUTCOME))
    }

    @Test
    fun `late older success cannot overwrite a newer published sample`() = runTest {
        repeat(5) {
            val registry = SimpleMeterRegistry()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val invocation = AtomicInteger()
            val contributor = ExposedKtorCacheContributor.custom("race") {
                if (invocation.incrementAndGet() == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    ExposedKtorCacheStatus.DOWN
                } else {
                    ExposedKtorCacheStatus.UP
                }
            }
            val binding = registerExposedKtorCacheMetrics(registry, cacheConfig(contributor)).single()

            coroutineScope {
                val older = launch {
                    aggregateExposedKtorReadiness(null, null, listOf(binding), 10.seconds, testScheduler.timeSource)
                }
                firstEntered.await()
                aggregateExposedKtorReadiness(null, null, listOf(binding), 10.seconds, testScheduler.timeSource)
                releaseFirst.complete(Unit)
                older.join()
            }

            assertEquals(ExposedKtorCacheStatus.UP, binding.currentSample().status)
        }
    }

    @Test
    fun `older active cancellation after newer success cannot clear the newer sample`() = runTest {
        repeat(5) {
            val registry = SimpleMeterRegistry()
            val firstEntered = CompletableDeferred<Unit>()
            val invocation = AtomicInteger()
            val contributor = ExposedKtorCacheContributor.custom("race") {
                if (invocation.incrementAndGet() == 1) {
                    firstEntered.complete(Unit)
                    awaitCancellation()
                } else {
                    ExposedKtorCacheStatus.UP
                }
            }
            val binding = registerExposedKtorCacheMetrics(registry, cacheConfig(contributor)).single()

            val older = launch {
                aggregateExposedKtorReadiness(null, null, listOf(binding), 10.seconds, testScheduler.timeSource)
            }
            firstEntered.await()
            aggregateExposedKtorReadiness(null, null, listOf(binding), 10.seconds, testScheduler.timeSource)
            older.cancelAndJoin()

            assertEquals(ExposedKtorCacheStatus.UP, binding.currentSample().status)
        }
    }

    @Test
    fun `bounded concurrent requests keep meter count constant and gauge reads safe`() = runTest {
        val registry = SimpleMeterRegistry()
        val binding = bindings(registry, contributor("concurrent") { ExposedKtorCacheStatus.UP }).single()

        coroutineScope {
            repeat(32) {
                launch {
                    aggregateExposedKtorReadiness(
                        null,
                        null,
                        listOf(binding),
                        1.seconds,
                        testScheduler.timeSource,
                    )
                    registry.meters.filter { it.id.type.name == "GAUGE" }.forEach { meter ->
                        meter.measure().forEach { measurement ->
                            assertTrue(measurement.value.isNaN() || measurement.value >= 0.0)
                        }
                    }
                }
            }
        }
        assertEquals(8, registry.meters.size)
    }

    private fun bindings(
        registry: SimpleMeterRegistry,
        vararg contributors: ExposedKtorCacheContributor,
    ) = registerExposedKtorCacheMetrics(registry, cacheConfig(*contributors))

    private fun cacheConfig(vararg contributors: ExposedKtorCacheContributor) =
        ExposedKtorCacheReadinessConfig(contributors.toList())

    private fun contributor(
        component: String,
        probe: suspend () -> ExposedKtorCacheStatus,
    ) = ExposedKtorCacheContributor.custom(component, probe)

    private fun timerCount(registry: SimpleMeterRegistry, component: String, outcome: String): Long =
        registry.find(CACHE_READINESS_METER_NAME)
            .tags("component", component, "kind", "custom", "operation", READINESS_OPERATION, "outcome", outcome)
            .timer()
            ?.count()
            ?: 0L

    private fun healthRoutesSource(): String {
        val relative = "ktor/exposed/src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"
        val paths = listOf(Path.of(relative), Path.of("src/main/kotlin/io/bluetape4k/exposed/ktor/ExposedKtorHealthRoutes.kt"))
        return Files.readString(paths.first(Files::exists))
    }

    private infix fun <T> T.shouldEqual(expected: T): T {
        assertEquals(expected, this)
        return this
    }
}
