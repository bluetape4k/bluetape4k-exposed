package io.bluetape4k.spring.data.exposed.jdbc.mapping

import io.bluetape4k.spring.data.exposed.jdbc.repository.support.toSnakeCase
import org.springframework.beans.BeanUtils
import org.springframework.data.core.TypeInformation
import org.springframework.data.mapping.context.AbstractMappingContext
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder

/**
 * JDBC 저장소의 기존 MappingContext 구현입니다.
 *
 * @deprecated common.mapping.ExposedMappingContext를 사용하십시오. JDBC binary facade로만 유지됩니다.
 */
@Deprecated("common.mapping.ExposedMappingContext를 사용하십시오.")
class ExposedMappingContext:
    AbstractMappingContext<DefaultExposedPersistentEntity<*>, ExposedPersistentProperty>() {

    override fun <T: Any> createPersistentEntity(
        typeInformation: TypeInformation<T>,
    ): DefaultExposedPersistentEntity<T> = DefaultExposedPersistentEntity(typeInformation).also { entity ->
        val table = entity.getTable() ?: return@also
        BeanUtils.getPropertyDescriptors(typeInformation.type)
            .asSequence()
            .filter { descriptor -> descriptor.readMethod?.declaringClass == typeInformation.type }
            .filter { descriptor ->
                table.columns.any { column ->
                    column.name == descriptor.name ||
                        column.name.equals(toSnakeCase(descriptor.name), ignoreCase = true)
                }
            }
            .map { descriptor -> Property.of(typeInformation, descriptor) }
            .map { property -> DefaultExposedPersistentProperty(property, entity, SimpleTypeHolder.DEFAULT) }
            .forEach(entity::addPersistentProperty)
    }

    override fun createPersistentProperty(
        property: Property,
        owner: DefaultExposedPersistentEntity<*>,
        simpleTypeHolder: SimpleTypeHolder,
    ): ExposedPersistentProperty = DefaultExposedPersistentProperty(property, owner, simpleTypeHolder)
}
