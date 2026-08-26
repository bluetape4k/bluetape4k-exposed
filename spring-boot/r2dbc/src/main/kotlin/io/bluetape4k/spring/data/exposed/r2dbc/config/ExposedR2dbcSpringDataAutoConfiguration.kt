package io.bluetape4k.spring.data.exposed.r2dbc.config

import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext
import org.jetbrains.exposed.v1.dao.EntityClass
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 코루틴 기반 Spring Data Exposed 자동 설정입니다.
 * JDBC 자동 설정과 독립적으로 실행되며 공통 Spring Data mapping context만 등록합니다.
 *
 * ```kotlin
 * // Spring Boot 자동 등록 — 별도 설정 불필요
 * // @EnableExposedR2dbcRepositories 어노테이션과 함께 사용됩니다.
 * @SpringBootApplication
 * @EnableExposedR2dbcRepositories(basePackages = ["io.example.repository"])
 * class Application
 * ```
 */
@AutoConfiguration
@ConditionalOnClass(EntityClass::class)
@Configuration(proxyBeanMethods = false)
class ExposedR2dbcSpringDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(
        value = [ExposedMappingContext::class],
        name = ["exposedMappingContext"],
    )
    fun exposedMappingContext(): ExposedMappingContext = ExposedMappingContext()
}
