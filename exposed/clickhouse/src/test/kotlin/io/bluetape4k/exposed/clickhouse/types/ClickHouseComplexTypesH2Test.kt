package io.bluetape4k.exposed.clickhouse.types

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBe
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneId
import java.util.*

/**
 * ClickHouse JDBC V2 복합·특수 타입의 H2 selector-safe 의미론 경계를 고정한다.
 *
 * 실제 ClickHouse wire round-trip은 [ClickHouseComplexTypesTest]에서 검증하고,
 * 이 테스트는 컨테이너 없이 ColumnType 계약·불변성·실패 경계를 검증한다.
 */
class ClickHouseComplexTypesH2Test {

    private enum class State {
        ACTIVE,
        DISABLED
    }

    private val intCodec = object: ClickHouseJsonCodec<Int> {
        override fun encode(value: Int): String = value.toString()
        override fun decode(json: String): Int = json.trim().toInt()
    }

    private object ComplexTable: Table("complex_types_h2") {
        val nullableElements = chArrayNullableElements("nullable_elements", ClickHouseInt32ColumnType())
        val nullableContainer = chNullableArray("nullable_container", ClickHouseStringColumnType())
        val nestedArray = chArray(
            "nested_array",
            ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType()),
        )
        val labels = chMap("labels", ClickHouseStringColumnType(), ClickHouseInt32ColumnType())
        val tuple = chTuple("tuple", listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        val nested = chNested("nested", listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        val rawJson = chJson("raw_json")
        val typedJson = chJson("typed_json", object: ClickHouseJsonCodec<Int> {
            override fun encode(value: Int): String = value.toString()
            override fun decode(json: String): Int = json.trim().toInt()
        })
        val uuid = chUuid("uuid")
        val ipv4 = chIpv4("ipv4")
        val ipv6 = chIpv6("ipv6")
        val decimal = chDecimal("decimal", precision = 18, scale = 4)
        val state = chEnum("state", mapOf(State.ACTIVE to "active", State.DISABLED to "disabled"))
    }

    @Test
    fun `builders expose complex ClickHouse sql types`() {
        ComplexTable.nullableElements.columnType.sqlType() shouldBeEqualTo "Array(Nullable(Int32))"
        ComplexTable.nullableContainer.columnType.sqlType() shouldBeEqualTo "Nullable(Array(String))"
        ComplexTable.nestedArray.columnType.sqlType() shouldBeEqualTo "Array(Array(Nullable(Int32)))"
        ComplexTable.labels.columnType.sqlType() shouldBeEqualTo "Map(String, Int32)"
        ComplexTable.tuple.columnType.sqlType() shouldBeEqualTo "Tuple(String, Int32)"
        ComplexTable.nested.columnType.sqlType() shouldBeEqualTo "Nested(field_0 String, field_1 Int32)"
        ComplexTable.rawJson.columnType.sqlType() shouldBeEqualTo "JSON"
        ComplexTable.uuid.columnType.sqlType() shouldBeEqualTo "UUID"
        ComplexTable.ipv4.columnType.sqlType() shouldBeEqualTo "IPv4"
        ComplexTable.ipv6.columnType.sqlType() shouldBeEqualTo "IPv6"
        ComplexTable.decimal.columnType.sqlType() shouldBeEqualTo "Decimal(18, 4)"
        ComplexTable.state.columnType.sqlType() shouldBeEqualTo "Enum8('active'=1, 'disabled'=2)"
    }

    @Test
    fun `nullable array exposes outer nullable list type`() {
        val column: Column<List<String>?> = ComplexTable.nullableContainer
        column.columnType.nullable.shouldBeTrue()
        column.columnType.sqlType() shouldBeEqualTo "Nullable(Array(String))"
    }

    @Test
    fun `nullable array preserves null elements and defensive copies`() {
        val type = ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType())
        val source = arrayOf<Any?>(1, null, 3)

        val decoded = type.valueFromDB(source)
        decoded shouldBeEqualTo listOf(1, null, 3)
        source[0] = 99
        decoded shouldBeEqualTo listOf(1, null, 3)

        assertFailsWith<UnsupportedOperationException> {
            (decoded as MutableList<Int?>)[0] = 4
        }

        val encoded = type.notNullValueToDB(listOf(1, null, 3)) as Array<*>
        encoded.toList() shouldBeEqualTo listOf(1, null, 3)
        assertFailsWith<IllegalStateException> {
            type.valueFromDB("[1,null]")
        }
    }

    @Test
    fun `nullable array distinguishes nullable container from nullable elements`() {
        val type = ClickHouseNullableArrayColumnType(ClickHouseStringColumnType())
        type.valueToDB(null).shouldBeNull()
        type.valueFromDB(listOf("a", "b")) shouldBeEqualTo listOf("a", "b")
        (type.notNullValueToDB(listOf("a", "b")) as Array<*>).toList() shouldBeEqualTo arrayOf("a", "b").toList()

        assertFailsWith<IllegalArgumentException> {
            type.valueFromDB(listOf("a", null))
        }
    }

    @Test
    fun `nullable array adapter rejects inner-nullability mode`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseNullableArrayColumnType(ClickHouseStringColumnType(), nullableContainer = false)
        }
    }

    @Test
    fun `nested arrays maps tuples and nested rows remain immutable and validate shape`() {
        val nestedArray = ClickHouseArrayColumnType(
            ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType()),
        )
        val decodedNestedArray = nestedArray.valueFromDB(arrayOf(arrayOf(1, null), arrayOf(2, 3)))
        decodedNestedArray shouldBeEqualTo listOf(listOf(1, null), listOf(2, 3))
        assertFailsWith<UnsupportedOperationException> {
            (decodedNestedArray as MutableList<List<Int?>>).clear()
        }

        val map = ClickHouseMapColumnType(ClickHouseStringColumnType(), ClickHouseInt32ColumnType())
        val decodedMap = map.valueFromDB(linkedMapOf("a" to 1, "b" to 2))
        decodedMap shouldBeEqualTo linkedMapOf("a" to 1, "b" to 2)
        decodedMap shouldNotBe linkedMapOf("a" to 1, "b" to 2)

        assertFailsWith<UnsupportedOperationException> {
            (decodedMap as MutableMap<String, Int>)["c"] = 3
        }
        assertFailsWith<IllegalArgumentException> {
            map.valueFromDB(listOf(listOf("a", 1), listOf("a", 2)))
        }

        val tuple = ClickHouseTupleColumnType(listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        tuple.valueFromDB(arrayOf<Any?>("x", 7)) shouldBeEqualTo listOf("x", 7)
        tuple.notNullValueToDB(listOf("x", 7)).shouldBeInstanceOf<com.clickhouse.data.Tuple>()

        assertFailsWith<IllegalArgumentException> {
            tuple.valueFromDB(arrayOf<Any?>("x"))
        }

        val nested = ClickHouseNestedColumnType(listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        nested.valueFromDB(listOf(listOf("x", 7), listOf("y", 8))) shouldBeEqualTo listOf(
            listOf("x", 7),
            listOf("y", 8)
        )
        assertFailsWith<IllegalArgumentException> {
            nested.valueFromDB(listOf(listOf("x")))
        }
    }

    @Test
    fun `json uuid ip decimal and enum mappings are typed`() {
        val rawJson = ClickHouseJsonColumnType()
        rawJson.valueFromDB("{\"id\":1}") shouldBeEqualTo "{\"id\":1}"
        assertFailsWith<IllegalArgumentException> { rawJson.valueFromDB("not-json") }

        val typedJson = ClickHouseJsonCodecColumnType(intCodec)
        typedJson.valueFromDB("42") shouldBeEqualTo 42
        typedJson.notNullValueToDB(42) shouldBeEqualTo "42"

        val uuid = UUID.randomUUID()
        val uuidType = ClickHouseUuidColumnType()
        uuidType.valueFromDB(uuid.toString()) shouldBeEqualTo uuid

        val ipv4Type = ClickHouseIpv4ColumnType()
        val ipv4 = InetAddress.getByName("127.0.0.1") as Inet4Address
        ipv4Type.valueFromDB("127.0.0.1") shouldBeEqualTo ipv4
        assertFailsWith<IllegalArgumentException> { ipv4Type.valueFromDB("::1") }

        val ipv6Type = ClickHouseIpv6ColumnType()
        val ipv6 = InetAddress.getByName("::1") as Inet6Address
        ipv6Type.valueFromDB("::1") shouldBeEqualTo ipv6

        val decimalType = ClickHouseDecimalColumnType(18, 4)
        decimalType.valueFromDB("12.3400") shouldBeEqualTo BigDecimal("12.3400")
        assertFailsWith<ArithmeticException> { decimalType.notNullValueToDB(BigDecimal("1.23456")) }

        val enumType = ClickHouseEnumColumnType(mapOf(State.ACTIVE to "active", State.DISABLED to "disabled"))
        enumType.valueFromDB("active") shouldBeEqualTo State.ACTIVE
        enumType.notNullValueToDB(State.DISABLED) shouldBeEqualTo "disabled"
        assertFailsWith<IllegalArgumentException> { enumType.valueFromDB(1) }
    }

    @Test
    fun `special type adapters reject string fallback and preserve ranges`() {
        assertFailsWith<IllegalStateException> {
            ClickHouseUuidColumnType().valueFromDB(123)
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseUInt64BigIntColumnType().valueFromDB("18446744073709551616")
        }
        assertFailsWith<IllegalArgumentException> {
            ClickHouseULongColumnType().valueFromDB(-1L)
        }
    }

    @Test
    fun `DateTime64 precision and zone are explicit and UInt64 keeps high bit`() {
        val zone = ZoneId.of("Asia/Seoul")
        val dateTime64 = DateTime64ColumnType(6, zone)
        dateTime64.sqlType() shouldBeEqualTo "DateTime64(6, 'Asia/Seoul')"
        val precise = Instant.parse("2026-04-25T12:34:56.123456Z")
        dateTime64.valueFromDB(precise) shouldBeEqualTo precise
        val subPrecision = Instant.parse("2026-04-25T12:34:56.1234567Z")
        dateTime64.valueFromDB(subPrecision) shouldBeEqualTo Instant.parse("2026-04-25T12:34:56.123456Z")

        val highBit = BigDecimal("9223372036854775808").toBigInteger()
        val unsigned = ClickHouseULongColumnType()
        unsigned.valueFromDB(highBit) shouldBeEqualTo highBit.toString().toULong()
        unsigned.notNullValueToDB(highBit.toString().toULong()) shouldBeEqualTo highBit
        ClickHouseUInt64BigIntColumnType().notNullValueToDB(highBit) shouldBeEqualTo highBit
    }

    @Test
    fun `setParameter and readObject apply the same conversion and nullability`() {
        val statement = RecordingPreparedStatement()
        val array = ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType())
        array.setParameter(statement, 1, array.notNullValueToDB(listOf(1, null, 3)))
        (statement.boundValue as Array<*>).toList() shouldBeEqualTo listOf(1, null, 3)

        val row = RecordingRow(arrayOf<Any?>(1, null, 3))
        array.readObject(row, 1) shouldBeEqualTo listOf(1, null, 3)

        val nullableArray = ClickHouseNullableArrayColumnType(ClickHouseStringColumnType())
        nullableArray.readObject(RecordingRow(null), 1).shouldBeNull()
        nullableArray.setParameter(statement, 2, null)
        statement.nullIndex shouldBeEqualTo 2
    }

    private class RecordingPreparedStatement: PreparedStatementApi {
        var boundValue: Any? = null
        var nullIndex: Int? = null

        override fun set(index: Int, value: Any, columnType: IColumnType<*>) {
            boundValue = value
        }

        override fun setNull(index: Int, columnType: IColumnType<*>) {
            nullIndex = index
            boundValue = null
        }

        override fun setInputStream(index: Int, inputStream: InputStream, setAsBlobObject: Boolean) = Unit

        override fun setArray(index: Int, type: ArrayColumnType<*, *>, array: Array<*>) {
            boundValue = array
        }
    }

    private class RecordingRow(private val value: Any?): RowApi {
        override fun getObject(index: Int): Any? = value
        override fun getObject(name: String): Any? = value

        @Suppress("UNCHECKED_CAST")
        override fun <T> getObject(index: Int, type: Class<T>): T? = value as T?

        @Suppress("UNCHECKED_CAST")
        override fun <T> getObject(name: String, type: Class<T>): T? = value as T?

        override fun getString(index: Int): String? = value?.toString()
    }
}
