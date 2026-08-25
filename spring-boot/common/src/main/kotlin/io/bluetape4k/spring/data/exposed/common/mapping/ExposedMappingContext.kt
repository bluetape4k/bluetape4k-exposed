package io.bluetape4k.spring.data.exposed.common.mapping

import io.bluetape4k.spring.data.exposed.common.repository.support.toSnakeCase
import org.springframework.beans.BeanUtils
import org.springframework.data.core.TypeInformation
import org.springframework.data.mapping.context.AbstractMappingContext
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder

/** Exposed DAO Entity를 위한 Spring Data MappingContext 구현체입니다. */
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
