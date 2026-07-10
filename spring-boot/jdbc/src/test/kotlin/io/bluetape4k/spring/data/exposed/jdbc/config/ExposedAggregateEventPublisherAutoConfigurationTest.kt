package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.exposed.core.ddd.AggregateRoot
import io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisher
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class ExposedAggregateEventPublisherAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedAggregateEventPublisherAutoConfiguration::class.java))

    @Test
    fun `does not register publisher without transaction manager`() {
        contextRunner.run { context ->
            context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `registers publisher with one transaction manager`() {
        contextRunner
            .withUserConfiguration(SingleManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `does not register publisher with two non-primary transaction managers`() {
        contextRunner
            .withUserConfiguration(TwoManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `registers publisher with one primary transaction manager among two`() {
        contextRunner
            .withUserConfiguration(PrimaryManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `backs off for application provided publisher`() {
        contextRunner
            .withUserConfiguration(SingleManagerConfiguration::class.java, CustomPublisherConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).keys shouldBeEqualTo
                        setOf("customAggregateEventPublisher")
            }
    }

    @Test
    fun `does not require Spring Modulith`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("org.springframework.modulith"))
            .withUserConfiguration(SingleManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `backs off when AggregateRoot is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(AggregateRoot::class.java))
            .withUserConfiguration(SingleManagerConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `backs off when transaction synchronization is absent`() {
        contextRunner
            .withClassLoader(FilteredClassLoader(TransactionSynchronizationManager::class.java))
            .withUserConfiguration(SingleManagerConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `class condition includes ApplicationEventPublisher`() {
        val condition = AnnotatedElementUtils.findMergedAnnotation(
            ExposedAggregateEventPublisherAutoConfiguration::class.java,
            ConditionalOnClass::class.java,
        ).shouldNotBeNull()

        condition.value.map { it.qualifiedName }
            .contains(ApplicationEventPublisher::class.java.name).shouldBeTrue()
    }

    @Test
    fun `runs after default Exposed transaction manager auto-configuration`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ExposedSpringDataAutoConfiguration::class.java,
                    ExposedAggregateEventPublisherAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(DataSourceOnlyConfiguration::class.java)
            .run { context ->
                context.containsBean("springTransactionManager").shouldBeTrue()
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DataSourceOnlyConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class SingleManagerConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()

        @Bean
        fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TwoManagerConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()

        @Bean
        fun firstTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun secondTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class PrimaryManagerConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()

        @Bean
        @Primary
        fun primaryTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun secondaryTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CustomPublisherConfiguration {
        @Bean
        fun customAggregateEventPublisher(
            applicationEventPublisher: ApplicationEventPublisher,
        ): ExposedAggregateEventPublisher = ExposedAggregateEventPublisher(applicationEventPublisher)
    }
}
