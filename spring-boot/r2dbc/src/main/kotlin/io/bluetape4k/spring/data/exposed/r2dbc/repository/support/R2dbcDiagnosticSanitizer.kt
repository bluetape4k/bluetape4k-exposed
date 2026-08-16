package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.mapping.MappingException

/** SQL/metric 진단에 사용할 고정 operation allowlist입니다. */
internal enum class R2dbcQbeOperation(val label: String) {
    FIND_ONE("find-one"),
    FIND_ALL("find-all"),
    COUNT("count"),
    EXISTS("exists"),
    FLUENT_ONE("fluent-one"),
    FLUENT_FIRST("fluent-first"),
    FLUENT_ALL("fluent-all"),
    FLUENT_PAGE("fluent-page"),
    FLUENT_SLICE("fluent-slice"),
}

/** 공개 오류에 raw probe/value/cause를 넣지 않도록 진단 token을 정규화합니다. */
internal object R2dbcDiagnosticSanitizer {

    private const val MAX_TOKEN_LENGTH = 128

    fun propertyToken(value: String): String = sanitizeToken(value)

    fun operationLabel(operation: R2dbcQbeOperation): String = operation.label

    fun validateOperationLabel(label: String): String =
        R2dbcQbeOperation.entries
            .firstOrNull { it.label == label }
            ?.label
            ?: throw IllegalArgumentException("Unsupported R2DBC QBE operation label")

    fun invalidUsage(message: String): InvalidDataAccessApiUsageException =
        InvalidDataAccessApiUsageException(sanitizeToken(message))

    fun mapping(message: String): MappingException =
        R2dbcSanitizedMappingException(sanitizeToken(message))

    private fun sanitizeToken(value: String): String {
        val sanitized = buildString(value.length) {
            value.forEach { character ->
                append(if (isAllowedTokenCharacter(character)) character else '_')
            }
        }.trim('_')
        return sanitized.take(MAX_TOKEN_LENGTH).ifEmpty { "unknown" }
    }

    private fun isAllowedTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character in "._-"
}

/** raw cause를 보관하지 않는 mapping failure입니다. */
internal class R2dbcSanitizedMappingException(message: String): MappingException(message)
