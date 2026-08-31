package io.bluetape4k.batch

import java.lang.reflect.Array
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/**
 * Job 실행 파라미터를 재현 가능한 SHA-256 식별자로 변환한다.
 *
 * 각 key와 value는 UTF-8 바이트 길이로 감싸고 value type을 별도로 기록한다.
 * 따라서 값에 구분자가 포함되거나 같은 문자열 표현을 가진 서로 다른 타입이어도
 * 동일한 실행으로 잘못 deduplicate되지 않는다. canonical 입력은 `v2`로 버전이
 * 고정되어 JDBC와 R2DBC가 같은 digest를 생성한다. 재현 가능한 표현을 보장할 수
 * 없는 임의 객체나 generic [Iterable]은 허용하지 않는다.
 *
 * 빈 Map은 기존 저장 데이터와의 호환을 위해 빈 문자열을 반환한다. 비어 있지 않은
 * Map은 64자리 lowercase SHA-256 hex를 반환한다.
 */
object BatchParameterHash {
    /** canonical parameter hash encoding version. */
    const val VERSION: Int = 2

    /** batch execution schema descriptor가 참조하는 저장 계약. */
    const val STORAGE_CONTRACT: String =
        "varchar(64):BatchParameterHash:v2:SHA-256(versioned,length-prefixed,key+typed-value,UTF-8,lowercase-hex,empty-map-empty)"

    private const val CANONICAL_PREFIX = "bluetape4k-batch-params"

    /**
     * [parameters]의 canonical encoding SHA-256 lowercase hex를 반환한다.
     *
     * @param parameters Job 실행 파라미터
     * @return 빈 Map이면 `""`, 그 외에는 64자리 lowercase SHA-256 hex
     */
    fun hash(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""

        val canonical = buildString {
            append(CANONICAL_PREFIX)
            append(":v").append(VERSION)
            appendField("count", parameters.size.toString())
            parameters.entries
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    appendField("key", key)
                    appendField("value", encodeValue(value))
                }
        }.toByteArray(UTF_8)

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and HEX_BYTE_MASK) }
    }
}

private const val HEX_RADIX = 16
private const val HEX_BYTE_MASK = 0xff
private const val FLOAT_HEX_WIDTH = 8
private const val DOUBLE_HEX_WIDTH = 16

private fun StringBuilder.appendField(name: String, value: String) {
    append('|').append(name).append(':')
    append(value.toByteArray(UTF_8).size)
    append(':').append(value)
}

private fun encodeValue(value: Any?): String = buildString {
    appendField("type", valueType(value))
    appendField("value", valueText(value))
}

private fun valueType(value: Any?): String {
    if (value == null) return "null"

    return simpleType(value)
        ?: numericType(value)
        ?: temporalType(value)
        ?: containerType(value)
        ?: throw IllegalArgumentException(
            "지원하지 않는 Batch parameter type입니다: ${value.javaClass.name}. " +
                "지원되는 scalar, Map, List, Set 또는 array로 정규화하세요.",
        )
}

private fun simpleType(value: Any): String? = when (value) {
    is String -> "string"
    is Char -> "char"
    is Boolean -> "boolean"
    is Enum<*> -> "enum:${value.declaringJavaClass.name}"
    else -> null
}

private fun numericType(value: Any): String? = when (value) {
    is Byte -> "byte"
    is Short -> "short"
    is Int -> "int"
    is Long -> "long"
    is Float -> "float"
    is Double -> "double"
    is BigInteger -> "big-integer"
    is BigDecimal -> "big-decimal"
    else -> null
}

private fun temporalType(value: Any): String? = when (value) {
    is UUID -> "uuid"
    is Instant -> "instant"
    is LocalDate -> "local-date"
    is LocalDateTime -> "local-date-time"
    is LocalTime -> "local-time"
    is OffsetDateTime -> "offset-date-time"
    is OffsetTime -> "offset-time"
    is ZonedDateTime -> "zoned-date-time"
    is Year -> "year"
    is YearMonth -> "year-month"
    is ZoneOffset -> "zone-offset"
    is ZoneId -> "zone-id"
    else -> null
}

private fun containerType(value: Any): String? = when (value) {
    is Map<*, *> -> "map"
    is List<*> -> "list"
    is Set<*> -> "set"
    else -> if (value.javaClass.isArray) "array:${value.javaClass.componentType.name}" else null
}

private fun valueText(value: Any?): String = when (value) {
    null -> ""
    is Enum<*> -> value.name
    is Float -> Integer.toUnsignedString(value.toRawBits(), HEX_RADIX).padStart(FLOAT_HEX_WIDTH, '0')
    is Double -> java.lang.Long.toUnsignedString(value.toRawBits(), HEX_RADIX)
        .padStart(DOUBLE_HEX_WIDTH, '0')
    is ByteArray -> value.joinToString("") {
        byte -> "%02x".format(Locale.ROOT, byte.toInt() and HEX_BYTE_MASK)
    }
    is Map<*, *> -> encodeMap(value)
    is List<*> -> encodeItems(value.map(::encodeValue), false)
    is Set<*> -> encodeItems(value.map(::encodeValue), true)
    value.javaClass.isArray -> encodeItems(
        (0 until Array.getLength(value)).map { index -> encodeValue(Array.get(value, index)) },
        false,
    )
    else -> value.toString()
}

private fun encodeMap(value: Map<*, *>): String = buildString {
    appendField("count", value.size.toString())
    value.entries
        .map { entry -> encodeValue(entry.key) to encodeValue(entry.value) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .forEach { (key, mappedValue) ->
            appendField("key", key)
            appendField("value", mappedValue)
        }
}

private fun encodeItems(values: List<String>, sortValues: Boolean): String = buildString {
    val encodedValues = values.let { values ->
        if (sortValues) values.sorted() else values
    }
    appendField("count", encodedValues.size.toString())
    encodedValues.forEach { appendField("item", it) }
}
