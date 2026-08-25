package io.bluetape4k.spring.data.exposed.jdbc.mapping

import io.bluetape4k.spring.data.exposed.jdbc.repository.support.toSnakeCase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.springframework.data.mapping.Association
import org.springframework.data.mapping.PersistentEntity
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty
import org.springframework.data.mapping.model.Property
import org.springframework.data.mapping.model.SimpleTypeHolder

/** JDBC 저장소의 기존 Exposed PersistentProperty 구현입니다. */
@Deprecated("common.mapping.DefaultExposedPersistentProperty를 사용하십시오.")
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
