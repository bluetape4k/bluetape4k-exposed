package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.exposed.cache.CacheHealthReport
import io.bluetape4k.exposed.cache.CacheWorkerState
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

/** JDBC Caffeine repository 일관성 상태를 위한 자동 설정입니다. */
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

/** Exposed cache health 통합 설정 속성입니다. */
@ConfigurationProperties("bluetape4k.exposed.cache.health")
class ExposedJdbcCacheHealthProperties {
    var enabled: Boolean = true
}

/** JDBC Caffeine repository 일관성 보고서를 사용하는 Spring Boot [HealthIndicator]입니다. */
class ExposedJdbcCacheHealthIndicator(
    private val repositories: ObjectProvider<JdbcCaffeineRepository<*, *>>,
): HealthIndicator {

    override fun health(): Health {
        val reports = repositories.orderedStream()
            .map { it.validateConsistency() }
            .toList()

        val failure = reports.selectFlushError()
        val failed = reports.any { it.workerState == CacheWorkerState.FAILED }
        val unavailable = reports.any { it.workerState.isUnavailable() }

        val builder = when {
            failure != null -> Health.down(failure)
            failed          -> Health.down()
            unavailable     -> Health.outOfService()
            else            -> Health.up()
        }

        return builder
            .withDetail("repositoryCount", reports.size)
            .withDetail("reports", reports.map { it.toDetails() })
            .build()
    }
}

/** repository 탐색 순서가 아니라 관찰 가능한 exception data로 실패를 선택합니다. */
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
