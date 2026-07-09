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
 * Auto-configuration for optional Micrometer gauges over the Exposed-backed
 * Spring Modulith event publication store.
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
 * Configuration properties for Exposed Spring Modulith store observability.
 */
@ConfigurationProperties("bluetape4k.spring.modulith.exposed.observability")
data class ExposedModulithObservabilityProperties(
    /**
     * Whether Exposed Spring Modulith store metrics are registered when
     * Micrometer is available.
     */
    val enabled: Boolean = true,

    /**
     * Whether to expose the gauge that detects incomplete publications whose
     * event type can no longer be loaded.
     */
    val includeUnloadable: Boolean = true,

    /**
     * Low-cardinality tags added to every Exposed Spring Modulith store metric.
     */
    val tags: Map<String, String> = emptyMap(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -8966263482426221897L
    }
}

/**
 * Registers Micrometer gauges for Exposed-backed Spring Modulith publication
 * state.
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
