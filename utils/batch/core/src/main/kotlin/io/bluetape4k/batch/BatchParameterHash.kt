package io.bluetape4k.batch

import java.lang.reflect.Array
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

private const val CANONICAL_PREFIX = "bluetape4k-batch-params"

/**
 * Job 실행 파라미터를 재현 가능한 SHA-256 식별자로 변환한다.
 *
 * 각 key와 value는 UTF-8 바이트 길이로 감싸고 value type을 별도로 기록한다.
 * 따라서 값에 구분자가 포함되거나 같은 문자열 표현을 가진 서로 다른 타입이어도
 * 동일한 실행으로 잘못 deduplicate되지 않는다. canonical 입력은 `v2`로 버전이
 * 고정되어 JDBC와 R2DBC가 같은 digest를 생성한다. 재현 가능한 표현을 보장할 수
 * 없는 임의 객체나 generic [Iterable]은 허용하지 않는다.
 * 입력은 canonical UTF-8 1 MiB, scalar 256 KiB, container 1,000항목,
 * 전체 value 10,000개, 중첩 32단계로 제한하며 순환 참조를 거부한다.
 * 입력을 한 번 순회하면서 상한을 적용한 canonical 표현을 생성해 mutable 입력이
 * 검증 이후 변경되더라도 제한을 우회하지 못하게 한다.
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

    internal const val MAX_NESTING_DEPTH: Int = 32
    internal const val MAX_CONTAINER_ITEMS: Int = 1_000
    internal const val MAX_TOTAL_VALUES: Int = 10_000
    internal const val MAX_SCALAR_UTF8_BYTES: Int = 256 * 1024
    internal const val MAX_CANONICAL_UTF8_BYTES: Int = 1024 * 1024

    /**
     * [parameters]의 canonical encoding SHA-256 lowercase hex를 반환한다.
     *
     * @param parameters Job 실행 파라미터
     * @return 빈 Map이면 `""`, 그 외에는 64자리 lowercase SHA-256 hex
     * @throws IllegalArgumentException 지원하지 않는 타입, 순환 참조 또는 입력 상한 초과
     */
    fun hash(parameters: Map<String, Any>): String {
        val canonical = ParameterEncoder().encode(parameters) ?: return ""

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and HEX_BYTE_MASK) }
    }
}

private data class EncodedText(
    val text: String,
    val utf8Bytes: Long,
)

private class ParameterEncoder {
    private val activeContainers = IdentityHashMap<Any, Unit>()
    private var totalValues = 0
    private val encodeMap: (Map<*, *>, Int) -> EncodedText = { value, depth ->
        withContainer(value) {
            checkContainerSize(value.size)
            var size = canonicalFieldSize("count", utf8Size("0"))
            var countFieldBytes = size
            val entries = buildList {
                value.entries.forEach { (key, mappedValue) ->
                    checkContainerSize(this.size + 1)
                    val nextCountFieldBytes = canonicalFieldSize("count", utf8Size((this.size + 1).toString()))
                    size = checkedAdd(size, nextCountFieldBytes - countFieldBytes)
                    countFieldBytes = nextCountFieldBytes
                    val encodedKey = encodedValue(key, depth + 1)
                    size = checkedAdd(size, canonicalFieldSize("key", encodedKey.utf8Bytes))
                    val encodedMappedValue = encodedValue(mappedValue, depth + 1)
                    size = checkedAdd(size, canonicalFieldSize("value", encodedMappedValue.utf8Bytes))
                    add(encodedKey to encodedMappedValue)
                }
            }.sortedWith(compareBy({ it.first.text }, { it.second.text }))
            EncodedText(
                buildString {
                    appendField("count", entries.size.toString())
                    entries.forEach { (key, mappedValue) ->
                        appendField("key", key.text)
                        appendField("value", mappedValue.text)
                    }
                },
                size,
            )
        }
    }

    fun encode(parameters: Map<String, Any>): ByteArray? {
        checkContainerSize(parameters.size)

        var canonicalBytes = utf8Size(CANONICAL_PREFIX) + utf8Size(":v${BatchParameterHash.VERSION}")
        var countFieldBytes = canonicalFieldSize("count", utf8Size("0"))
        canonicalBytes = checkedAdd(canonicalBytes, countFieldBytes)
        val entries = buildList {
            parameters.entries.forEach { (key, value) ->
                checkContainerSize(size + 1)
                boundedScalar(key)
                val nextCountFieldBytes = canonicalFieldSize("count", utf8Size((size + 1).toString()))
                canonicalBytes = checkedAdd(canonicalBytes, nextCountFieldBytes - countFieldBytes)
                countFieldBytes = nextCountFieldBytes
                canonicalBytes = checkedAdd(canonicalBytes, canonicalFieldSize("key", utf8Size(key)))
                val encodedValue = encodedValue(value, 1)
                canonicalBytes = checkedAdd(canonicalBytes, canonicalFieldSize("value", encodedValue.utf8Bytes))
                add(key to encodedValue)
            }
        }.sortedBy { it.first }
        if (entries.isEmpty()) return null

        checkCanonicalSize(canonicalBytes)

        return strictUtf8Bytes(buildString {
            append(CANONICAL_PREFIX)
            append(":v").append(BatchParameterHash.VERSION)
            appendField("count", entries.size.toString())
            entries.forEach { (key, value) ->
                appendField("key", key)
                appendField("value", value.text)
            }
        })
    }

    private fun encodedValue(value: Any?, depth: Int): EncodedText {
        if (depth > BatchParameterHash.MAX_NESTING_DEPTH) {
            throw IllegalArgumentException(
                "Batch parameter 중첩 깊이는 ${BatchParameterHash.MAX_NESTING_DEPTH} 이하여야 합니다.",
            )
        }
        totalValues += 1
        if (totalValues > BatchParameterHash.MAX_TOTAL_VALUES) {
            throw IllegalArgumentException(
                "Batch parameter 전체 value 수는 ${BatchParameterHash.MAX_TOTAL_VALUES} 이하여야 합니다.",
            )
        }

        val type = valueType(value)
        val valueText = valueText(value, depth)
        val size = checkedAdd(
            canonicalFieldSize("type", utf8Size(type)),
            canonicalFieldSize("value", valueText.utf8Bytes),
        )
        return EncodedText(
            buildString {
                appendField("type", type)
                appendField("value", valueText.text)
            },
            size,
        )
    }

    private fun valueText(value: Any?, depth: Int): EncodedText = when (value) {
        null -> EncodedText("", 0)
        is Enum<*> -> boundedScalar(value.name)
        is Float -> boundedScalar(
            Integer.toUnsignedString(value.toRawBits(), HEX_RADIX).padStart(FLOAT_HEX_WIDTH, '0'),
        )
        is Double -> boundedScalar(
            java.lang.Long.toUnsignedString(value.toRawBits(), HEX_RADIX).padStart(DOUBLE_HEX_WIDTH, '0'),
        )
        is ByteArray -> {
            checkedScalarSize(value.size.toLong() * 2)
            boundedScalar(
                value.joinToString("") { byte ->
                    "%02x".format(Locale.ROOT, byte.toInt() and HEX_BYTE_MASK)
                },
            )
        }
        is Map<*, *> -> encodeMap(value, depth)
        is List<*> -> containerItems(value, value, depth, false)
        is Set<*> -> containerItems(value, value, depth, true)
        else -> if (value.javaClass.isArray) {
            withContainer(value) {
                val length = Array.getLength(value)
                checkContainerSize(length)
                var size = canonicalFieldSize("count", utf8Size(length.toString()))
                val items = buildList {
                    repeat(length) { index ->
                        val item = encodedValue(Array.get(value, index), depth + 1)
                        size = checkedAdd(size, canonicalFieldSize("item", item.utf8Bytes))
                        add(item)
                    }
                }
                containerText(items, false, size)
            }
        } else {
            checkNumericSizeBeforeRendering(value)
            boundedScalar(value.toString())
        }
    }

    private fun containerItems(
        identity: Any,
        values: Collection<*>,
        depth: Int,
        sortValues: Boolean,
    ): EncodedText = withContainer(identity) {
        checkContainerSize(values.size)
        var size = canonicalFieldSize("count", utf8Size("0"))
        var countFieldBytes = size
        val encodedValues = buildList {
            values.forEach { item ->
                checkContainerSize(this.size + 1)
                val nextCountFieldBytes = canonicalFieldSize("count", utf8Size((this.size + 1).toString()))
                size = checkedAdd(size, nextCountFieldBytes - countFieldBytes)
                countFieldBytes = nextCountFieldBytes
                val encodedItem = encodedValue(item, depth + 1)
                size = checkedAdd(size, canonicalFieldSize("item", encodedItem.utf8Bytes))
                add(encodedItem)
            }
        }
        containerText(encodedValues, sortValues, size)
    }

    private fun containerText(values: List<EncodedText>, sortValues: Boolean, size: Long): EncodedText {
        val encodedValues = if (sortValues) values.sortedBy { it.text } else values
        return EncodedText(
            buildString {
                appendField("count", encodedValues.size.toString())
                encodedValues.forEach { appendField("item", it.text) }
            },
            size,
        )
    }

    private inline fun <T> withContainer(container: Any, block: () -> T): T {
        if (activeContainers.put(container, Unit) != null) {
            throw IllegalArgumentException("Batch parameter에는 순환 참조를 사용할 수 없습니다.")
        }
        return try {
            block()
        } finally {
            activeContainers.remove(container)
        }
    }

    private fun checkNumericSizeBeforeRendering(value: Any) {
        val maxBits = BatchParameterHash.MAX_SCALAR_UTF8_BYTES.toLong() * 4
        val bitLength = when (value) {
            is BigInteger -> value.bitLength().toLong()
            is BigDecimal -> value.unscaledValue().bitLength().toLong()
            else -> return
        }
        if (bitLength > maxBits) {
            throw IllegalArgumentException(
                "Batch parameter scalar UTF-8 크기는 ${BatchParameterHash.MAX_SCALAR_UTF8_BYTES} bytes 이하여야 합니다.",
            )
        }
    }

    private fun boundedScalar(value: String): EncodedText {
        if (value.length > BatchParameterHash.MAX_SCALAR_UTF8_BYTES) {
            checkedScalarSize(value.length.toLong())
        }
        return EncodedText(value, checkedScalarSize(utf8Size(value)))
    }

    private fun checkedScalarSize(size: Long): Long {
        if (size > BatchParameterHash.MAX_SCALAR_UTF8_BYTES) {
            throw IllegalArgumentException(
                "Batch parameter scalar UTF-8 크기는 ${BatchParameterHash.MAX_SCALAR_UTF8_BYTES} bytes 이하여야 합니다.",
            )
        }
        return size
    }

    private fun checkedAdd(vararg values: Long): Long {
        var total = 0L
        values.forEach { value ->
            total = try {
                Math.addExact(total, value)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Batch parameter canonical 크기가 허용 범위를 초과했습니다.")
            }
            checkCanonicalSize(total)
        }
        return total
    }

    private fun checkCanonicalSize(size: Long) {
        if (size > BatchParameterHash.MAX_CANONICAL_UTF8_BYTES) {
            throw IllegalArgumentException(
                "Batch parameter canonical UTF-8 크기는 " +
                    "${BatchParameterHash.MAX_CANONICAL_UTF8_BYTES} bytes 이하여야 합니다.",
            )
        }
    }
}

private val canonicalFieldSize: (String, Long) -> Long = { name, valueBytes ->
    1 + utf8Size(name) + 1 + valueBytes.toString().length + 1 + valueBytes
}

private val strictUtf8Bytes: (String) -> ByteArray = { value ->
    try {
        val encoded = UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also { bytes -> encoded.get(bytes) }
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Batch parameter String에는 잘못된 UTF-16 surrogate를 사용할 수 없습니다.")
    }
}

private val utf8Size: (String) -> Long = { value -> strictUtf8Bytes(value).size.toLong() }

private fun checkContainerSize(size: Int) {
    if (size > BatchParameterHash.MAX_CONTAINER_ITEMS) {
        throw IllegalArgumentException(
            "Batch parameter container 항목 수는 ${BatchParameterHash.MAX_CONTAINER_ITEMS} 이하여야 합니다.",
        )
    }
}

private const val HEX_RADIX = 16
private const val HEX_BYTE_MASK = 0xff
private const val FLOAT_HEX_WIDTH = 8
private const val DOUBLE_HEX_WIDTH = 16

private fun StringBuilder.appendField(name: String, value: String) {
    append('|').append(name).append(':')
    append(strictUtf8Bytes(value).size)
    append(':').append(value)
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
