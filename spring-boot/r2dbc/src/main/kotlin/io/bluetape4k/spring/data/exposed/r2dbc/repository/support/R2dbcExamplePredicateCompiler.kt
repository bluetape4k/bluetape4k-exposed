package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.springframework.data.domain.ExampleMatcher

/** Detached QBE snapshot만 읽어 Exposed predicate를 생성하는 단일 compiler입니다. */
internal class R2dbcExamplePredicateCompiler(
    private val propertyResolver: R2dbcPersistentPropertyResolver,
) {

    fun compile(snapshot: R2dbcExampleSnapshot): Op<Boolean> {
        val conditions = snapshot.properties.mapNotNull { property ->
            val resolved = propertyResolver.resolve(property.property)
            if (property.value == null && property.includeNull) {
                resolved.column.isNull()
            } else {
                conditionFor(resolved, property.value, property.stringMatcher)
            }
        }
        if (conditions.isEmpty()) return Op.TRUE
        return if (snapshot.matchingAll) {
            conditions.reduce { left, right -> left and right }
        } else {
            conditions.reduce { left, right -> left or right }
        }
    }

    private fun conditionFor(
        property: R2dbcResolvedProperty,
        value: Any?,
        stringMatcher: ExampleMatcher.StringMatcher,
    ): Op<Boolean> {
        return when {
            value == null -> property.column.isNull()
            value !is String || stringMatcher in EXACT_STRING_MATCHERS -> {
                @Suppress("UNCHECKED_CAST")
                (property.column as org.jetbrains.exposed.v1.core.Column<Any>).eq(value)
            }
            else -> {
                val literal = literalPattern(value)
                val pattern = when (stringMatcher) {
                    ExampleMatcher.StringMatcher.CONTAINING -> LikePattern("%${literal.pattern}%", literal.escapeChar)
                    ExampleMatcher.StringMatcher.STARTING -> LikePattern("${literal.pattern}%", literal.escapeChar)
                    ExampleMatcher.StringMatcher.ENDING -> LikePattern("%${literal.pattern}", literal.escapeChar)
                    else -> error("Unsupported string matcher reached compiler")
                }
                @Suppress("UNCHECKED_CAST")
                (property.column as org.jetbrains.exposed.v1.core.Column<String?>).like(pattern)
            }
        }
    }

    private fun literalPattern(value: String): LikePattern {
        val escaped = buildString(value.length) {
            value.forEach { character ->
                if (character == '%' || character == '_' || character == '\\') append('\\')
                append(character)
            }
        }
        return LikePattern(escaped, '\\')
    }

    companion object {
        private val EXACT_STRING_MATCHERS = setOf(
            ExampleMatcher.StringMatcher.DEFAULT,
            ExampleMatcher.StringMatcher.EXACT,
        )
    }
}
