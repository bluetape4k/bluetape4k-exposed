package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.spring.data.exposed.common.mapping.ExposedPersistentProperty
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.Entity
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.mapping.MappingException
import org.springframework.data.mapping.PreferredConstructor
import org.springframework.data.mapping.model.PreferredConstructorDiscoverer
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.repository.query.ReturnedType
import java.beans.PropertyDescriptor
import java.lang.reflect.Constructor
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

internal interface JdbcProjectionShape<R: Any> {
    val requiredProperties: List<JdbcResolvedProperty>
    fun map(rowIndex: Int, values: Map<String, Any?>): R
}

internal class JdbcProjectionMapper(
    private val domainType: Class<*>,
    private val projectionFactory: ProjectionFactory,
    private val propertyResolver: JdbcPersistentPropertyResolver,
) {

    fun <R: Any> shape(
        resultType: Class<R>,
        explicitProperties: Collection<String>? = null,
    ): JdbcProjectionShape<R> {
        if (Entity::class.java.isAssignableFrom(resultType)) {
            if (!explicitProperties.isNullOrEmpty()) {
                throw InvalidDataAccessApiUsageException(
                    "Partial Entity projection is not supported; use as(ClosedProjection::class.java).",
                )
            }
            throw UnsupportedOperationException("Entity results use the full-row repository execution path.")
        }

        return if (resultType.isInterface) {
            interfaceShape(resultType, explicitProperties)
        } else {
            constructorShape(resultType, explicitProperties)
        }
    }

    private fun <R: Any> interfaceShape(
        resultType: Class<R>,
        explicitProperties: Collection<String>?,
    ): JdbcProjectionShape<R> {
        val projectionInformation = projectionFactory.getProjectionInformation(resultType)
        if (!projectionInformation.isClosed) {
            throw UnsupportedOperationException(
                "Open or SpEL projection '${resultType.name}' is not supported by JDBC FluentQuery; " +
                    "use a closed getter interface, Kotlin data class, or Java record.",
            )
        }

        val descriptors = projectionInformation.inputProperties
        val requiredNames = descriptors.map(PropertyDescriptor::getName)
        validateExplicitProperties(resultType, requiredNames, explicitProperties)
        val inputs = descriptors.map { descriptor -> descriptor to propertyResolver.resolve(descriptor.name) }
        val resolved = inputs.map { it.second }
        val nullable = resultType.kotlin.memberProperties.associate { it.name to it.returnType.isMarkedNullable }

        return shape(resolved) { rowIndex, values ->
            val source = LinkedHashMap<String, Any?>(resolved.size)
            inputs.forEach { (descriptor, property) ->
                source[descriptor.name] = validateValue(
                    rowIndex = rowIndex,
                    propertyName = descriptor.name,
                    expectedType = descriptor.propertyType,
                    nullable = nullable[descriptor.name] ?: !descriptor.propertyType.isPrimitive,
                    value = values[property.logicalName],
                )
            }
            projectionFactory.createProjection(resultType, source)
        }
    }

    private fun <R: Any> constructorShape(
        resultType: Class<R>,
        explicitProperties: Collection<String>?,
    ): JdbcProjectionShape<R> {
        val returnedType = ReturnedType.of(resultType, domainType, projectionFactory)
        val inputProperties = returnedType.inputProperties
        val preferred = discoverConstructor(resultType)
        val constructorNames = constructorNames(resultType, preferred)
        if (inputProperties != constructorNames) {
            throw UnsupportedOperationException(
                "Projection constructor inputs for '${resultType.name}' are not deterministic; " +
                    "use one preferred constructor whose named inputs match the projection properties.",
            )
        }

        validateExplicitProperties(resultType, inputProperties, explicitProperties)
        val resolved = propertyResolver.resolveAll(inputProperties)
        val constructor = preferred.constructor.apply { trySetAccessible() }
        val kotlinNullability = resultType.kotlin.primaryConstructor
            ?.parameters
            ?.mapIndexedNotNull { index, parameter -> parameter.name?.let { index to parameter.type.isMarkedNullable } }
            ?.toMap()
            .orEmpty()

        return shape(resolved) { rowIndex, values ->
            val arguments = constructor.parameterTypes.mapIndexed { index, expectedType ->
                val property = resolved[index]
                validateValue(
                    rowIndex = rowIndex,
                    propertyName = property.logicalName,
                    expectedType = expectedType,
                    nullable = kotlinNullability[index] ?: !expectedType.isPrimitive,
                    value = values[property.logicalName],
                )
            }.toTypedArray()
            instantiate(constructor, arguments, rowIndex)
        }
    }

    private fun <R: Any> constructorNames(
        resultType: Class<R>,
        preferred: PreferredConstructor<R, ExposedPersistentProperty>,
    ): List<String> {
        if (preferred.parameters.isEmpty()) {
            throw UnsupportedOperationException(
                "Projection type '${resultType.name}' must have at least one named constructor input; " +
                    "declare a closed projection with one or more mapped properties.",
            )
        }
        return preferred.parameters.map { parameter ->
            if (!parameter.hasName()) {
                throw UnsupportedOperationException(
                    "Projection constructor for '${resultType.name}' must expose parameter names; " +
                        "compile with Java '-parameters' or use Kotlin constructor metadata.",
                )
            }
            parameter.requiredName
        }
    }

    private fun validateExplicitProperties(
        resultType: Class<*>,
        requiredNames: List<String>,
        explicitProperties: Collection<String>?,
    ) {
        if (explicitProperties.isNullOrEmpty()) return
        val requested = explicitProperties.toSet()
        val required = requiredNames.toSet()
        if (requested != required || requested.size != explicitProperties.size) {
            throw InvalidDataAccessApiUsageException(
                "Projection properties for '${resultType.name}' must exactly match ${required.sorted()}.",
            )
        }
    }

    private fun validateValue(
        rowIndex: Int,
        propertyName: String,
        expectedType: Class<*>,
        nullable: Boolean,
        value: Any?,
    ): Any? {
        val detached = if (value is EntityID<*>) value.value else value
        if (detached == null) {
            if (nullable) return null
            throw mappingFailure(rowIndex, propertyName, expectedType)
        }
        if (!boxed(expectedType).isInstance(detached)) {
            throw mappingFailure(rowIndex, propertyName, expectedType)
        }
        return detached
    }

    private fun mappingFailure(rowIndex: Int, propertyName: String, expectedType: Class<*>) =
        MappingException(
            "Projection mapping failed at row $rowIndex for property '$propertyName'; expected ${expectedType.name}.",
        )

    private fun <R: Any> instantiate(
        constructor: Constructor<R>,
        arguments: Array<Any?>,
        rowIndex: Int,
    ): R = try {
        constructor.newInstance(*arguments)
    } catch (cause: ReflectiveOperationException) {
        throw MappingException(
            "Projection constructor invocation failed at row $rowIndex for '${constructor.declaringClass.name}'.",
            sanitizedReflectiveException(cause),
        )
    }

    private fun <R: Any> discoverConstructor(
        resultType: Class<R>,
    ): PreferredConstructor<R, ExposedPersistentProperty> =
        PreferredConstructorDiscoverer.discover<R, ExposedPersistentProperty>(resultType)
            ?: throw UnsupportedOperationException(
                "Projection type '${resultType.name}' must have one preferred named constructor; " +
                    "use a Kotlin data class or Java record with deterministic component names.",
            )

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> type
    }

    private fun <R: Any> shape(
        resolved: List<JdbcResolvedProperty>,
        mapper: (Int, Map<String, Any?>) -> R,
    ): JdbcProjectionShape<R> = object: JdbcProjectionShape<R> {
        override val requiredProperties: List<JdbcResolvedProperty> = resolved

        override fun map(rowIndex: Int, values: Map<String, Any?>): R = mapper(rowIndex, values)
    }
}
