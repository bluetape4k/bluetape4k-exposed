package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentEntity
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import java.lang.reflect.Method
import java.util.Optional

internal class JdbcExamplePredicateCompiler<E: Entity<ID>, ID: Any>(
    private val persistentEntity: ExposedPersistentEntity<E>,
    private val propertyResolver: JdbcPersistentPropertyResolver,
    private val entityClass: EntityClass<ID, E>,
    private val transaction: Transaction,
) {

    fun compile(example: Example<out E>): Op<Boolean> {
        val matcher = example.matcher
        val preparedProperties = validateMatcher(matcher)
        val probe = example.probe
        validateAttachedProbe(probe)

        val conditions = preparedProperties.mapNotNull { prepared ->
            if (prepared.ignored) return@mapNotNull null
            val property = prepared.property

            val rawValue = readProbeProperty(
                getter = property.persistentProperty.requiredGetter,
                probe = probe,
                propertyName = property.logicalName,
            )
            val transformed = prepared.transform(Optional.ofNullable(rawValue))

            if (transformed.isEmpty) {
                if (rawValue == null && matcher.nullHandler == ExampleMatcher.NullHandler.INCLUDE) {
                    property.column.isNull()
                } else {
                    null
                }
            } else {
                conditionFor(
                    property = property,
                    value = transformed.get(),
                    stringMatcher = prepared.stringMatcher,
                )
            }
        }

        if (conditions.isEmpty()) return Op.TRUE
        return if (matcher.isAnyMatching) {
            conditions.reduce { left, right -> left or right }
        } else {
            conditions.reduce { left, right -> left and right }
        }
    }

    private fun validateMatcher(matcher: ExampleMatcher): List<PreparedProperty> {
        if (matcher.isIgnoreCaseEnabled) {
            throw UnsupportedOperationException(
                "Ignore-case QBE matching is not supported by JDBC FluentQuery; " +
                    "use case-sensitive DEFAULT, EXACT, CONTAINING, STARTING, or ENDING matching.",
            )
        }

        val resolvedProperties = persistentEntity
            .mapNotNull { property ->
                property.getColumn()
                    ?.takeUnless { property.isIdProperty || property.name == "id" }
                    ?.let { propertyResolver.resolve(property.name) }
            }
            .distinctBy { it.logicalName }

        val ignoredLogicalNames = matcher.ignoredPaths.mapNotNullTo(linkedSetOf()) { path ->
            if (path == "id") null else propertyResolver.resolve(path).logicalName
        }

        val matcherByLogicalName = prepareMatchers(matcher)

        return resolvedProperties.map { property ->
            val preparedMatcher = matcherByLogicalName[property.logicalName]
            val stringMatcher = preparedMatcher?.stringMatcher ?: matcher.defaultStringMatcher
            val ignored = property.logicalName in ignoredLogicalNames
            if (!ignored) validateStringMatcher(property, stringMatcher)
            PreparedProperty(
                property = property,
                ignored = ignored,
                stringMatcher = stringMatcher,
                transform = preparedMatcher?.transform ?: { value -> value },
            )
        }
    }

    private fun prepareMatchers(matcher: ExampleMatcher): Map<String, PreparedMatcher> {
        val matcherByLogicalName = linkedMapOf<String, PreparedMatcher>()
        matcher.propertySpecifiers.specifiers.forEach { specifier ->
            val property = propertyResolver.resolve(specifier.path)
            if (specifier.ignoreCase == true) {
                throw UnsupportedOperationException(
                    "Ignore-case QBE matching is not supported for property " +
                        "'${safeDiagnosticValue(specifier.path)}'; use a case-sensitive matcher.",
                )
            }
            val stringMatcher = specifier.stringMatcher ?: matcher.defaultStringMatcher
            validateStringMatcher(property, stringMatcher)
            val previous = matcherByLogicalName.put(
                property.logicalName,
                PreparedMatcher(stringMatcher) { value -> specifier.transformValue(value) },
            )
            if (previous != null) {
                throw InvalidDataAccessApiUsageException(
                    "QBE matcher defines the same property more than once through aliases.",
                )
            }
        }
        return matcherByLogicalName
    }

    private fun validateStringMatcher(
        property: JdbcResolvedProperty,
        stringMatcher: ExampleMatcher.StringMatcher?,
    ) {
        val effective = stringMatcher ?: ExampleMatcher.StringMatcher.DEFAULT
        val propertyName = safeDiagnosticValue(property.logicalName)
        val failureMessage = when {
            effective == ExampleMatcher.StringMatcher.REGEX ->
                "Regex QBE matching is not supported for property '$propertyName'; " +
                    "use DEFAULT, EXACT, CONTAINING, STARTING, or ENDING."
            effective !in SUPPORTED_STRING_MATCHERS ->
                "QBE string matcher '$effective' is not supported for property '$propertyName'; " +
                    "use DEFAULT, EXACT, CONTAINING, STARTING, or ENDING."
            effective !in EXACT_STRING_MATCHERS && property.valueType != String::class.java ->
                "QBE string matcher '$effective' requires a String property '$propertyName'; " +
                    "use DEFAULT or EXACT for non-String properties."
            else -> null
        }
        if (failureMessage != null) throw UnsupportedOperationException(failureMessage)
    }

    private fun validateAttachedProbe(probe: E) {
        val readValues = probe._readValues
        val hasPendingIdentity = hasPendingIdentityWrite(probe)
        if (readValues == null || !readValues.hasValue(entityClass.table.id) || hasPendingIdentity) {
            throw invalidProbe()
        }
        val cached = transaction.entityCache.find(entityClass, probe.id)
        if (cached !== probe) {
            throw invalidProbe()
        }
    }

    /**
     * 신규 Entity의 ID는 insert 예약 상태에서 write-set에만 있으므로 내부 Exposed API 없이 구분할 수 있습니다.
     */
    private fun hasPendingIdentityWrite(probe: E): Boolean =
        probe.writeValues.keys.any { pendingColumn ->
            entityClass.table.idColumns.any { idColumn -> idColumn == pendingColumn }
        }

    private fun invalidProbe() = InvalidDataAccessApiUsageException(
        "QBE requires a persisted probe already loaded in the current Exposed transaction.",
    )

    private fun conditionFor(
        property: JdbcResolvedProperty,
        value: Any,
        stringMatcher: ExampleMatcher.StringMatcher,
    ): Op<Boolean> {
        validateStringMatcher(property, stringMatcher)
        if (value !is String || stringMatcher in EXACT_STRING_MATCHERS) {
            @Suppress("UNCHECKED_CAST")
            return (property.column as Column<Any>).eq(value)
        }

        val literal = LikePattern.ofLiteral(value)
        val pattern = when (stringMatcher) {
            ExampleMatcher.StringMatcher.CONTAINING -> LikePattern("%${literal.pattern}%", literal.escapeChar)
            ExampleMatcher.StringMatcher.STARTING -> LikePattern("${literal.pattern}%", literal.escapeChar)
            ExampleMatcher.StringMatcher.ENDING -> LikePattern("%${literal.pattern}", literal.escapeChar)
            else -> error("Unsupported matcher reached condition compilation: $stringMatcher")
        }
        @Suppress("UNCHECKED_CAST")
        return (property.column as Column<String?>).like(pattern)
    }

    companion object {
        /**
         * probe getter가 던진 내부 예외와 message를 caller 진단 경계 밖으로 노출하지 않습니다.
         */
        @JvmSynthetic
        internal fun readProbeProperty(
            getter: Method,
            probe: Any,
            propertyName: String,
        ): Any? = try {
            getter.invoke(probe)
        } catch (cause: ReflectiveOperationException) {
            throw InvalidDataAccessApiUsageException(
                "QBE probe property '${safeDiagnosticValue(propertyName)}' could not be read.",
                sanitizedReflectiveException(cause),
            )
        }

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

    private data class PreparedMatcher(
        val stringMatcher: ExampleMatcher.StringMatcher,
        val transform: (Optional<Any>) -> Optional<Any>,
    )

    private data class PreparedProperty(
        val property: JdbcResolvedProperty,
        val ignored: Boolean,
        val stringMatcher: ExampleMatcher.StringMatcher,
        val transform: (Optional<Any>) -> Optional<Any>,
    )
}
