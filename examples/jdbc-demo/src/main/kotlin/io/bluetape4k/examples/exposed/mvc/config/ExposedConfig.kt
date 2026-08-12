package io.bluetape4k.examples.exposed.mvc.config

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.annotation.EnableTransactionManagement
import javax.sql.DataSource

/**
 * Exposed + Spring Boot 수동 트랜잭션 설정.
 *
 * exposed-spring-boot-starter의 ExposedAutoConfiguration은 Spring Boot 전용
 * DataSourceAutoConfiguration을 참조하므로, 명시적으로 SpringTransactionManager를 구성한다.
 */
@Configuration
@EnableTransactionManagement
class ExposedConfig {

    @Bean
    fun springTransactionManager(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig,
    ): SpringTransactionManager = SpringTransactionManager(dataSource, databaseConfig, false)

    @Bean
    fun databaseConfig(): DatabaseConfig = DatabaseConfig {}
}
