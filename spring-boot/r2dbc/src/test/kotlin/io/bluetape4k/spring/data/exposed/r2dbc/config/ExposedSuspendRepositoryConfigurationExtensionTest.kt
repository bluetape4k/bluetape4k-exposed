package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.r2dbc.repository.config.EnableExposedR2dbcRepositories
import io.bluetape4k.spring.data.exposed.r2dbc.repository.config.ExposedSuspendRepositoryConfigurationExtension
import io.bluetape4k.spring.data.exposed.r2dbc.repository.config.ExposedR2dbcRepositoriesRegistrar
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.type.AnnotationMetadata
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource
import org.junit.jupiter.api.Test

class ExposedSuspendRepositoryConfigurationExtensionTest {

    @Test
    @Suppress("DEPRECATION")
    fun `registrar rejects custom transactionManagerRef before factory registration`() {
        val context = AnnotationConfigApplicationContext()
        try {
            val registrar = ExposedR2dbcRepositoriesRegistrar().apply {
                setResourceLoader(context)
                setEnvironment(context.environment)
            }

            assertFailsWith<IllegalArgumentException> {
                registrar.registerBeanDefinitions(
                    AnnotationMetadata.introspect(CustomManagerRegistrarConfig::class.java),
                    context,
                )
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `transactionManagerRef remains deprecated with its original default`() {
        val method = EnableExposedR2dbcRepositories::class.java
            .getDeclaredMethod("transactionManagerRef")

        method.defaultValue shouldBeEqualTo "springTransactionManager"
    }

    @Test
    fun `default transactionManagerRef remains accepted for ABI compatibility`() {
        val source = configurationSource(DefaultManagerConfig::class.java)
        val builder = BeanDefinitionBuilder.genericBeanDefinition(Any::class.java)

        ExposedSuspendRepositoryConfigurationExtension().postProcess(builder, source)
    }

    @Test
    fun `custom transactionManagerRef is rejected because R2DBC selects database explicitly`() {
        val source = configurationSource(CustomManagerConfig::class.java)
        val builder = BeanDefinitionBuilder.genericBeanDefinition(Any::class.java)

        assertFailsWith<IllegalArgumentException> {
            ExposedSuspendRepositoryConfigurationExtension().postProcess(builder, source)
        }
    }

    private fun configurationSource(configurationClass: Class<*>): AnnotationRepositoryConfigurationSource =
        AnnotationRepositoryConfigurationSource(
            AnnotationMetadata.introspect(configurationClass),
            EnableExposedR2dbcRepositories::class.java,
            DefaultResourceLoader(),
            StandardEnvironment(),
            DefaultListableBeanFactory(),
            null,
        )

    @Configuration
    @EnableExposedR2dbcRepositories(transactionManagerRef = "customTransactionManager")
    private class CustomManagerConfig

    @Configuration
    @EnableExposedR2dbcRepositories(
        basePackages = ["io.bluetape4k.spring.data.exposed.r2dbc.repository"],
        transactionManagerRef = "customTransactionManager",
    )
    private class CustomManagerRegistrarConfig

    @Configuration
    @EnableExposedR2dbcRepositories
    private class DefaultManagerConfig
}
