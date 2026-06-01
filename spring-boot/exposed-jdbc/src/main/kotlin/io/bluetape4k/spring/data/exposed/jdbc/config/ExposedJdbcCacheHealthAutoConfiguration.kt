package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for JDBC Caffeine repository consistency health.
 */
@AutoConfiguration
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
    name = [
        "io.bluetape4k.exposed.jdbc.caffeine.repository.JdbcCaffeineRepository",
        "org.springframework.boot.health.contributor.HealthIndicator",
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.exposed.cache.health",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(ExposedJdbcCacheHealthProperties::class)
class ExposedJdbcCacheHealthAutoConfiguration {

    @Bean("exposedJdbcCacheHealthIndicator")
    @ConditionalOnMissingBean(name = ["exposedJdbcCacheHealthIndicator"])
    fun exposedJdbcCacheHealthIndicator(
        repositories: ObjectProvider<JdbcCaffeineRepository<*, *>>,
    ): HealthIndicator =
        ExposedJdbcCacheHealthIndicator(repositories)
}

/**
 * Configuration properties for Exposed cache health integration.
 */
@ConfigurationProperties("bluetape4k.exposed.cache.health")
class ExposedJdbcCacheHealthProperties {
    var enabled: Boolean = true
}

/**
 * Spring Boot [HealthIndicator] backed by JDBC Caffeine repository consistency reports.
 */
class ExposedJdbcCacheHealthIndicator(
    private val repositories: ObjectProvider<JdbcCaffeineRepository<*, *>>,
): HealthIndicator {

    override fun health(): Health {
        val reports = repositories.orderedStream()
            .map { it.validateConsistency() }
            .toList()

        val failure = reports.firstNotNullOfOrNull { it.lastFlushError }
        val stalled = reports.firstOrNull { it.isWriteBehindStalled() }

        val builder = when {
            failure != null -> Health.down(failure)
            stalled != null -> Health.outOfService()
            else            -> Health.up()
        }

        return builder
            .withDetail("repositoryCount", reports.size)
            .withDetail("reports", reports.map { it.toDetails() })
            .build()
    }
}

private fun CacheHealthReport.isWriteBehindStalled(): Boolean =
    mode == CacheWriteMode.WRITE_BEHIND && queueDepth > 0 && !isFlushJobRunning

private fun CacheHealthReport.toDetails(): Map<String, Any?> =
    mapOf(
        "mode" to mode.name,
        "queueDepth" to queueDepth,
        "flushJobRunning" to isFlushJobRunning,
        "lastFlushError" to lastFlushError?.message,
    )
