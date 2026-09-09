package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

/**
 * ClickHouse JDBC V2 복합·특수 타입의 H2 selector-safe 의미론 경계를 고정한다.
 *
 * 실제 ClickHouse wire round-trip은 [ClickHouseComplexTypesTest]에서 검증하고,
 * 이 테스트는 컨테이너 없이 ColumnType 계약·불변성·실패 경계를 검증한다.
 */
class ClickHouseComplexTypesH2Test {

    private enum class State { ACTIVE, DISABLED }

    private val intCodec = object : ClickHouseJsonCodec<Int> {
        override fun encode(value: Int): String = value.toString()
        override fun decode(json: String): Int = json.trim().toInt()
    }

    private object ComplexTable: Table("complex_types_h2") {
        val nullableElements = chArrayNullableElements("nullable_elements", ClickHouseInt32ColumnType())
        val nullableContainer = chNullableArray("nullable_container", ClickHouseStringColumnType())
        val nestedArray = chArray("nested_array", ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType()))
        val labels = chMap("labels", ClickHouseStringColumnType(), ClickHouseInt32ColumnType())
        val tuple = chTuple("tuple", listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        val nested = chNested("nested", listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        val rawJson = chJson("raw_json")
        val typedJson = chJson("typed_json", object : ClickHouseJsonCodec<Int> {
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
        assertEquals("Array(Nullable(Int32))", ComplexTable.nullableElements.columnType.sqlType())
        assertEquals("Nullable(Array(String))", ComplexTable.nullableContainer.columnType.sqlType())
        assertEquals("Array(Array(Nullable(Int32)))", ComplexTable.nestedArray.columnType.sqlType())
        assertEquals("Map(String, Int32)", ComplexTable.labels.columnType.sqlType())
        assertEquals("Tuple(String, Int32)", ComplexTable.tuple.columnType.sqlType())
        assertEquals("Nested(String, Int32)", ComplexTable.nested.columnType.sqlType())
        assertEquals("JSON", ComplexTable.rawJson.columnType.sqlType())
        assertEquals("UUID", ComplexTable.uuid.columnType.sqlType())
        assertEquals("IPv4", ComplexTable.ipv4.columnType.sqlType())
        assertEquals("IPv6", ComplexTable.ipv6.columnType.sqlType())
        assertEquals("Decimal(18, 4)", ComplexTable.decimal.columnType.sqlType())
        assertEquals("Enum8('active'=1, 'disabled'=2)", ComplexTable.state.columnType.sqlType())
    }

    @Test
    fun `nullable array preserves null elements and defensive copies`() {
        val type = ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType())
        val source = arrayOf<Any?>(1, null, 3)

        val decoded = type.valueFromDB(source)
        assertEquals(listOf(1, null, 3), decoded)
        source[0] = 99
        assertEquals(listOf(1, null, 3), decoded)

        val encoded = type.notNullValueToDB(listOf(1, null, 3)) as Array<*>
        assertEquals(listOf(1, null, 3), encoded.toList())
        assertThrows(IllegalArgumentException::class.java) {
            type.valueFromDB("[1,null]")
        }
    }

    @Test
    fun `nullable array distinguishes nullable container from nullable elements`() {
        val type = ClickHouseNullableArrayColumnType(ClickHouseStringColumnType())
        assertEquals(null, type.valueFromDB(null))
        assertEquals(listOf("a", null), type.valueFromDB(listOf("a", null)))
        assertEquals(arrayOf("a", null).toList(), (type.notNullValueToDB(listOf("a", null)) as Array<*>).toList())
    }

    @Test
    fun `nested arrays maps tuples and nested rows remain immutable and validate shape`() {
        val nestedArray = ClickHouseArrayColumnType(ClickHouseArrayNullableElementsColumnType(ClickHouseInt32ColumnType()))
        assertEquals(listOf(listOf(1, null), listOf(2, 3)), nestedArray.valueFromDB(arrayOf(arrayOf(1, null), arrayOf(2, 3))))

        val map = ClickHouseMapColumnType(ClickHouseStringColumnType(), ClickHouseInt32ColumnType())
        val decodedMap = map.valueFromDB(linkedMapOf("a" to 1, "b" to 2))
        assertEquals(linkedMapOf("a" to 1, "b" to 2), decodedMap)
        assertFalse(decodedMap === linkedMapOf("a" to 1, "b" to 2))
        assertThrows(IllegalArgumentException::class.java) {
            map.valueFromDB(listOf(listOf("a", 1), listOf("a", 2)))
        }

        val tuple = ClickHouseTupleColumnType(listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        assertEquals(listOf("x", 7), tuple.valueFromDB(arrayOf("x", 7)))
        assertThrows(IllegalArgumentException::class.java) { tuple.valueFromDB(arrayOf("x")) }

        val nested = ClickHouseNestedColumnType(listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()))
        assertEquals(listOf(listOf("x", 7), listOf("y", 8)), nested.valueFromDB(listOf(listOf("x", 7), listOf("y", 8))))
        assertThrows(IllegalArgumentException::class.java) { nested.valueFromDB(listOf(listOf("x"))) }
    }

    @Test
    fun `json uuid ip decimal and enum mappings are typed`() {
        val rawJson = ClickHouseJsonColumnType()
        assertEquals("{\"id\":1}", rawJson.valueFromDB("{\"id\":1}"))
        assertThrows(IllegalArgumentException::class.java) { rawJson.valueFromDB("not-json") }

        val typedJson = ClickHouseJsonCodecColumnType(intCodec)
        assertEquals(42, typedJson.valueFromDB("42"))
        assertEquals("42", typedJson.notNullValueToDB(42))

        val uuid = UUID.randomUUID()
        val uuidType = ClickHouseUuidColumnType()
        assertEquals(uuid, uuidType.valueFromDB(uuid.toString()))

        val ipv4Type = ClickHouseIpv4ColumnType()
        val ipv4 = InetAddress.getByName("127.0.0.1") as Inet4Address
        assertEquals(ipv4, ipv4Type.valueFromDB("127.0.0.1"))
        assertThrows(IllegalArgumentException::class.java) { ipv4Type.valueFromDB("::1") }

        val ipv6Type = ClickHouseIpv6ColumnType()
        val ipv6 = InetAddress.getByName("::1") as Inet6Address
        assertEquals(ipv6, ipv6Type.valueFromDB("::1"))

        val decimalType = ClickHouseDecimalColumnType(18, 4)
        assertEquals(BigDecimal("12.3400"), decimalType.valueFromDB("12.3400"))
        assertThrows(ArithmeticException::class.java) { decimalType.notNullValueToDB(BigDecimal("1.23456")) }

        val enumType = ClickHouseEnumColumnType(mapOf(State.ACTIVE to "active", State.DISABLED to "disabled"))
        assertEquals(State.ACTIVE, enumType.valueFromDB("active"))
        assertEquals("disabled", enumType.notNullValueToDB(State.DISABLED))
        assertThrows(IllegalArgumentException::class.java) { enumType.valueFromDB(1) }
    }

    @Test
    fun `special type adapters reject string fallback and preserve ranges`() {
        assertThrows(IllegalStateException::class.java) {
            ClickHouseUuidColumnType().valueFromDB(123)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickHouseUInt64BigIntColumnType().valueFromDB("18446744073709551616")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClickHouseULongColumnType().valueFromDB(-1L)
        }
    }
}
