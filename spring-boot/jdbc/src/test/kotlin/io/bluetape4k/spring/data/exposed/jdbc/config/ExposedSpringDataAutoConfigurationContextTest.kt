package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
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

class ExposedSpringDataAutoConfigurationContextTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ExposedSpringDataAutoConfiguration::class.java))

    @Test
    fun `uses application DatabaseConfig for the auto configured transaction manager`() {
        contextRunner
            .withUserConfiguration(DataSourceConfiguration::class.java, CustomDatabaseConfigConfiguration::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }

                context.getBean(DatabaseConfig::class.java).defaultQueryTimeout shouldBeEqualTo 17

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
                context.getBeansOfType(DataSourceTransactionManager::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(SpringTransactionManager::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `does not register a transaction manager without a DataSource`() {
        contextRunner.run { context ->
            context.containsBean("springTransactionManager").not().shouldBeTrue()
            context.getBeansOfType(PlatformTransactionManager::class.java).isEmpty().shouldBeTrue()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class DataSourceConfiguration {
        @Bean(destroyMethod = "shutdown")
        fun dataSource(): DataSource = EmbeddedDatabaseBuilder()
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
