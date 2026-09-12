@file:Suppress("TooManyFunctions")

package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.sql.Clob
import java.util.LinkedHashMap
import java.util.UUID

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val JSON_UNICODE_ESCAPE_LENGTH = 4
private const val UUID_BYTE_LENGTH = 16

/** Caller-owned JSON codec. This module intentionally does not select a serializer dependency. */
interface ClickHouseJsonCodec<T> {
    fun encode(value: T): String
    fun decode(json: String): T
}

/** ClickHouse `JSON` column in raw text mode. */
class ClickHouseJsonColumnType: ColumnType<String>() {
    override fun sqlType(): String = "JSON"

    override fun valueFromDB(value: Any): String = clickHouseJsonText(value).also(::validateClickHouseJson)

    override fun notNullValueToDB(value: String): Any = value.also(::validateClickHouseJson)

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** ClickHouse `JSON` column backed by a caller-owned typed codec. */
class ClickHouseJsonCodecColumnType<T: Any>(val codec: ClickHouseJsonCodec<T>): ColumnType<T>() {
    override fun sqlType(): String = "JSON"

    override fun valueFromDB(value: Any): T = codec.decode(clickHouseJsonText(value))

    override fun notNullValueToDB(value: T): Any = codec.encode(value).also(::validateClickHouseJson)

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

private fun clickHouseJsonText(value: Any?): String = when (value) {
    is String -> value
    is ByteArray -> value.toString(StandardCharsets.UTF_8)
    is Clob -> withClickHouseCleanup({ value.free() }) { value.getSubString(1, value.length().toInt()) }
    is java.sql.Array -> clickHouseJsonText(clickHouseArrayElements(value))
    is Map<*, *> -> clickHouseJsonObject(value)
    is Iterable<*> -> clickHouseJsonArray(value)
    is Array<*> -> clickHouseJsonArray(value.asList())
    is Number -> clickHouseJsonNumber(value)
    is Boolean -> value.toString()
    null -> "null"
    else -> error("Unexpected ClickHouse JSON value: $value (${value::class.simpleName})")
}

private fun clickHouseJsonObject(value: Map<*, *>): String =
    value.entries.joinToString(prefix = "{", postfix = "}") { (key, mappedValue) ->
        require(key is String) { "ClickHouse JSON object keys must be strings: $key" }
        "${clickHouseJsonString(key)}:${clickHouseJsonText(mappedValue)}"
    }

private fun clickHouseJsonArray(value: Iterable<*>): String =
    value.joinToString(prefix = "[", postfix = "]", transform = ::clickHouseJsonText)

private fun clickHouseJsonNumber(value: Number): String {
    require(value !is Double || value.isFinite()) { "ClickHouse JSON number must be finite: $value" }
    require(value !is Float || value.isFinite()) { "ClickHouse JSON number must be finite: $value" }
    return value.toString()
}

private fun clickHouseJsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < JSON_CONTROL_CHARACTER_LIMIT) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

/** A small strict JSON validator used to preserve the raw JSON contract without a serializer dependency. */
internal fun validateClickHouseJson(json: String) {
    require(json.isNotBlank()) { "ClickHouse JSON value must not be blank" }
    ClickHouseJsonParser(json).parse()
}

private class ClickHouseJsonParser(private val input: String) {
    private var index = 0

    fun parse() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        require(index == input.length) { "Malformed JSON at offset $index" }
    }

    private fun parseValue() {
        require(index < input.length) { "Malformed JSON at offset $index" }
        when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                val literal = "true"
                require(input.regionMatches(index, literal, 0, literal.length)) {
                    "Malformed JSON at offset $index"
                }
                index += literal.length
            }
            'f' -> {
                val literal = "false"
                require(input.regionMatches(index, literal, 0, literal.length)) {
                    "Malformed JSON at offset $index"
                }
                index += literal.length
            }
            'n' -> {
                val literal = "null"
                require(input.regionMatches(index, literal, 0, literal.length)) {
                    "Malformed JSON at offset $index"
                }
                index += literal.length
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("Malformed JSON at offset $index")
        }
    }

    private fun parseObject() {
        index++
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            require(input.getOrNull(index) == '"') { "JSON object key must be a string at offset $index" }
            parseString()
            skipWhitespace()
            require(consume(':')) { "Expected ':' at offset $index" }
            skipWhitespace()
            parseValue()
            skipWhitespace()
            if (consume('}')) return
            require(consume(',')) { "Expected ',' at offset $index" }
            skipWhitespace()
        }
    }

    private fun parseArray() {
        index++
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consume(']')) return
            require(consume(',')) { "Expected ',' at offset $index" }
            skipWhitespace()
        }
    }

    private fun parseString() {
        require(consume('"')) { "Expected '\"' at offset $index" }
        while (index < input.length) {
            when (val character = input[index++]) {
                '"' -> return
                '\\' -> {
                    require(index < input.length) { "Incomplete JSON escape at offset $index" }
                    when (input[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(JSON_UNICODE_ESCAPE_LENGTH) {
                            val digit = input.getOrNull(index)
                            require(digit != null && (digit.isDigit() || digit.lowercaseChar() in 'a'..'f')) {
                                "Invalid JSON unicode escape at offset $index"
                            }
                            index++
                        }
                        else -> error("Invalid JSON escape at offset ${index - 1}")
                    }
                }
                else -> require(character.code >= JSON_CONTROL_CHARACTER_LIMIT) {
                    "JSON string contains a control character"
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseNumber() {
        val start = index
        consume('-')
        parseInteger()
        parseFraction()
        parseExponent()
        require(index > start) { "Malformed JSON number at offset $start" }
    }

    private fun parseInteger() {
        if (consume('0')) {
            // Leading zeroes are not valid JSON numbers.
            require(index >= input.length || !input[index].isDigit()) { "Malformed JSON number at offset $index" }
        } else {
            require(index < input.length && input[index] in '1'..'9') { "Malformed JSON number at offset $index" }
            while (index < input.length && input[index].isDigit()) index++
        }
    }

    private fun parseFraction() {
        if (consume('.')) {
            require(index < input.length && input[index].isDigit()) { "Malformed JSON number at offset $index" }
            while (index < input.length && input[index].isDigit()) index++
        }
    }

    private fun parseExponent() {
        if (index < input.length && input[index] in "eE") {
            index++
            if (index < input.length && input[index] in "+-") index++
            require(index < input.length && input[index].isDigit()) { "Malformed JSON exponent at offset $index" }
            while (index < input.length && input[index].isDigit()) index++
        }
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun consume(expected: Char): Boolean {
        if (input.getOrNull(index) != expected) return false
        index++
        return true
    }
}

/** ClickHouse `UUID` column mapped to [UUID]. */
class ClickHouseUuidColumnType: ColumnType<UUID>() {
    override fun sqlType(): String = "UUID"

    override fun valueFromDB(value: Any): UUID = when (value) {
        is UUID -> value
        is String -> runCatching { UUID.fromString(value) }
            .getOrElse { error("Malformed ClickHouse UUID: $value") }
        is ByteArray -> {
            require(value.size == UUID_BYTE_LENGTH) {
                "ClickHouse UUID binary value must contain $UUID_BYTE_LENGTH bytes"
            }
            val most = value.copyOfRange(0, 8).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
            val least = value.copyOfRange(8, 16).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
            UUID(most, least)
        }
        else -> error("Unexpected ClickHouse UUID value: $value (${value::class.simpleName})")
    }

    override fun notNullValueToDB(value: UUID): Any = value

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** ClickHouse `IPv4` column mapped to [Inet4Address]. */
class ClickHouseIpv4ColumnType: ColumnType<Inet4Address>() {
    override fun sqlType(): String = "IPv4"

    override fun valueFromDB(value: Any): Inet4Address = when (value) {
        is Inet4Address -> value
        is InetAddress -> {
            require(value is Inet4Address) { "Expected IPv4 address but got ${value.hostAddress}" }
            value
        }
        is String -> parseIpv4(value)
        else -> error("Unexpected ClickHouse IPv4 value: $value (${value::class.simpleName})")
    }

    override fun notNullValueToDB(value: Inet4Address): Any = value.hostAddress

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** ClickHouse `IPv6` column mapped to [Inet6Address]. */
class ClickHouseIpv6ColumnType: ColumnType<Inet6Address>() {
    override fun sqlType(): String = "IPv6"

    override fun valueFromDB(value: Any): Inet6Address = when (value) {
        is Inet6Address -> value
        is InetAddress -> {
            require(value is Inet6Address) { "Expected IPv6 address but got ${value.hostAddress}" }
            value
        }
        is String -> parseIpv6(value)
        else -> error("Unexpected ClickHouse IPv6 value: $value (${value::class.simpleName})")
    }

    override fun notNullValueToDB(value: Inet6Address): Any = value.hostAddress

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

private fun parseIpv4(value: String): Inet4Address {
    require(value.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) { "Malformed IPv4 address: $value" }
    val address = runCatching { InetAddress.getByName(value) }
        .getOrElse { error("Malformed IPv4 address: $value") }
    require(address is Inet4Address) { "Expected IPv4 address: $value" }
    return address
}

private fun parseIpv6(value: String): Inet6Address {
    require(':' in value) { "Malformed IPv6 address: $value" }
    val address = runCatching { InetAddress.getByName(value) }
        .getOrElse { error("Malformed IPv6 address: $value") }
    require(address is Inet6Address) { "Expected IPv6 address: $value" }
    return address
}

/** ClickHouse `Decimal(precision, scale)` mapped to [BigDecimal]. */
class ClickHouseDecimalColumnType(val precision: Int, val scale: Int): ColumnType<BigDecimal>() {
    init {
        require(precision > 0) { "Decimal precision must be > 0: $precision" }
        require(scale in 0..precision) { "Decimal scale must be in 0..precision: $scale" }
    }

    override fun sqlType(): String = "Decimal($precision, $scale)"

    override fun valueFromDB(value: Any): BigDecimal = normalizeDecimal(value)

    override fun notNullValueToDB(value: BigDecimal): Any = normalizeDecimal(value)

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }

    private fun normalizeDecimal(value: Any): BigDecimal {
        val decimal = when (value) {
            is BigDecimal -> value
            is Number, is String -> runCatching { BigDecimal(value.toString()) }
                .getOrElse { error("Malformed ClickHouse Decimal value: $value") }
            else -> error("Unexpected ClickHouse Decimal value: $value (${value::class.simpleName})")
        }
        val scaled = decimal.setScale(scale, RoundingMode.UNNECESSARY)
        require(scaled.precision() <= precision) {
            "Decimal($precision, $scale) cannot represent value $decimal"
        }
        return scaled
    }
}

/** ClickHouse `Enum8`/`Enum16` mapped by explicit wire names, never ordinal values. */
class ClickHouseEnumColumnType<E: Enum<E>>(values: Map<E, String>): ColumnType<E>() {
    val values: Map<E, String> = LinkedHashMap(values)
    private val byWireName: Map<String, E>

    init {
        require(this.values.isNotEmpty()) { "ClickHouse Enum requires at least one value" }
        this.values.values.forEach { name ->
        require(name.isNotBlank() && name.none(Char::isISOControl)) {
            "Enum wire name must be non-blank and printable"
        }
        }
        require(this.values.values.toSet().size == this.values.size) { "ClickHouse Enum wire names must be unique" }
        byWireName = this.values.entries.associate { it.value to it.key }
    }

    override fun sqlType(): String {
        val enumType = if (values.size <= 127) "Enum8" else "Enum16"
        val entries = values.values.mapIndexed { index, name ->
            "'${name.replace("'", "''")}'=${index + 1}"
        }.joinToString(", ")
        return "$enumType($entries)"
    }

    override fun valueFromDB(value: Any): E = when (value) {
        is String -> byWireName[value] ?: error("Unknown ClickHouse Enum value: $value")
        is Enum<*> -> {
            @Suppress("UNCHECKED_CAST")
            val typed = value as E
            require(values.containsKey(typed)) { "Enum value $typed is not registered in ClickHouse Enum" }
            typed
        }
        else -> throw IllegalArgumentException("ClickHouse Enum ordinal values are unsupported: $value")
    }

    override fun notNullValueToDB(value: E): Any = values[value]
        ?: error("Enum value $value is not registered in ClickHouse Enum")

    override fun parameterMarker(value: E?): String = "CAST(? AS ${sqlType()})"

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** Raw `JSON` column을 등록합니다. */
fun Table.chJson(name: String): Column<String> =
    registerColumn(name, ClickHouseJsonColumnType())

/** Caller-owned codec를 사용하는 typed `JSON` column을 등록합니다. */
fun <T: Any> Table.chJson(name: String, codec: ClickHouseJsonCodec<T>): Column<T> =
    registerColumn(name, ClickHouseJsonCodecColumnType(codec))

/** `UUID` column을 등록합니다. */
fun Table.chUuid(name: String): Column<UUID> =
    registerColumn(name, ClickHouseUuidColumnType())

/** `IPv4` column을 등록합니다. */
fun Table.chIpv4(name: String): Column<Inet4Address> =
    registerColumn(name, ClickHouseIpv4ColumnType())

/** `IPv6` column을 등록합니다. */
fun Table.chIpv6(name: String): Column<Inet6Address> =
    registerColumn(name, ClickHouseIpv6ColumnType())

/** `Decimal(precision, scale)` column을 등록합니다. */
fun Table.chDecimal(name: String, precision: Int, scale: Int): Column<BigDecimal> =
    registerColumn(name, ClickHouseDecimalColumnType(precision, scale))

/** 명시적 wire name/alias를 사용하는 `Enum` column을 등록합니다. */
fun <E: Enum<E>> Table.chEnum(name: String, values: Map<E, String>): Column<E> =
    registerColumn(name, ClickHouseEnumColumnType(values))
