package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.projection.ProjectionFactory
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/** selected ResultRow를 full domain 또는 detached closed projection으로 변환합니다. */
internal class R2dbcProjectionMapper(
    private val domainType: KClass<*>,
    private val toDomainMapper: (ResultRow) -> Any,
    private val propertyResolver: R2dbcPersistentPropertyResolver,
    private val projectionFactory: ProjectionFactory,
) {

    @Suppress("UNCHECKED_CAST", "ThrowsCount", "TooGenericExceptionCaught")
    fun <T: Any> map(
        row: ResultRow,
        resultType: KClass<T>,
        explicitProperties: List<String>?,
    ): T {
        if (resultType == domainType) {
            if (!explicitProperties.isNullOrEmpty()) {
                throw UnsupportedOperationException("Partial domain projection is not supported")
            }
            return toDomainMapper(row) as T
        }

        val properties = projectionProperties(resultType, explicitProperties)
        val values = linkedMapOf<String, Any?>()
        properties.forEach { property ->
            val resolved = propertyResolver.resolve(property)
            values[resolved.logicalName] = unwrap(row[resolved.column])
        }

        return try {
            when {
                resultType.java.isInterface -> {
                    val information = projectionFactory.getProjectionInformation(resultType.java)
                    if (!information.isClosed) {
                        throw UnsupportedOperationException("Open or SpEL projections are not supported")
                    }
                    projectionFactory.createProjection(resultType.java, values) as T
                }
                resultType.java.isRecord -> instantiateRecord(resultType.java, values) as T
                else -> instantiateKotlin(resultType, values) as T
            }
        } catch (unsupported: UnsupportedOperationException) {
            throw unsupported
        } catch (error: Error) {
            throw error
        } catch (_: Exception) {
            throw R2dbcDiagnosticSanitizer.mapping("Projection mapping failed")
        }
    }

    fun requiredProperties(resultType: KClass<*>): List<String> =
        projectionProperties(resultType, explicitProperties = null)

    private fun projectionProperties(resultType: KClass<*>, explicitProperties: List<String>?): List<String> {
        return when {
            !explicitProperties.isNullOrEmpty() -> explicitProperties.distinct()
            resultType.java.isInterface -> closedInterfaceProperties(resultType)
            resultType.java.isRecord -> resultType.java.recordComponents.map { it.name }
            else -> resultType.primaryConstructor?.parameters?.mapNotNull { it.name }
                ?: throw UnsupportedOperationException("Projection requires a named constructor")
        }
    }

    private fun closedInterfaceProperties(resultType: KClass<*>): List<String> {
        val information = projectionFactory.getProjectionInformation(resultType.java)
        if (!information.isClosed) {
            throw UnsupportedOperationException("Open or SpEL projections are not supported")
        }
        return information.inputProperties.map { it.name }
    }

    @Suppress("ThrowsCount")
    private fun instantiateKotlin(resultType: KClass<*>, values: Map<String, Any?>): Any {
        val constructor = resultType.primaryConstructor
            ?: throw UnsupportedOperationException("Projection requires a primary constructor")
        constructor.isAccessible = true
        val arguments = constructor.parameters.associateWith { parameter ->
            val name = parameter.name
                ?: throw R2dbcDiagnosticSanitizer.mapping("Projection constructor parameter is unnamed")
            if (!values.containsKey(name)) {
                throw InvalidDataAccessApiUsageException(
                    "Projection property '${R2dbcDiagnosticSanitizer.propertyToken(name)}' is not selected",
                )
            }
            val value = values[name]
            if (value == null && !parameter.type.isMarkedNullable && !parameter.isOptional) {
                throw R2dbcSanitizedMappingException("Projection non-null property is null")
            }
            value
        }
        return constructor.callBy(arguments)
    }

    private fun instantiateRecord(type: Class<*>, values: Map<String, Any?>): Any {
        val components = type.recordComponents
        val constructor = type.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
        val arguments = components.map { component ->
            if (!values.containsKey(component.name)) {
                throw InvalidDataAccessApiUsageException(
                    "Projection property '${R2dbcDiagnosticSanitizer.propertyToken(component.name)}' is not selected",
                )
            }
            values[component.name]
        }.toTypedArray()
        return constructor.newInstance(*arguments)
    }

    private fun unwrap(value: Any?): Any? =
        if (value is EntityID<*>) value.value else value
}
