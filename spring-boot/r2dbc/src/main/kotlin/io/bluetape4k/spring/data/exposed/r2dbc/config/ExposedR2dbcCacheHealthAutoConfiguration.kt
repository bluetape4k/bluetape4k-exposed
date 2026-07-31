package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
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

/** R2DBC Caffeine repository의 consistency health를 제공하는 auto-configuration입니다. */
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

/** Exposed cache health integration의 configuration property입니다. */
@ConfigurationProperties("bluetape4k.exposed.cache.health")
class ExposedR2dbcCacheHealthProperties {
    var enabled: Boolean = true
}

/** R2DBC Caffeine repository consistency report를 사용하는 Spring Boot [ReactiveHealthIndicator]입니다. */
class ExposedR2dbcCacheHealthIndicator(
    private val repositories: ObjectProvider<R2dbcCaffeineRepository<*, *>>,
): ReactiveHealthIndicator {

    override fun health() = mono {
        val reports = repositories.orderedStream()
            .toList()
            .map { it.validateConsistency() }

        val failure = reports.selectFlushError()
        val failed = reports.any { it.workerState == CacheWorkerState.FAILED }
        val unavailable = reports.any { it.workerState.isUnavailable() }

        val builder = when {
            failure != null -> Health.down(failure)
            failed          -> Health.down()
            unavailable     -> Health.outOfService()
            else            -> Health.up()
        }

        builder
            .withDetail("repositoryCount", reports.size)
            .withDetail("reports", reports.map { it.toDetails() })
            .build()
    }
}

/** Repository 탐색 순서 대신 관측 가능한 exception data를 기준으로 failure를 선택합니다. */
private fun List<CacheHealthReport>.selectFlushError(): Throwable? =
    mapNotNull { it.lastFlushError }
        .minWithOrNull(
            compareBy(
                { it.javaClass.name },
                { it.message.orEmpty() },
                { it.toString() },
            )
        )

private fun CacheWorkerState.isUnavailable(): Boolean =
    this == CacheWorkerState.DRAINING || this == CacheWorkerState.STOPPED

private fun CacheHealthReport.toDetails(): Map<String, Any?> =
    buildMap {
        put("mode", mode.name)
        put("queueDepth", queueDepth)
        put("workerState", workerState.name)
        lastFlushError?.message?.let { put("lastFlushError", it) }
    }
