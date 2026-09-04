package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.repository.config.EnableExposedJdbcRepositories
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@SpringBootTest(classes = [ExposedSpringDataAutoConfigurationTest.TestConfig::class])
class ExposedSpringDataAutoConfigurationTest {

    companion object : KLogging()

    @Configuration
    @EnableAutoConfiguration(
        excludeName = [
            "org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration",
            "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration",
        ]
    )
    @EnableExposedJdbcRepositories(
        basePackages = ["io.bluetape4k.spring.data.exposed.jdbc.repository"],
        transactionManagerRef = "secondTransactionManager",
    )
    class TestConfig {
        @Bean("springTransactionManager")
        fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            SpringTransactionManager(dataSource, DatabaseConfig {}, false)

        @Bean("secondTransactionManager")
        fun secondTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            SpringTransactionManager(dataSource, DatabaseConfig {}, false)
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var exposedMappingContext: ExposedMappingContext

    @Test
    fun `ExposedMappingContext bean is registered in context`() {
        exposedMappingContext.shouldNotBeNull()
    }

    @Test
    fun `ApplicationContext contains ExposedMappingContext bean`() {
        val bean = applicationContext.getBean(ExposedMappingContext::class.java)
        bean.shouldNotBeNull()
    }

    @Test
    fun `ApplicationContext contains PlatformTransactionManager bean`() {
        val txManager = applicationContext.getBean("secondTransactionManager", PlatformTransactionManager::class.java)
        txManager.shouldNotBeNull()
    }

    @Test
    fun `repository factory bean uses configured transaction manager reference`() {
        val beanFactory = (applicationContext as ConfigurableApplicationContext).beanFactory
        val definition = beanFactory.getBeanDefinition("userJdbcRepository")

        definition.propertyValues.get("transactionManager") shouldBeEqualTo "secondTransactionManager"
    }

    @Test
    fun `ExposedMappingContext can resolve UserEntity persistent entity`() {
        val entity = exposedMappingContext.getRequiredPersistentEntity(
            io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity::class.java
        )
        entity.shouldNotBeNull()
        entity.getTable().shouldNotBeNull()
    }
}
