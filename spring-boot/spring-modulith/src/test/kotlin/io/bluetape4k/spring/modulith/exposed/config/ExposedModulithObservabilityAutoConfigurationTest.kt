package io.bluetape4k.spring.modulith.exposed.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.modulith.exposed.ExposedEventPublicationRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class ExposedModulithObservabilityAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedModulithObservabilityAutoConfiguration::class.java))

    @Test
    fun `registers Exposed Modulith gauges when meter registry is present`() {
        contextRunner
            .withUserConfiguration<MeteredRepositoryConfiguration>()
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
    fun `status gauges use count queries without materializing publications`() {
        val repository = mockk<ExposedEventPublicationRepository>(relaxed = true) {
            every { countIncompletePublications() } returns 2
            every { countCompletedPublications() } returns 1
            every { countFailedPublications() } returns 1
            every { countUnloadablePublications() } returns 1
        }
        val registry = SimpleMeterRegistry()
        ExposedEventPublicationMetrics(
            repository = repository,
            meterRegistry = registry,
            modulithProperties = ExposedModulithProperties(),
            observabilityProperties = ExposedModulithObservabilityProperties(includeUnloadable = true),
        )

        registry.publicationGauge("incomplete") shouldBeEqualTo 2.0
        registry.publicationGauge("completed") shouldBeEqualTo 1.0
        registry.publicationGauge("failed") shouldBeEqualTo 1.0
        registry.publicationGauge("unloadable") shouldBeEqualTo 1.0
        verify(exactly = 1) {
            repository.countIncompletePublications()
            repository.countCompletedPublications()
            repository.countFailedPublications()
            repository.countUnloadablePublications()
        }
        verify(exactly = 0) {
            repository.findIncompletePublications()
            repository.findCompletedPublications()
            repository.findFailedPublications(any())
        }
    }

    @Test
    fun `gauge query failures return NaN and increment a low cardinality counter`() {
        val repository = mockk<ExposedEventPublicationRepository>(relaxed = true) {
            every { countIncompletePublications() } throws IllegalStateException("database unavailable")
        }
        val registry = SimpleMeterRegistry()
        ExposedEventPublicationMetrics(
            repository = repository,
            meterRegistry = registry,
            modulithProperties = ExposedModulithProperties(),
            observabilityProperties = ExposedModulithObservabilityProperties(includeUnloadable = false),
        )

        registry.publicationGauge("incomplete").isNaN().shouldBeTrue()
        requireNotNull(
            registry.find(ExposedEventPublicationMetrics.ERROR_METER_NAME)
                .tag("state", "incomplete")
                .counter()
        ).count() shouldBeEqualTo 1.0
    }

    @Test
    fun `registers only low cardinality publication state tags`() {
        contextRunner
            .withUserConfiguration<MeteredRepositoryConfiguration>()
            .run { context ->
                val registry = context.getBean(MeterRegistry::class.java)
                val publicationMeters = registry.find(ExposedEventPublicationMetrics.METER_NAME).meters()
                val tagMaps = publicationMeters.map { meter ->
                    meter.id.tags.associate { tag -> tag.key to tag.value }
                }

                tagMaps.map { it.keys }.toSet() shouldBeEqualTo
                    setOf(setOf("completion.mode", "state"))
                tagMaps.flatMap { it.values }.toSet() shouldBeEqualTo
                    setOf("update", "incomplete", "completed", "failed", "unloadable")
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges when disabled`() {
        contextRunner
            .withPropertyValues("bluetape4k.spring.modulith.exposed.observability.enabled=false")
            .withUserConfiguration<MeteredRepositoryConfiguration>()
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges when Micrometer is missing`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(MeterRegistry::class.java))
            .withUserConfiguration<RepositoryOnlyConfiguration>()
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges without meter registry`() {
        contextRunner
            .withUserConfiguration<RepositoryOnlyConfiguration>()
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `does not register Exposed Modulith gauges without repository`() {
        contextRunner
            .withUserConfiguration<MeterRegistryOnlyConfiguration>()
            .run { context ->
                context.containsBean("exposedModulithEventPublicationMetrics").shouldBeFalse()
            }
    }

    @Test
    fun `can disable unloadable publication gauge`() {
        contextRunner
            .withPropertyValues("bluetape4k.spring.modulith.exposed.observability.include-unloadable=false")
            .withUserConfiguration<MeteredRepositoryConfiguration>()
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
            .withUserConfiguration<MeteredRepositoryConfiguration>()
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
            return mockk(relaxed = true) {
                every { countIncompletePublications() } returns 2
                every { countCompletedPublications() } returns 1
                every { countFailedPublications() } returns 1
                every { countUnloadablePublications() } returns 1
            }
        }
    }

}

private inline fun <reified T : Any> ApplicationContextRunner.withUserConfiguration(): ApplicationContextRunner =
    withUserConfiguration(T::class.java)
