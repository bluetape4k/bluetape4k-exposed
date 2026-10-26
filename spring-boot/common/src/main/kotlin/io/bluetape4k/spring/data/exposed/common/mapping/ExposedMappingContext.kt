package io.bluetape4k.spring.data.exposed.common.mapping

import io.bluetape4k.spring.data.exposed.common.repository.support.snakeCase
import org.springframework.beans.BeanUtils
import org.springframework.data.core.TypeInformation
import org.springframework.data.mapping.PersistentPropertyPaths
import org.springframework.data.mapping.context.AbstractMappingContext
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder
import java.util.function.Predicate

/** Exposed DAO Entity를 위한 Spring Data MappingContext 구현체입니다. */
class ExposedMappingContext:
    AbstractMappingContext<DefaultExposedPersistentEntity<*>, ExposedPersistentProperty>() {

    override fun <T: Any> createPersistentEntity(
        typeInformation: TypeInformation<T>,
    ): DefaultExposedPersistentEntity<T> =
        DefaultExposedPersistentEntity(typeInformation).also { entity ->
            val table = entity.getTable() ?: return@also

            BeanUtils.getPropertyDescriptors(typeInformation.type)
                .asSequence()
                .filter { descriptor -> descriptor.readMethod?.declaringClass == typeInformation.type }
                .filter { descriptor ->
                    table.columns.any { column ->
                        column.name == descriptor.name ||
                        column.name.equals(descriptor.name.snakeCase(), ignoreCase = true)
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

inline fun <reified T: Any> ExposedMappingContext.getPersistentEntity(): DefaultExposedPersistentEntity<*>? =
    getPersistentEntity(T::class.java)

inline fun <reified T: Any> ExposedMappingContext.getRequiredPersistentEntity(): DefaultExposedPersistentEntity<*> =
    getRequiredPersistentEntity(T::class.java)

inline fun <reified T: Any> ExposedMappingContext.hasPersistentEntityFor(): Boolean =
    hasPersistentEntityFor(T::class.java)


inline fun <reified T: Any> ExposedMappingContext.findPersistentPropertyPaths(
    predicate: Predicate<ExposedPersistentProperty>,
): PersistentPropertyPaths<T, ExposedPersistentProperty> =
    findPersistentPropertyPaths(T::class.java, predicate)
