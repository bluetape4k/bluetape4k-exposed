package io.bluetape4k.exposed.clickhouse.types

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * ClickHouse Basic / Signed Int / Float / Unsigned 컬럼 타입의 단위 테스트.
 *
 * 실 DB 연결이 필요하지 않은 [ColumnType.sqlType] / [ColumnType.valueFromDB] /
 * [ColumnType.notNullValueToDB] 의 동작만을 검증합니다.
 */
class UnsignedTypesTest {

    // ────────────────────────────────────────────────────────────
    // sqlType()
    // ────────────────────────────────────────────────────────────

    @Test
    fun `String sqlType is String`() {
        ClickHouseStringColumnType().sqlType() shouldBeEqualTo "String"
    }

    @Test
    fun `FixedString sqlType is FixedString(n)`() {
        ClickHouseFixedStringColumnType(16).sqlType() shouldBeEqualTo "FixedString(16)"
        ClickHouseFixedStringColumnType(1).sqlType() shouldBeEqualTo "FixedString(1)"
    }

    @Test
    fun `Float32 sqlType is Float32`() {
        ClickHouseFloat32ColumnType().sqlType() shouldBeEqualTo "Float32"
    }

    @Test
    fun `Float64 sqlType is Float64`() {
        ClickHouseFloat64ColumnType().sqlType() shouldBeEqualTo "Float64"
    }

    @Test
    fun `Int8 sqlType is Int8`() {
        ClickHouseInt8ColumnType().sqlType() shouldBeEqualTo "Int8"
    }

    @Test
    fun `Int16 sqlType is Int16`() {
        ClickHouseInt16ColumnType().sqlType() shouldBeEqualTo "Int16"
    }

    @Test
    fun `Int32 sqlType is Int32`() {
        ClickHouseInt32ColumnType().sqlType() shouldBeEqualTo "Int32"
    }

    @Test
    fun `Int64 sqlType is Int64`() {
        ClickHouseInt64ColumnType().sqlType() shouldBeEqualTo "Int64"
    }

    @Test
    fun `UByte sqlType is UInt8`() {
        ClickHouseUByteColumnType().sqlType() shouldBeEqualTo "UInt8"
    }

    @Test
    fun `UShort sqlType is UInt16`() {
        ClickHouseUShortColumnType().sqlType() shouldBeEqualTo "UInt16"
    }

    @Test
    fun `UInt sqlType is UInt32`() {
        ClickHouseUIntColumnType().sqlType() shouldBeEqualTo "UInt32"
    }

    @Test
    fun `ULong sqlType is UInt64`() {
        ClickHouseULongColumnType().sqlType() shouldBeEqualTo "UInt64"
    }

    @Test
    fun `UInt64BigInt sqlType is UInt64`() {
        ClickHouseUInt64BigIntColumnType().sqlType() shouldBeEqualTo "UInt64"
    }

    @Test
    fun `Nullable wraps inner sqlType`() {
        ClickHouseNullableColumnType(ClickHouseInt32ColumnType()).sqlType() shouldBeEqualTo "Nullable(Int32)"
        ClickHouseNullableColumnType(ClickHouseStringColumnType()).sqlType() shouldBeEqualTo "Nullable(String)"
        ClickHouseNullableColumnType(ClickHouseFixedStringColumnType(8)).sqlType() shouldBeEqualTo "Nullable(FixedString(8))"
    }

    // ────────────────────────────────────────────────────────────
    // valueFromDB() — cross-cast 방어 로직
    // ────────────────────────────────────────────────────────────

    @Test
    fun `UByte valueFromDB accepts Short`() {
        val col = ClickHouseUByteColumnType()
        col.valueFromDB(255.toShort()) shouldBeEqualTo 255.toUByte()
        col.valueFromDB(0.toShort()) shouldBeEqualTo 0.toUByte()
    }

    @Test
    fun `UShort valueFromDB accepts Int`() {
        val col = ClickHouseUShortColumnType()
        col.valueFromDB(65535) shouldBeEqualTo 65535.toUShort()
        col.valueFromDB(0) shouldBeEqualTo 0.toUShort()
    }

    @Test
    fun `UInt valueFromDB accepts Long`() {
        val col = ClickHouseUIntColumnType()
        col.valueFromDB(4_294_967_295L) shouldBeEqualTo 4_294_967_295L.toUInt()
        col.valueFromDB(0L) shouldBeEqualTo 0u
    }

    @Test
    fun `ULong valueFromDB accepts BigInteger`() {
        val col = ClickHouseULongColumnType()
        col.valueFromDB(java.math.BigInteger.valueOf(123L)) shouldBeEqualTo 123uL
    }

    @Test
    fun `UInt64BigInt valueFromDB accepts Long`() {
        val col = ClickHouseUInt64BigIntColumnType()
        col.valueFromDB(123L) shouldBeEqualTo java.math.BigInteger.valueOf(123L)
    }

    @Test
    fun `Int32 valueFromDB accepts Long`() {
        val col = ClickHouseInt32ColumnType()
        col.valueFromDB(42L) shouldBeEqualTo 42
    }

    @Test
    fun `Float32 valueFromDB accepts Double`() {
        val col = ClickHouseFloat32ColumnType()
        col.valueFromDB(1.5) shouldBeEqualTo 1.5f
    }

    @Test
    fun `Float64 valueFromDB accepts Float`() {
        val col = ClickHouseFloat64ColumnType()
        col.valueFromDB(1.5f) shouldBeEqualTo 1.5
    }

    @Test
    fun `String valueFromDB falls back to toString`() {
        val col = ClickHouseStringColumnType()
        col.valueFromDB(123) shouldBeEqualTo "123"
        col.valueFromDB("abc") shouldBeEqualTo "abc"
    }

    @Test
    fun `FixedString valueFromDB falls back to toString`() {
        val col = ClickHouseFixedStringColumnType(2)
        col.valueFromDB("KR") shouldBeEqualTo "KR"
        col.valueFromDB(12) shouldBeEqualTo "12"
    }

    @Test
    fun `signed integers accept exact numeric and string values`() {
        ClickHouseInt8ColumnType().valueFromDB(1.toByte()) shouldBeEqualTo 1.toByte()
        ClickHouseInt8ColumnType().valueFromDB("2") shouldBeEqualTo 2.toByte()
        ClickHouseInt16ColumnType().valueFromDB(3.toShort()) shouldBeEqualTo 3.toShort()
        ClickHouseInt16ColumnType().valueFromDB("4") shouldBeEqualTo 4.toShort()
        ClickHouseInt32ColumnType().valueFromDB(5) shouldBeEqualTo 5
        ClickHouseInt32ColumnType().valueFromDB("6") shouldBeEqualTo 6
        ClickHouseInt64ColumnType().valueFromDB(7L) shouldBeEqualTo 7L
        ClickHouseInt64ColumnType().valueFromDB("8") shouldBeEqualTo 8L
    }

    @Test
    fun `floating point types accept exact and string values`() {
        ClickHouseFloat32ColumnType().valueFromDB(1.25f) shouldBeEqualTo 1.25f
        ClickHouseFloat32ColumnType().valueFromDB("2.5") shouldBeEqualTo 2.5f
        ClickHouseFloat64ColumnType().valueFromDB(3.75) shouldBeEqualTo 3.75
        ClickHouseFloat64ColumnType().valueFromDB("4.5") shouldBeEqualTo 4.5
    }

    @Test
    fun `unsigned types accept exact and string values`() {
        ClickHouseUByteColumnType().valueFromDB(1.toUByte()) shouldBeEqualTo 1.toUByte()
        ClickHouseUByteColumnType().valueFromDB("2") shouldBeEqualTo 2.toUByte()
        ClickHouseUShortColumnType().valueFromDB(3.toUShort()) shouldBeEqualTo 3.toUShort()
        ClickHouseUShortColumnType().valueFromDB("4") shouldBeEqualTo 4.toUShort()
        ClickHouseUIntColumnType().valueFromDB(5u) shouldBeEqualTo 5u
        ClickHouseUIntColumnType().valueFromDB("6") shouldBeEqualTo 6u
        ClickHouseULongColumnType().valueFromDB(7uL) shouldBeEqualTo 7uL
        ClickHouseULongColumnType().valueFromDB(8L) shouldBeEqualTo 8uL
        ClickHouseULongColumnType().valueFromDB("9") shouldBeEqualTo 9uL
        ClickHouseUInt64BigIntColumnType().valueFromDB(BigInteger.TEN) shouldBeEqualTo BigInteger.TEN
        ClickHouseUInt64BigIntColumnType().valueFromDB("11") shouldBeEqualTo BigInteger.valueOf(11L)
    }

    @Test
    fun `ULong valueFromDB rejects out of range BigInteger`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseULongColumnType().valueFromDB(BigInteger.ONE.shiftLeft(65))
        }
    }

    @Test
    fun `column types reject unsupported database values`() {
        val unsupported = Any()
        assertFailsWith<IllegalStateException> { ClickHouseFloat32ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseFloat64ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseInt8ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseInt16ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseInt32ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseInt64ColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseUByteColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseUShortColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseUIntColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseULongColumnType().valueFromDB(unsupported) }
        assertFailsWith<IllegalStateException> { ClickHouseUInt64BigIntColumnType().valueFromDB(unsupported) }
    }

    // ────────────────────────────────────────────────────────────
    // notNullValueToDB() — JDBC 변환
    // ────────────────────────────────────────────────────────────

    @Test
    fun `UByte notNullValueToDB returns Short`() {
        val col = ClickHouseUByteColumnType()
        col.notNullValueToDB(200.toUByte()) shouldBeEqualTo 200.toShort()
    }

    @Test
    fun `UShort notNullValueToDB returns Int`() {
        val col = ClickHouseUShortColumnType()
        col.notNullValueToDB(60000.toUShort()) shouldBeEqualTo 60000
    }

    @Test
    fun `UInt notNullValueToDB returns Long`() {
        val col = ClickHouseUIntColumnType()
        col.notNullValueToDB(4_000_000_000u) shouldBeEqualTo 4_000_000_000L
    }

    @Test
    fun `ULong notNullValueToDB returns Long`() {
        val col = ClickHouseULongColumnType()
        col.notNullValueToDB(123uL) shouldBeEqualTo 123L
    }

    @Test
    fun `basic notNullValueToDB returns database values`() {
        ClickHouseStringColumnType().notNullValueToDB("abc") shouldBeEqualTo "abc"
        ClickHouseFixedStringColumnType(3).notNullValueToDB("abc") shouldBeEqualTo "abc"
        ClickHouseFloat32ColumnType().notNullValueToDB(1.5f) shouldBeEqualTo 1.5f
        ClickHouseFloat64ColumnType().notNullValueToDB(2.5) shouldBeEqualTo 2.5
        ClickHouseInt8ColumnType().notNullValueToDB(1.toByte()) shouldBeEqualTo 1.toByte()
        ClickHouseInt16ColumnType().notNullValueToDB(2.toShort()) shouldBeEqualTo 2.toShort()
        ClickHouseInt32ColumnType().notNullValueToDB(3) shouldBeEqualTo 3
        ClickHouseInt64ColumnType().notNullValueToDB(4L) shouldBeEqualTo 4L
        ClickHouseUInt64BigIntColumnType().notNullValueToDB(BigInteger.TEN) shouldBeEqualTo BigInteger.TEN
    }

    @Test
    fun `Nullable delegates conversion to inner type`() {
        val col = ClickHouseNullableColumnType(ClickHouseInt32ColumnType())

        col.valueFromDB("42") shouldBeEqualTo 42
        col.notNullValueToDB(43) shouldBeEqualTo 43
    }

    @Test
    fun `FixedString requires positive length`() {
        assertFailsWith<IllegalArgumentException> {
            ClickHouseFixedStringColumnType(0)
        }
    }

    @Test
    fun `table extension builders register expected column types`() {
        object: Table("clickhouse_type_builder_probe") {
            val string = chString("string_value")
            val fixed = fixedString("fixed_value", 8)
            val float32 = chFloat32("float32_value")
            val float64 = chFloat64("float64_value")
            val int8 = chInt8("int8_value")
            val int16 = chInt16("int16_value")
            val int32 = chInt32("int32_value")
            val int64 = chInt64("int64_value")
            val nullable = chNullable("nullable_value", ClickHouseStringColumnType())
            val uByte = chUByte("ubyte_value")
            val uShort = chUShort("ushort_value")
            val uInt = chUInt("uint_value")
            val uLong = chULong("ulong_value")
            val uLongBigInt = chUInt64BigInt("ulong_bigint_value")
        }.let { table ->
            table.string.columnType.sqlType() shouldBeEqualTo "String"
            table.fixed.columnType.sqlType() shouldBeEqualTo "FixedString(8)"
            table.float32.columnType.sqlType() shouldBeEqualTo "Float32"
            table.float64.columnType.sqlType() shouldBeEqualTo "Float64"
            table.int8.columnType.sqlType() shouldBeEqualTo "Int8"
            table.int16.columnType.sqlType() shouldBeEqualTo "Int16"
            table.int32.columnType.sqlType() shouldBeEqualTo "Int32"
            table.int64.columnType.sqlType() shouldBeEqualTo "Int64"
            table.nullable.columnType.sqlType() shouldBeEqualTo "Nullable(String)"
            table.uByte.columnType.sqlType() shouldBeEqualTo "UInt8"
            table.uShort.columnType.sqlType() shouldBeEqualTo "UInt16"
            table.uInt.columnType.sqlType() shouldBeEqualTo "UInt32"
            table.uLong.columnType.sqlType() shouldBeEqualTo "UInt64"
            table.uLongBigInt.columnType.sqlType() shouldBeEqualTo "UInt64"
        }
    }
}
