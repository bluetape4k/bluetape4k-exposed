package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWriteMode
import io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for R2DBC Caffeine repository consistency health.
 */
@AutoConfiguration
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
    name = [
        "io.bluetape4k.exposed.r2dbc.caffeine.repository.R2dbcCaffeineRepository",
        "org.springframework.boot.health.contributor.ReactiveHealthIndicator",
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.exposed.cache.health",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(ExposedR2dbcCacheHealthProperties::class)
class ExposedR2dbcCacheHealthAutoConfiguration {

    @Bean("exposedR2dbcCacheHealthIndicator")
    @ConditionalOnMissingBean(name = ["exposedR2dbcCacheHealthIndicator"])
    fun exposedR2dbcCacheHealthIndicator(
        repositories: ObjectProvider<R2dbcCaffeineRepository<*, *>>,
    ): ReactiveHealthIndicator =
        ExposedR2dbcCacheHealthIndicator(repositories)
}

/**
 * Configuration properties for Exposed cache health integration.
 */
@ConfigurationProperties("bluetape4k.exposed.cache.health")
class ExposedR2dbcCacheHealthProperties {
    var enabled: Boolean = true
}

/**
 * Spring Boot [ReactiveHealthIndicator] backed by R2DBC Caffeine repository consistency reports.
 */
class ExposedR2dbcCacheHealthIndicator(
    private val repositories: ObjectProvider<R2dbcCaffeineRepository<*, *>>,
): ReactiveHealthIndicator {

    override fun health() = mono {
        val reports = repositories.orderedStream()
            .toList()
            .map { it.validateConsistency() }

        val failure = reports.firstNotNullOfOrNull { it.lastFlushError }
        val stalled = reports.firstOrNull { it.isWriteBehindStalled() }

        val builder = when {
            failure != null -> Health.down(failure)
            stalled != null -> Health.outOfService()
            else            -> Health.up()
        }

        builder
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
