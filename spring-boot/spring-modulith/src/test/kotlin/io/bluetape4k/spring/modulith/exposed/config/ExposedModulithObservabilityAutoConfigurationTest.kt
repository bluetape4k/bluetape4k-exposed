package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.bluetape4k.spring.modulith.exposed.UnloadableEventPublicationException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.modulith.events.EventPublication.Status
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.TargetEventPublication
import java.io.Serializable
import java.time.Instant
import java.util.UUID

class ExposedModulithObservabilityAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedModulithObservabilityAutoConfiguration::class.java))

    @Test
    fun `registers Exposed Modulith gauges when meter registry is present`() {
        contextRunner
            .withUserConfiguration(MeteredRepositoryConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeTrue()

                val registry = context.getBean(MeterRegistry::class.java)

                registry.publicationGauge("incomplete") shouldBeEqualTo 2.0
                registry.publicationGauge("completed") shouldBeEqualTo 1.0
                registry.publicationGauge("failed") shouldBeEqualTo 1.0
                registry.publicationGauge("unloadable") shouldBeEqualTo 1.0
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.spring.modulith.exposed.observability.enabled=false")
            .withUserConfiguration(MeteredRepositoryConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges when Micrometer is missing`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(MeterRegistry::class.java))
            .withUserConfiguration(RepositoryOnlyConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges without meter registry`() {
        contextRunner
            .withUserConfiguration(RepositoryOnlyConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges without repository`() {
        contextRunner
            .withUserConfiguration(MeterRegistryOnlyConfiguration::class.java)
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `can disable unloadable publication gauge`() {
        contextRunner
            .withPropertyValues("bluetape4k.spring.modulith.exposed.observability.include-unloadable=false")
            .withUserConfiguration(MeteredRepositoryConfiguration::class.java)
            .run { context ->
                val registry = context.getBean(MeterRegistry::class.java)

                registry.find(ExposedEventPublicationMetrics.METER_NAME)
                    .tag("state", "unloadable")
                    .gauge()
                    .shouldBeNull()
            }
    }

    @Test
    fun `adds configured low cardinality tags to Exposed Modulith gauges`() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.spring.modulith.exposed.observability.tags.application=orders",
                "bluetape4k.spring.modulith.exposed.observability.tags.environment=test",
            )
            .withUserConfiguration(MeteredRepositoryConfiguration::class.java)
            .run { context ->
                val registry = context.getBean(MeterRegistry::class.java)

                requireNotNull(
                    registry.find(ExposedEventPublicationMetrics.METER_NAME)
                        .tag("state", "incomplete")
                        .tag("completion.mode", "update")
                        .tag("application", "orders")
                        .tag("environment", "test")
                        .gauge()
                ).value() shouldBeEqualTo 2.0
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MeteredRepositoryConfiguration {

        @Bean
        fun meterRegistry(): MeterRegistry =
            SimpleMeterRegistry()

        @Bean
        fun exposedEventPublicationRepository(): ExposedEventPublicationRepository =
            meteredRepository()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class RepositoryOnlyConfiguration {

        @Bean
        fun exposedEventPublicationRepository(): ExposedEventPublicationRepository =
            meteredRepository()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MeterRegistryOnlyConfiguration {

        @Bean
        fun meterRegistry(): MeterRegistry =
            SimpleMeterRegistry()
    }

    private fun MeterRegistry.publicationGauge(state: String): Double =
        requireNotNull(
            find(ExposedEventPublicationMetrics.METER_NAME)
                .tag("state", state)
                .tag("completion.mode", "update")
                .gauge()
        ).value()

    companion object {

        private fun meteredRepository(): ExposedEventPublicationRepository {
            val incomplete = listOf(loadablePublication(), unloadablePublication())
            val failed = listOf(unloadablePublication())
            val completed = listOf(loadablePublication())

            return mockk(relaxed = true) {
                every { findIncompletePublications() } returns incomplete
                every { findCompletedPublications() } returns completed
                every { findFailedPublications(EventPublicationRepository.FailedCriteria.ALL) } returns failed
            }
        }

        private fun loadablePublication(): TargetEventPublication =
            mockk {
                every { event } returns TestEvent("loadable")
                every { status } returns Status.PUBLISHED
                every { publicationDate } returns Instant.parse("2026-05-16T00:00:00Z")
            }

        private fun unloadablePublication(): TargetEventPublication =
            mockk {
                every { event } throws UnloadableEventPublicationException(
                    identifier = UUID.fromString("018f4a28-85b8-7d6c-8a1b-463e0b468101"),
                    eventType = "com.example.MissingEvent",
                    listenerId = "listener.missing",
                    cause = ClassNotFoundException("com.example.MissingEvent"),
                )
                every { status } returns Status.FAILED
                every { publicationDate } returns Instant.parse("2026-05-16T00:00:00Z")
            }
    }

    data class TestEvent(val value: String) : Serializable {
        companion object {
            private const val serialVersionUID: Long = -3368753273455485545L
        }
    }
}
