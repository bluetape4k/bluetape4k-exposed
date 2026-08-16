package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import java.sql.SQLException

private const val MAX_DIAGNOSTIC_VALUE_LENGTH = 128

/**
 * caller 또는 driver가 제공한 진단 문자열에서 control character를 제거하고 길이를 제한합니다.
 */
@JvmSynthetic
internal fun safeDiagnosticValue(value: String): String = buildString {
    value.forEach { character ->
        if (!character.isISOControl() && length < MAX_DIAGNOSTIC_VALUE_LENGTH) {
            append(character)
        }
    }
}

/**
 * 원본 driver message와 cause chain을 제거하고 안전한 SQL 상태 정보만 보존합니다.
 */
@JvmSynthetic
internal fun sanitizedSqlException(cause: SQLException, operation: String): SQLException =
    SQLException(
        operation,
        cause.sqlState?.let(::safeDiagnosticValue),
        cause.errorCode,
    )

/**
 * reflection 대상이 던진 message와 cause chain을 제거하고 reflection 예외 종류만 보존합니다.
 */
@JvmSynthetic
internal fun sanitizedReflectiveException(cause: ReflectiveOperationException): ReflectiveOperationException =
    ReflectiveOperationException(
        "Reflection invocation failed (${cause::class.java.simpleName}).",
    )
