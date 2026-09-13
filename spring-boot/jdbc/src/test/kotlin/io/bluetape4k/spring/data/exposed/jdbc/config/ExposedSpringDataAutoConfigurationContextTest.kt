package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.r2dbc.config.ExposedR2dbcSpringDataAutoConfiguration
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource
import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext as CommonExposedMappingContext

class ExposedSpringDataAutoConfigurationContextTest {

    companion object: KLogging()

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedSpringDataAutoConfiguration::class.java))

    @Test
    fun `uses application DatabaseConfig for the auto configured transaction manager`() {
        contextRunner
            .withUserConfiguration(DataSourceConfiguration::class.java, CustomDatabaseConfigConfiguration::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }

                context.getBean<DatabaseConfig>().defaultQueryTimeout shouldBeEqualTo 17

                val transactionManager = context.getBean(
                    "springTransactionManager",
                    PlatformTransactionManager::class.java,
                )
                TransactionTemplate(transactionManager).execute {
                    TransactionManager.current().queryTimeout shouldBeEqualTo 17
                    TransactionManager.current().db.config.useNestedTransactions.shouldBeTrue()
                }
            }
    }

    @Test
    fun `provides a default DatabaseConfig when the application does not define one`() {
        contextRunner
            .withUserConfiguration(DataSourceConfiguration::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }

                context.getBean(DatabaseConfig::class.java).defaultQueryTimeout shouldBeEqualTo 0
                context.containsBean("springTransactionManager").shouldBeTrue()
            }
    }

    @Test
    fun `backs off when the application provides the named transaction manager`() {
        contextRunner
            .withUserConfiguration(DataSourceConfiguration::class.java, UserTransactionManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType<DataSourceTransactionManager>() shouldHaveSize 1
                context.getBeansOfType<SpringTransactionManager>().shouldBeEmpty()
            }
    }

    @Test
    fun `does not register a transaction manager without a DataSource`() {
        contextRunner.run { context ->
            context.containsBean("springTransactionManager").shouldBeFalse()
            context.getBeansOfType<PlatformTransactionManager>().shouldBeEmpty()
        }
    }

    @Test
    fun `registers one common mapping context when JDBC and R2DBC auto-configurations are combined`() {
        contextRunner
            .withConfiguration(AutoConfigurations.of(ExposedR2dbcSpringDataAutoConfiguration::class.java))
            .run { context ->
                context.startupFailure?.let { throw it }

                context.getBeansOfType<CommonExposedMappingContext>() shouldHaveSize 1
                context.getBean("exposedMappingContext").shouldBeInstanceOf<CommonExposedMappingContext>()
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DataSourceConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource() = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CustomDatabaseConfigConfiguration {
        @Bean
        @Primary
        fun databaseConfig(): DatabaseConfig = DatabaseConfig {
            defaultQueryTimeout = 17
            useNestedTransactions = true
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class UserTransactionManagerConfiguration {
        @Bean("springTransactionManager")
        fun springTransactionManager(dataSource: DataSource): DataSourceTransactionManager =
            DataSourceTransactionManager(dataSource)
    }
}
