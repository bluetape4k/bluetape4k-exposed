package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import java.lang.reflect.InvocationTargetException
import java.util.Optional
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/** Kotlin domain property와 Exposed [IdTable] column을 엄격하게 대응시킵니다. */
@Suppress("TooManyFunctions")
internal class R2dbcPersistentPropertyResolver(
    private val domainType: KClass<*>,
    private val table: IdTable<*>,
) {

    private val properties: List<R2dbcResolvedProperty> = buildList {
        domainType.memberProperties.forEach { property ->
            val column = columnFor(property.name) ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            add(
                R2dbcResolvedProperty(
                    logicalName = property.name,
                    column = column,
                    valueType = (property.returnType.classifier as? KClass<*>) ?: Any::class,
                    nullable = property.returnType.isMarkedNullable || column.columnType.nullable,
                    getter = property as KProperty1<Any, *>,
                ),
            )
        }
    }

    fun resolve(propertyName: String): R2dbcResolvedProperty {
        if (propertyName.isBlank() || '.' in propertyName) {
            throw invalidProperty(propertyName, "only a non-empty flat property is supported")
        }

        val matches = properties.filter { property ->
            property.logicalName == propertyName ||
                toSnakeCase(property.logicalName) == propertyName ||
                property.column.name == propertyName ||
                toCamelCase(property.column.name) == propertyName
        }
        return matches.singleOrNull()
            ?: throw invalidProperty(
                propertyName,
                if (matches.isEmpty()) "the property is unknown" else "the property is ambiguous",
            )
    }

    fun resolveAll(propertyNames: Collection<String>): List<R2dbcResolvedProperty> =
        propertyNames.map(::resolve)

    fun snapshot(example: Example<*>): R2dbcExampleSnapshot {
        val probe = example.probe
        validateProbe(probe)
        val matcher = example.matcher
        validateGlobalMatcher(matcher)
        val ignoredPaths = matcher.ignoredPaths.mapTo(linkedSetOf()) { resolve(it).logicalName }
        val specifiers = resolveSpecifiers(matcher)

        return R2dbcExampleSnapshot(
            expectedDomainType = domainType,
            matchingAll = matcher.isAllMatching,
            nullHandler = matcher.nullHandler,
            ignoredPaths = ignoredPaths,
            properties = snapshotProperties(probe, matcher, ignoredPaths, specifiers),
        )
    }

    private fun validateProbe(probe: Any) {
        if (probe::class != domainType) {
            throw R2dbcDiagnosticSanitizer.invalidUsage("QBE probe type does not match repository domain type")
        }
    }

    private fun resolveSpecifiers(
        matcher: ExampleMatcher,
    ): Map<String, ExampleMatcher.PropertySpecifier> = buildMap {
        matcher.propertySpecifiers.specifiers.forEach { specifier ->
            val resolved = resolve(specifier.path)
            if (specifier.ignoreCase == true) {
                throw UnsupportedOperationException(
                    "Ignore-case QBE matching is not supported for property " +
                        "'${R2dbcDiagnosticSanitizer.propertyToken(resolved.logicalName)}'.",
                )
            }
            if (put(resolved.logicalName, specifier) != null) {
                throw R2dbcDiagnosticSanitizer.invalidUsage("QBE matcher defines an aliased property more than once")
            }
        }
    }

    private fun snapshotProperties(
        probe: Any,
        matcher: ExampleMatcher,
        ignoredPaths: Set<String>,
        specifiers: Map<String, ExampleMatcher.PropertySpecifier>,
    ): List<R2dbcExamplePropertySnapshot> = buildList {
        properties.forEach { resolved ->
            if (resolved.logicalName !in ignoredPaths) {
                snapshotProperty(resolved, probe, matcher, specifiers[resolved.logicalName])?.let(::add)
            }
        }
    }

    private fun snapshotProperty(
        resolved: R2dbcResolvedProperty,
        probe: Any,
        matcher: ExampleMatcher,
        specifier: ExampleMatcher.PropertySpecifier?,
    ): R2dbcExamplePropertySnapshot? {
        val stringMatcher = specifier?.stringMatcher ?: matcher.defaultStringMatcher
        validateStringMatcher(resolved, stringMatcher)
        val rawValue = readValue(resolved, probe)
        val transformed = transformValue(specifier, rawValue)
        return when {
            transformed.isPresent -> R2dbcExamplePropertySnapshot(
                property = resolved.logicalName,
                value = R2dbcBindValueSnapshotter.snapshot(transformed.get()),
                stringMatcher = stringMatcher,
                ignoreCase = false,
                includeNull = false,
            )
            rawValue == null && matcher.nullHandler == ExampleMatcher.NullHandler.INCLUDE ->
                R2dbcExamplePropertySnapshot(
                    property = resolved.logicalName,
                    value = null,
                    stringMatcher = stringMatcher,
                    ignoreCase = false,
                    includeNull = true,
                )
            else -> null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun transformValue(
        specifier: ExampleMatcher.PropertySpecifier?,
        rawValue: Any?,
    ): Optional<*> = try {
        specifier?.transformValue(Optional.ofNullable(rawValue)) ?: Optional.ofNullable(rawValue)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Error) {
        throw error
    } catch (_: Exception) {
        throw R2dbcDiagnosticSanitizer.mapping("QBE property transformer failed")
    }

    private fun validateGlobalMatcher(matcher: ExampleMatcher) {
        if (matcher.isIgnoreCaseEnabled) {
            throw UnsupportedOperationException(
                "Ignore-case QBE matching is not supported; use case-sensitive matching.",
            )
        }
    }

    private fun validateStringMatcher(
        property: R2dbcResolvedProperty,
        matcher: ExampleMatcher.StringMatcher,
    ) {
        val message = when {
            matcher == ExampleMatcher.StringMatcher.REGEX ->
                "Regex QBE matching is not supported for property " +
                    "'${R2dbcDiagnosticSanitizer.propertyToken(property.logicalName)}'."
            matcher !in SUPPORTED_STRING_MATCHERS -> "Unsupported QBE string matcher"
            matcher !in EXACT_STRING_MATCHERS && property.valueType != String::class ->
                "String QBE matcher requires a String property " +
                    "'${R2dbcDiagnosticSanitizer.propertyToken(property.logicalName)}'."
            else -> null
        }
        if (message != null) throw UnsupportedOperationException(message)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readValue(property: R2dbcResolvedProperty, probe: Any): Any? = try {
        property.getter.isAccessible = true
        property.getter.get(probe)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Error) {
        throw error
    } catch (_: InvocationTargetException) {
        throw R2dbcDiagnosticSanitizer.mapping("QBE probe property could not be read")
    } catch (_: Exception) {
        throw R2dbcDiagnosticSanitizer.mapping("QBE probe property could not be read")
    }

    private fun columnFor(propertyName: String): Column<*>? =
        if (propertyName == "id") {
            table.id
        } else {
            val candidates = table.columns.filter { column ->
                column.name == propertyName ||
                    column.name == toSnakeCase(propertyName) ||
                    toCamelCase(column.name) == propertyName
            }
            candidates.singleOrNull()
        }

    private fun invalidProperty(propertyName: String, reason: String) =
        InvalidDataAccessApiUsageException(
            "QBE property '${R2dbcDiagnosticSanitizer.propertyToken(propertyName)}' is not supported: $reason.",
        )

    private fun toSnakeCase(value: String): String =
        value.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

    private fun toCamelCase(value: String): String =
        value.split('_')
            .filter { it.isNotEmpty() }
            .mapIndexed { index, part ->
                if (index == 0) part.lowercase() else part.lowercase().replaceFirstChar(Char::uppercase)
            }
            .joinToString("")

    companion object {
        private val EXACT_STRING_MATCHERS = setOf(
            ExampleMatcher.StringMatcher.DEFAULT,
            ExampleMatcher.StringMatcher.EXACT,
        )
        private val SUPPORTED_STRING_MATCHERS = EXACT_STRING_MATCHERS + setOf(
            ExampleMatcher.StringMatcher.CONTAINING,
            ExampleMatcher.StringMatcher.STARTING,
            ExampleMatcher.StringMatcher.ENDING,
        )
    }
}

internal data class R2dbcResolvedProperty(
    val logicalName: String,
    val column: Column<*>,
    val valueType: KClass<*>,
    val nullable: Boolean,
    val getter: KProperty1<Any, *>,
)
