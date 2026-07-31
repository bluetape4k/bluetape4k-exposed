package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.UnloadableEventPublicationException
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.core.EventPublicationRepository
import java.io.Serializable

/**
 * Exposed 기반 Spring Modulith 이벤트 게시 저장소에 선택적 Micrometer gauge를 구성합니다.
 */
@AutoConfiguration(after = [ExposedModulithAutoConfiguration::class])
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
    name = [
        "io.micrometer.core.instrument.MeterRegistry",
        "org.springframework.modulith.events.core.EventPublicationRepository",
    ]
)
@ConditionalOnBean(
    value = [
        ExposedEventPublicationRepository::class,
        MeterRegistry::class,
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.spring.modulith.exposed.observability",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(
    ExposedModulithProperties::class,
    ExposedModulithObservabilityProperties::class,
)
class ExposedModulithObservabilityAutoConfiguration {

    @Bean("exposedModulithEventPublicationMetrics")
    @ConditionalOnMissingBean(name = ["exposedModulithEventPublicationMetrics"])
    fun exposedModulithEventPublicationMetrics(
        repository: ExposedEventPublicationRepository,
        meterRegistry: MeterRegistry,
        modulithProperties: ExposedModulithProperties,
        observabilityProperties: ExposedModulithObservabilityProperties,
    ): ExposedEventPublicationMetrics =
        ExposedEventPublicationMetrics(
            repository = repository,
            meterRegistry = meterRegistry,
            modulithProperties = modulithProperties,
            observabilityProperties = observabilityProperties,
        )
}

/**
 * Exposed Spring Modulith 저장소 관측성 구성 속성입니다.
 */
@ConfigurationProperties("bluetape4k.spring.modulith.exposed.observability")
data class ExposedModulithObservabilityProperties(
    /**
     * Micrometer를 사용할 수 있을 때 Exposed Spring Modulith 저장소 메트릭을 등록할지 여부입니다.
     */
    val enabled: Boolean = true,

    /**
     * 이벤트 타입을 더 이상 로드할 수 없는 미완료 게시를 감지하는 gauge를 노출할지 여부입니다.
     */
    val includeUnloadable: Boolean = true,

    /**
     * 모든 Exposed Spring Modulith 저장소 메트릭에 추가할 낮은 카디널리티 태그입니다.
     */
    val tags: Map<String, String> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -8966263482426221897L
    }
}

/**
 * Exposed 기반 Spring Modulith 게시 상태를 나타내는 Micrometer gauge를 등록합니다.
 */
class ExposedEventPublicationMetrics(
    private val repository: ExposedEventPublicationRepository,
    meterRegistry: MeterRegistry,
    modulithProperties: ExposedModulithProperties,
    observabilityProperties: ExposedModulithObservabilityProperties,
) {

    private val commonTags = Tags.of(
        "completion.mode",
        modulithProperties.completionMode.name.lowercase(),
    ).and(
        observabilityProperties.tags.map { (key, value) -> Tag.of(key, value) }
    )

    init {
        registerGauge(
            meterRegistry = meterRegistry,
            state = "incomplete",
            description = "Incomplete Exposed-backed Spring Modulith event publications.",
        ) {
            repository.findIncompletePublications().size
        }

        registerGauge(
            meterRegistry = meterRegistry,
            state = "completed",
            description = "Completed Exposed-backed Spring Modulith event publications.",
        ) {
            repository.findCompletedPublications().size
        }

        registerGauge(
            meterRegistry = meterRegistry,
            state = "failed",
            description = "Failed Exposed-backed Spring Modulith event publications.",
        ) {
            repository.findFailedPublications(EventPublicationRepository.FailedCriteria.ALL).size
        }

        if (observabilityProperties.includeUnloadable) {
            registerGauge(
                meterRegistry = meterRegistry,
                state = "unloadable",
                description = "Incomplete Exposed-backed Spring Modulith publications with unloadable event types.",
            ) {
                countUnloadablePublications()
            }
        }
    }

    private fun registerGauge(
        meterRegistry: MeterRegistry,
        state: String,
        description: String,
        count: () -> Int,
    ) {
        Gauge.builder(METER_NAME, this) { metrics ->
            metrics.safeCount(count)
        }
            .description(description)
            .tags(commonTags.and("state", state))
            .register(meterRegistry)
    }

    private fun safeCount(count: () -> Int): Double =
        runCatching { count().toDouble() }
            .getOrDefault(Double.NaN)

    private fun countUnloadablePublications(): Int =
        repository.findIncompletePublications()
            .count { publication ->
                runCatching { publication.event }
                    .exceptionOrNull() is UnloadableEventPublicationException
            }

    companion object {
        const val METER_NAME: String = "bluetape4k.exposed.modulith.publications"
    }
}
