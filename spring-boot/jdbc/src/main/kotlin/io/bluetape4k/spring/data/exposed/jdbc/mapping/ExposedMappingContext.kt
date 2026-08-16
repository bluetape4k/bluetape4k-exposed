package io.bluetape4k.spring.data.exposed.jdbc.mapping

import org.springframework.beans.BeanUtils
import org.springframework.data.core.TypeInformation
import org.springframework.data.mapping.context.AbstractMappingContext
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder

/**
 * Exposed DAO Entity를 위한 Spring Data MappingContext 구현체입니다.
 *
 * ```kotlin
 * val context = ExposedMappingContext()
 * val entity = context.getRequiredPersistentEntity(User::class.java)
 * val table = entity.getTable()        // Users
 * val entityClass = entity.getEntityClass() // User.Companion
 * ```
 */
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
                        column.name.equals(
                            io.bluetape4k.spring.data.exposed.jdbc.repository.support.toSnakeCase(descriptor.name),
                            ignoreCase = true,
                        )
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
    ): ExposedPersistentProperty =
        DefaultExposedPersistentProperty(property, owner, simpleTypeHolder)
}
