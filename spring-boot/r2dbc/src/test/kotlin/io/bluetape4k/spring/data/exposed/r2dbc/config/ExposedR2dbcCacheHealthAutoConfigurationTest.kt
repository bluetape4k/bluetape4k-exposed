package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.io.Serializable

class ExposedR2dbcCacheHealthAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedR2dbcCacheHealthAutoConfiguration::class.java))

    @Test
    fun `registers R2DBC cache health indicator when enabled`() {
        contextRunner
            .withUserConfiguration(HealthyRepositoryConfiguration::class.java)
            .run { context ->
                val health = context.getBean("exposedR2dbcCacheHealthIndicator", ReactiveHealthIndicator::class.java)
                    .health()
                    .block()

                health?.status shouldBeEqualTo Status.UP
                health?.details?.get("repositoryCount") shouldBeEqualTo 1
            }
    }

    @Test
    fun `does not register R2DBC cache health indicator when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.exposed.cache.health.enabled=false")
            .withUserConfiguration(HealthyRepositoryConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedR2dbcCacheHealthIndicator").shouldBeFalse()
            }
    }

    @Test
    fun `does not register R2DBC cache health indicator when repository class is missing`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(R2dbcCaffeineRepository::class.java))
            .run { context ->
                context.containsBean("exposedR2dbcCacheHealthIndicator").shouldBeFalse()
            }
    }

    @Test
    fun `maps every R2DBC cache worker state without inferring status from queue depth`() {
        val cases = listOf(
            CacheWorkerState.NOT_APPLICABLE to Status.UP,
            CacheWorkerState.IDLE to Status.UP,
            CacheWorkerState.RUNNING to Status.UP,
            CacheWorkerState.DRAINING to Status.OUT_OF_SERVICE,
            CacheWorkerState.STOPPED to Status.OUT_OF_SERVICE,
            CacheWorkerState.FAILED to Status.DOWN,
        )

        cases.forEach { (workerState, expectedStatus) ->
            healthFor(report(workerState = workerState, queueDepth = 37)).status shouldBeEqualTo expectedStatus
        }
    }

    @Test
    fun `reports DOWN with the flush error regardless of R2DBC worker state`() {
        val failure = IllegalStateException("flush failed")

        CacheWorkerState.entries.forEach { workerState ->
            val health = healthFor(report(workerState = workerState, lastFlushError = failure))

            health.status shouldBeEqualTo Status.DOWN
            health.details["error"] shouldBeEqualTo failure.toString()
        }
    }

    @Test
    fun `reports DOWN without an error detail for a failed R2DBC worker`() {
        val health = healthFor(report(workerState = CacheWorkerState.FAILED))

        health.status shouldBeEqualTo Status.DOWN
        health.details.containsKey("error").shouldBeFalse()
    }

    @Test
    fun `applies R2DBC aggregate precedence independently of repository order`() {
        val up = report(workerState = CacheWorkerState.RUNNING)
        val unavailable = report(workerState = CacheWorkerState.STOPPED)
        val failed = report(workerState = CacheWorkerState.FAILED)
        val failure = IllegalArgumentException("flush failed globally")
        val errored = report(workerState = CacheWorkerState.IDLE, lastFlushError = failure)
        val cases = listOf(
            Triple(up, unavailable, Status.OUT_OF_SERVICE),
            Triple(unavailable, failed, Status.DOWN),
            Triple(failed, errored, Status.DOWN),
        )

        cases.forEach { (first, second, expectedStatus) ->
            listOf(first to second, second to first).forEach { (left, right) ->
                val health = healthFor(left, right)

                health.status shouldBeEqualTo expectedStatus
                health.details["error"] shouldBeEqualTo
                    if (left.lastFlushError != null || right.lastFlushError != null) failure.toString() else null
            }
        }
    }

    @Test
    fun `selects the same R2DBC flush error independently of repository order`() {
        val selected = IllegalArgumentException("alpha failure")
        val other = IllegalStateException("zeta failure")
        val selectedReport = report(workerState = CacheWorkerState.RUNNING, lastFlushError = selected)
        val otherReport = report(workerState = CacheWorkerState.FAILED, lastFlushError = other)

        listOf(selectedReport to otherReport, otherReport to selectedReport).forEach { (first, second) ->
            val health = healthFor(first, second)

            health.status shouldBeEqualTo Status.DOWN
            health.details["error"] shouldBeEqualTo selected.toString()
        }
    }

    @Test
    fun `exposes finite R2DBC worker details and only includes flush errors when present`() {
        val failure = IllegalStateException("flush failed")
        val health = healthFor(
            report(
                mode = CacheWriteMode.WRITE_BEHIND,
                queueDepth = 13,
                workerState = CacheWorkerState.DRAINING,
            ),
            report(
                mode = CacheWriteMode.WRITE_THROUGH,
                workerState = CacheWorkerState.NOT_APPLICABLE,
                lastFlushError = failure,
            ),
        )

        health.details["repositoryCount"] shouldBeEqualTo 2
        @Suppress("UNCHECKED_CAST")
        val reports = health.details["reports"] as List<Map<String, Any?>>
        reports[0] shouldBeEqualTo mapOf(
            "mode" to "WRITE_BEHIND",
            "queueDepth" to 13,
            "workerState" to "DRAINING",
        )
        reports[1] shouldBeEqualTo mapOf(
            "mode" to "WRITE_THROUGH",
            "queueDepth" to 0,
            "workerState" to "NOT_APPLICABLE",
            "lastFlushError" to "flush failed",
        )
    }

    private fun healthFor(vararg reports: CacheHealthReport): Health {
        val repositories: List<R2dbcCaffeineRepository<*, *>> = reports.map { report ->
            mockk<R2dbcCaffeineRepository<Any, Serializable>> {
                coEvery { validateConsistency() } returns report
            }
        }
        val provider = mockk<ObjectProvider<R2dbcCaffeineRepository<*, *>>> {
            every { orderedStream() } returns repositories.stream()
        }
        return requireNotNull(ExposedR2dbcCacheHealthIndicator(provider).health().block())
    }

    private fun report(
        mode: CacheWriteMode = CacheWriteMode.WRITE_BEHIND,
        queueDepth: Int = 0,
        workerState: CacheWorkerState,
        lastFlushError: Throwable? = null,
    ): CacheHealthReport = CacheHealthReport(mode, queueDepth, workerState, lastFlushError)

    @TestConfiguration(proxyBeanMethods = false)
    class HealthyRepositoryConfiguration {

        @Bean
        fun r2dbcCaffeineRepository(): R2dbcCaffeineRepository<Any, Serializable> =
            mockk {
                coEvery { validateConsistency() } returns CacheHealthReport(
                    mode = CacheWriteMode.WRITE_THROUGH,
                    queueDepth = 0,
                    workerState = CacheWorkerState.NOT_APPLICABLE,
                    lastFlushError = null,
                )
            }
    }
}
