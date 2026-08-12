package io.bluetape4k.spring.data.exposed.r2dbc.repository.config

import io.bluetape4k.spring.data.exposed.r2dbc.repository.support.ExposedR2dbcRepositoryFactoryBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.data.repository.query.QueryLookupStrategy
import kotlin.reflect.KClass

/**
 * suspend 기반 Exposed Repository 스캐닝을 활성화합니다.
 *
 * ```kotlin
 * @SpringBootApplication
 * @EnableExposedR2dbcRepositories(basePackages = ["io.example.repository"])
 * class Application
 *
 * // 데이터베이스가 여러 개라면 저장소 메서드 바깥에서 대상을 명시합니다.
 * suspendTransaction(database) {
 *     userRepository.findAllAsList()
 * }
 * userRepository.streamAll(database)
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(ExposedR2dbcRepositoriesRegistrar::class)
annotation class EnableExposedR2dbcRepositories(
    vararg val value: String = [],
    val basePackages: Array<String> = [],
    val basePackageClasses: Array<KClass<*>> = [],
    val excludeFilters: Array<ComponentScan.Filter> = [],
    val includeFilters: Array<ComponentScan.Filter> = [],
    val repositoryFactoryBeanClass: KClass<*> = ExposedR2dbcRepositoryFactoryBean::class,
    val queryLookupStrategy: QueryLookupStrategy.Key = QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND,
    /**
     * R2DBC 저장소는 Spring transaction interceptor를 사용하지 않으므로 이 값으로
     * `R2dbcDatabase`를 선택하지 않습니다. ABI 호환성을 위해 유지하며, 기본값이 아닌
     * 값을 지정하면 저장소 등록 시 명확한 오류가 발생합니다.
     */
    @Deprecated(
        message = "R2DBC 저장소는 transactionManagerRef로 Exposed R2dbcDatabase를 선택하지 않습니다. " +
            "명시적인 suspendTransaction(database) 경계 또는 streamAll(database)를 사용하세요.",
    )
    val transactionManagerRef: String = "springTransactionManager",
    val namedQueriesLocation: String = "",
    val repositoryImplementationPostfix: String = "Impl",
)
