package io.bluetape4k.spring.data.exposed.jdbc.config

import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext as CommonExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedMappingContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/**
 * Spring Data Exposed 자동 설정입니다.
 * Exposed `EntityClass`가 classpath에 있을 때 활성화됩니다.
 *
 * ```kotlin
 * // Spring Boot 자동 등록 — 별도 설정 불필요
 * // @EnableExposedJdbcRepositories 어노테이션과 함께 사용됩니다.
 * @SpringBootApplication
 * @EnableExposedJdbcRepositories(basePackages = ["io.example.repository"])
 * class Application
 * ```
 */
@AutoConfiguration
@ConditionalOnClass(EntityClass::class)
@Configuration(proxyBeanMethods = false)
class ExposedSpringDataAutoConfiguration {

    /**
     * 기존 JDBC 자동 설정 호출자의 binary compatibility를 유지하는 facade 생성 메서드입니다.
     * 실제 Spring bean은 common mapping context를 사용합니다.
     */
    fun exposedMappingContext(): ExposedMappingContext = ExposedMappingContext()

    @Bean("exposedMappingContext")
    @ConditionalOnMissingBean(
        value = [CommonExposedMappingContext::class],
        name = ["exposedMappingContext"],
    )
    private fun commonExposedMappingContext(): CommonExposedMappingContext = CommonExposedMappingContext()

    /**
     * 애플리케이션이 별도 설정을 제공하지 않을 때 사용할 Exposed 기본 설정입니다.
     */
    @Bean
    @ConditionalOnMissingBean(DatabaseConfig::class)
    fun databaseConfig(): DatabaseConfig = DatabaseConfig {}

    @Bean("springTransactionManager")
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(name = ["springTransactionManager"])
    fun springTransactionManager(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig,
    ): PlatformTransactionManager = SpringTransactionManager(dataSource, databaseConfig, false)
}
