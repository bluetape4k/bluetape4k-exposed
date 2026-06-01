package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
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
                context.containsBean("exposedR2dbcCacheHealthIndicator") shouldBeEqualTo false
            }
    }

    @Test
    fun `does not register R2DBC cache health indicator when repository class is missing`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(R2dbcCaffeineRepository::class.java))
            .run { context ->
                context.containsBean("exposedR2dbcCacheHealthIndicator") shouldBeEqualTo false
            }
    }

    @Test
    fun `reports DOWN when R2DBC cache consistency has flush failure`() {
        contextRunner
            .withUserConfiguration(FailedRepositoryConfiguration::class.java)
            .run { context ->
                val health = context.getBean("exposedR2dbcCacheHealthIndicator", ReactiveHealthIndicator::class.java)
                    .health()
                    .block()

                health?.status shouldBeEqualTo Status.DOWN
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class HealthyRepositoryConfiguration {

        @Bean
        fun r2dbcCaffeineRepository(): R2dbcCaffeineRepository<Any, Serializable> =
            mockk {
                coEvery { validateConsistency() } returns CacheHealthReport(
                    mode = CacheWriteMode.WRITE_THROUGH,
                    queueDepth = 0,
                    isFlushJobRunning = false,
                    lastFlushError = null,
                )
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FailedRepositoryConfiguration {

        @Bean
        fun r2dbcCaffeineRepository(): R2dbcCaffeineRepository<Any, Serializable> =
            mockk {
                coEvery { validateConsistency() } returns CacheHealthReport(
                    mode = CacheWriteMode.WRITE_BEHIND,
                    queueDepth = 0,
                    isFlushJobRunning = false,
                    lastFlushError = IllegalStateException("flush failed"),
                )
            }
    }
}
