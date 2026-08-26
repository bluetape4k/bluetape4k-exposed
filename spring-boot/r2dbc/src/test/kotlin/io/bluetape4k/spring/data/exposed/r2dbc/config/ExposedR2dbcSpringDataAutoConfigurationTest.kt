package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean

class ExposedR2dbcSpringDataAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedR2dbcSpringDataAutoConfiguration::class.java))

    @Test
    fun `registers the common mapping context in an R2DBC-only context`() {
        contextRunner.run { context ->
            context.getBeansOfType(ExposedMappingContext::class.java).size shouldBeEqualTo 1
            context.getBean("exposedMappingContext") shouldBeEqualTo
                context.getBean(ExposedMappingContext::class.java)
        }
    }

    @Test
    fun `backs off when a combined application already owns the mapping bean name`() {
        contextRunner
            .withUserConfiguration(ExistingMappingContextConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedMappingContext::class.java).size shouldBeEqualTo 1
                (
                    context.getBean("exposedMappingContext") === ExistingMappingContextConfiguration.context
                ).shouldBeTrue()
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ExistingMappingContextConfiguration {

        companion object {
            lateinit var context: ExposedMappingContext
        }

        @Bean("exposedMappingContext")
        fun exposedMappingContext(): ExposedMappingContext = ExposedMappingContext().also { context = it }
    }
}
