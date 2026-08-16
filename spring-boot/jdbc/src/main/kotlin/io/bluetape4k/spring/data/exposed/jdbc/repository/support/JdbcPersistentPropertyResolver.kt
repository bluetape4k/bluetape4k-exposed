package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentEntity
import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentProperty
import org.jetbrains.exposed.v1.core.Column
import org.springframework.dao.InvalidDataAccessApiUsageException

internal data class JdbcResolvedProperty(
    val logicalName: String,
    val column: Column<*>,
    val valueType: Class<*>,
    val nullable: Boolean,
    val persistentProperty: ExposedPersistentProperty,
)

internal class JdbcPersistentPropertyResolver(
    persistentEntity: ExposedPersistentEntity<*>,
) {

    private val properties: List<JdbcResolvedProperty> = buildList {
        persistentEntity.forEach { property ->
            property.getColumn()?.let { column ->
                add(
                    JdbcResolvedProperty(
                        logicalName = property.name,
                        column = column,
                        valueType = property.type,
                        nullable = column.columnType.nullable,
                        persistentProperty = property,
                    ),
                )
            }
        }
    }

    fun resolve(propertyName: String): JdbcResolvedProperty {
        if (propertyName.isBlank() || '.' in propertyName) {
            throw invalidProperty(propertyName, "only a non-empty flat property is supported")
        }

        val matches = properties.filter { property ->
            property.logicalName == propertyName ||
                toSnakeCase(property.logicalName) == propertyName ||
                property.column.name == propertyName ||
                toCamelCase(property.column.name) == propertyName
        }

        matches.singleOrNull()?.let { return it }
        val reason = if (matches.isEmpty()) "the property is unknown" else "the property is ambiguous"
        throw invalidProperty(propertyName, reason)
    }

    fun resolveAll(propertyNames: Collection<String>): List<JdbcResolvedProperty> =
        propertyNames.map(::resolve)

    private fun invalidProperty(propertyName: String, reason: String) =
        InvalidDataAccessApiUsageException(
            "FluentQuery property '$propertyName' is not supported: $reason.",
        )
}
