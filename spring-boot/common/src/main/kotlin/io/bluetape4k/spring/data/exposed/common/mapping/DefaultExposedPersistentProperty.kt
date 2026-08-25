package io.bluetape4k.spring.data.exposed.common.mapping

import io.bluetape4k.spring.data.exposed.common.repository.support.toSnakeCase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.springframework.data.mapping.Association
import org.springframework.data.mapping.PersistentEntity
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder

/** [ExposedPersistentProperty]의 기본 구현체입니다. */
class DefaultExposedPersistentProperty(
    property: Property,
    owner: PersistentEntity<*, ExposedPersistentProperty>,
    simpleTypeHolder: SimpleTypeHolder,
): AnnotationBasedPersistentProperty<ExposedPersistentProperty>(property, owner, simpleTypeHolder),
   ExposedPersistentProperty {

    private val table: Table? = (owner as? ExposedPersistentEntity<*>)?.getTable()

    override fun getColumn(): Column<*>? {
        val currentTable = table ?: return null
        return currentTable.columns.firstOrNull { column ->
            column.name.equals(name, ignoreCase = true) ||
                column.name.equals(toSnakeCase(name), ignoreCase = true)
        }
    }

    override fun createAssociation(): Association<ExposedPersistentProperty> = Association(this, null)
}
