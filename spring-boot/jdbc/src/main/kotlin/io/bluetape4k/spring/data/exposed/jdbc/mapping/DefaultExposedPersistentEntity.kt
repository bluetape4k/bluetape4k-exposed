package io.bluetape4k.spring.data.exposed.jdbc.mapping

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.dao.EntityClass
import org.springframework.data.core.TypeInformation
import org.springframework.data.mapping.model.BasicPersistentEntity
import kotlin.reflect.full.companionObjectInstance

/** JDBC 저장소의 기존 Exposed PersistentEntity 구현입니다. */
@Deprecated("common.mapping.DefaultExposedPersistentEntity를 사용하십시오.")
class DefaultExposedPersistentEntity<T: Any>(
    typeInformation: TypeInformation<T>,
): BasicPersistentEntity<T, ExposedPersistentProperty>(typeInformation), ExposedPersistentEntity<T> {

    private val entityClassInstance: EntityClass<*, *>? by lazy {
        runCatching {
            typeInformation.type.kotlin.companionObjectInstance as? EntityClass<*, *>
        }.getOrNull()
    }

    override fun getEntityClass(): EntityClass<*, *>? = entityClassInstance

    override fun getTable(): Table? = entityClassInstance?.table
}
