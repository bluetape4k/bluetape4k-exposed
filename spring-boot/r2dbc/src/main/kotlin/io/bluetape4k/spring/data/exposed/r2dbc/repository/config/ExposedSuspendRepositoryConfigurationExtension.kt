package io.bluetape4k.spring.data.exposed.r2dbc.repository.config

import io.bluetape4k.spring.data.exposed.jdbc.annotation.ExposedEntity
import io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedR2dbcRepository
import io.bluetape4k.spring.data.exposed.r2dbc.repository.support.ExposedR2dbcRepositoryFactoryBean
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource
import org.springframework.data.repository.config.RepositoryConfigurationExtensionSupport
import org.springframework.data.repository.core.RepositoryMetadata

/**
 * suspend Exposed Spring Data 모듈 설정 확장입니다.
 *
 * ```kotlin
 * // Spring Data 내부 인프라에서 사용됩니다. 직접 인스턴스화할 필요 없습니다.
 * // @EnableExposedR2dbcRepositories 어노테이션이 자동으로 이 클래스를 사용합니다.
 * // 모듈 이름: "SUSPEND_EXPOSED", Factory Bean: ExposedR2dbcRepositoryFactoryBean
 * ```
 */
class ExposedSuspendRepositoryConfigurationExtension: RepositoryConfigurationExtensionSupport() {

    companion object {
        private const val DEFAULT_TRANSACTION_MANAGER_REF = "springTransactionManager"
    }

    override fun getModuleName(): String = "SUSPEND_EXPOSED"

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getModulePrefix(): String = "suspendExposed"

    override fun getRepositoryFactoryBeanClassName(): String =
        ExposedR2dbcRepositoryFactoryBean::class.java.name

    /**
     * R2DBC 저장소는 Spring transaction interceptor를 우회하고 Exposed의
     * `suspendTransaction`을 직접 사용합니다. 따라서 `transactionManagerRef`로
     * 데이터베이스를 선택한다고 오해하지 않도록 비기본 설정을 등록 단계에서 거부합니다.
     */
    override fun postProcess(
        builder: BeanDefinitionBuilder,
        source: AnnotationRepositoryConfigurationSource,
    ) {
        val transactionManagerRef = source.attributes.getString("transactionManagerRef")
        require(transactionManagerRef == DEFAULT_TRANSACTION_MANAGER_REF) {
            "R2DBC 저장소는 transactionManagerRef='$transactionManagerRef'를 지원하지 않습니다. " +
                "명시적인 suspendTransaction(database) 경계 또는 streamAll(database)를 사용하세요."
        }
    }

    override fun getIdentifyingAnnotations(): Collection<Class<out Annotation>> =
        listOf(ExposedEntity::class.java)

    override fun getIdentifyingTypes(): Collection<Class<*>> =
        listOf(ExposedR2dbcRepository::class.java)

    /**
     * 코루틴/Flow 기반의 reactive repository를 지원합니다.
     * Spring Data의 reactive 체크를 우회하여 suspend/Flow 메서드를 포함한 모든 repository를 허용합니다.
     */
    override fun useRepositoryConfiguration(metadata: RepositoryMetadata): Boolean = true
}
