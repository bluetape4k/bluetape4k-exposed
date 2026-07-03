package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.io.Serializable

class ExposedJdbcCacheHealthAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedJdbcCacheHealthAutoConfiguration::class.java))

    @Test
    fun `registers JDBC cache health indicator when enabled`() {
        contextRunner
            .withUserConfiguration(HealthyRepositoryConfiguration::class.java)
            .run { context ->
                val health = requireNotNull(
                    context.getBean("exposedJdbcCacheHealthIndicator", HealthIndicator::class.java)
                        .health()
                )

                health.status shouldBeEqualTo Status.UP
                health.details["repositoryCount"] shouldBeEqualTo 1
            }
    }

    @Test
    fun `does not register JDBC cache health indicator when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.exposed.cache.health.enabled=false")
            .withUserConfiguration(HealthyRepositoryConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedJdbcCacheHealthIndicator").shouldBeFalse()
            }
    }

    @Test
    fun `does not register JDBC cache health indicator when repository class is missing`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(JdbcCaffeineRepository::class.java))
            .run { context ->
                context.containsBean("exposedJdbcCacheHealthIndicator").shouldBeFalse()
            }
    }

    @Test
    fun `reports DOWN when JDBC cache consistency has flush failure`() {
        contextRunner
            .withUserConfiguration(FailedRepositoryConfiguration::class.java)
            .run { context ->
                val health = requireNotNull(
                    context.getBean("exposedJdbcCacheHealthIndicator", HealthIndicator::class.java)
                        .health()
                )

                health.status shouldBeEqualTo Status.DOWN
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class HealthyRepositoryConfiguration {

        @Bean
        fun jdbcCaffeineRepository(): JdbcCaffeineRepository<Any, Serializable> =
            mockk {
                every { validateConsistency() } returns CacheHealthReport(
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
        fun jdbcCaffeineRepository(): JdbcCaffeineRepository<Any, Serializable> =
            mockk {
                every { validateConsistency() } returns CacheHealthReport(
                    mode = CacheWriteMode.WRITE_BEHIND,
                    queueDepth = 0,
                    isFlushJobRunning = false,
                    lastFlushError = IllegalStateException("flush failed"),
                )
            }
    }
}
