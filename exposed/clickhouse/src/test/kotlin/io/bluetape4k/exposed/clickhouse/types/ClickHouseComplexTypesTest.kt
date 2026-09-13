package io.bluetape4k.exposed.clickhouse.types

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.clickhouse.AbstractClickHouseTest
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.JDBCType
import java.sql.ResultSetMetaData

/**
 * ClickHouse JDBC V2 복합·특수 타입의 실제 DDL/insert/select/metadata 검증.
 *
 * H2 converter 테스트와 분리해 driver가 반환하는 `java.sql.Array`/`Struct`,
 * nested signature, precision/scale을 실제 서버에서 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClickHouseComplexTypesTest: AbstractClickHouseTest() {

    @Test
    fun `ClickHouse complex type round trip preserves nullability order and metadata`() = transaction(db) {
        // ClickHouse 26.7 keeps JSON behind this session setting.
        exec("SET allow_experimental_json_type = 1")
        SchemaUtils.create(ClickHouseComplexTypesTable)
        try {
            insertFixture()
            assertRoundTrip()
            assertMetadata()
            assertJdbcBoundaries()
        } finally {
            SchemaUtils.drop(ClickHouseComplexTypesTable)
        }
    }

    private fun JdbcTransaction.insertFixture() {
        ClickHouseComplexTypesTable.insert { row ->
            row[ClickHouseComplexTypesTable.id] = ClickHouseTypeBoundaryFixture.seed
            row[ClickHouseComplexTypesTable.nullableArray] = ClickHouseTypeBoundaryFixture.nullableArrayValue
            row[ClickHouseComplexTypesTable.nestedArray] = ClickHouseTypeBoundaryFixture.nestedArrayValue
            row[ClickHouseComplexTypesTable.labels] = ClickHouseTypeBoundaryFixture.labelsValue
            row[ClickHouseComplexTypesTable.tuple] = ClickHouseTypeBoundaryFixture.tupleValue
            row[ClickHouseComplexTypesTable.json] = ClickHouseTypeBoundaryFixture.jsonValue
            row[ClickHouseComplexTypesTable.uuid] = ClickHouseTypeBoundaryFixture.uuidValue
            row[ClickHouseComplexTypesTable.ipv4] = ClickHouseTypeBoundaryFixture.ipv4Value
            row[ClickHouseComplexTypesTable.ipv6] = ClickHouseTypeBoundaryFixture.ipv6Value
            row[ClickHouseComplexTypesTable.eventTime] = ClickHouseTypeBoundaryFixture.eventTimeValue
            row[ClickHouseComplexTypesTable.amount] = ClickHouseTypeBoundaryFixture.amountValue
            row[ClickHouseComplexTypesTable.counter] = java.math.BigInteger.valueOf(ClickHouseTypeBoundaryFixture.seed)
            row[ClickHouseComplexTypesTable.state] = ClickHouseTypeBoundaryFixture.stateValue
        }
    }

    private fun JdbcTransaction.assertRoundTrip() {
        val selected = ClickHouseComplexTypesTable.selectAll().single()
        selected[ClickHouseComplexTypesTable.nullableArray] shouldBeEqualTo
            ClickHouseTypeBoundaryFixture.nullableArrayValue
        selected[ClickHouseComplexTypesTable.nestedArray] shouldBeEqualTo ClickHouseTypeBoundaryFixture.nestedArrayValue
        selected[ClickHouseComplexTypesTable.labels] shouldBeEqualTo ClickHouseTypeBoundaryFixture.labelsValue
        selected[ClickHouseComplexTypesTable.tuple] shouldBeEqualTo ClickHouseTypeBoundaryFixture.tupleValue
        selected[ClickHouseComplexTypesTable.json] shouldBeEqualTo ClickHouseTypeBoundaryFixture.jsonValue
        selected[ClickHouseComplexTypesTable.uuid] shouldBeEqualTo ClickHouseTypeBoundaryFixture.uuidValue
        selected[ClickHouseComplexTypesTable.ipv4] shouldBeEqualTo ClickHouseTypeBoundaryFixture.ipv4Value
        selected[ClickHouseComplexTypesTable.ipv6] shouldBeEqualTo ClickHouseTypeBoundaryFixture.ipv6Value
        selected[ClickHouseComplexTypesTable.amount] shouldBeEqualTo ClickHouseTypeBoundaryFixture.amountValue
        selected[ClickHouseComplexTypesTable.state] shouldBeEqualTo ClickHouseTypeBoundaryFixture.stateValue
    }

    private fun JdbcTransaction.assertMetadata() {
        data class ColumnMetadata(
            val name: String,
            val typeName: String,
            val jdbcType: Int,
            val nullability: Int,
            val precision: Int,
            val scale: Int,
        )
        val metadata = exec(
            "SELECT nullable_array, nested_array, labels, tuple, json, uuid, ipv4, ipv6, amount, counter, state " +
                "FROM ${ClickHouseTypeBoundaryFixture.tableName} LIMIT 0",
        ) { resultSet ->
            val meta = resultSet.metaData
            (1..meta.columnCount).map { index ->
                ColumnMetadata(
                    name = meta.getColumnName(index),
                    typeName = meta.getColumnTypeName(index),
                    jdbcType = meta.getColumnType(index),
                    nullability = meta.isNullable(index),
                    precision = meta.getPrecision(index),
                    scale = meta.getScale(index),
                )
            }
        } ?: emptyList()

        assertTrue(metadata.isNotEmpty())
        metadata.forEach { column ->
            assertTrue(column.typeName.isNotBlank())
            assertTrue(column.jdbcType != JDBCType.NULL.vendorTypeNumber)
            assertTrue(column.nullability == ResultSetMetaData.columnNoNulls)
        }
        val decimalMetadata = metadata.first { it.name.equals("amount", ignoreCase = true) }
        assertTrue(decimalMetadata.typeName.contains("Decimal", ignoreCase = true))
        assertEquals(18, decimalMetadata.precision)
        assertEquals(4, decimalMetadata.scale)
        assertTrue(metadata.first { it.name.equals("nested_array", ignoreCase = true) }
            .typeName.contains("Array", ignoreCase = true))
        assertTrue(metadata.first { it.name.equals("labels", ignoreCase = true) }
            .typeName.contains("Map", ignoreCase = true))
        assertTrue(metadata.first { it.name.equals("tuple", ignoreCase = true) }
            .typeName.contains("Tuple", ignoreCase = true))
        assertTrue(metadata.first { it.name.equals("state", ignoreCase = true) }
            .typeName.contains("Enum", ignoreCase = true))
    }

    private fun JdbcTransaction.assertJdbcBoundaries() {
        // The V2 driver exposes Array/Struct objects for composite columns.
        exec("SELECT nullable_array, tuple FROM ${ClickHouseTypeBoundaryFixture.tableName} LIMIT 1") { resultSet ->
            check(resultSet.next())
            resultSet.getArray(1)?.free()
            (resultSet.getObject(2) as? java.sql.Struct)?.getAttributes()
        }

        // Verify the JDBC factory boundary independently from Exposed's row mapper.
        val arrayType = ClickHouseArrayNullableElementsColumnType(ClickHouseStringColumnType())
        val jdbcConnection = connection.connection as java.sql.Connection
        jdbcConnection.createArrayOf("Nullable(String)", arrayOf<Any?>("alpha", null, "omega"))
            .let { jdbcArray ->
                try {
                    assertEquals(
                        listOf("alpha", null, "omega"),
                        arrayType.valueFromDB(jdbcArray),
                    )
                } finally {
                    // valueFromDB owns and frees a JDBC Array returned by the driver.
                    assertThrows(java.sql.SQLException::class.java) { jdbcArray.getArray() }
                }
            }

        exec("SELECT nullable_array FROM ${ClickHouseTypeBoundaryFixture.tableName} LIMIT 1") { resultSet ->
            check(resultSet.next())
            val jdbcArray = resultSet.getArray(1)
            try {
                assertEquals(
                    ClickHouseTypeBoundaryFixture.nullableArrayValue,
                    arrayType.valueFromDB(jdbcArray.getResultSet()),
                )
            } finally {
                jdbcArray.free()
            }
        }

        val tupleType = ClickHouseTupleColumnType(
            listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()),
        )
        jdbcConnection.createStruct("Tuple(String, Int32)", arrayOf<Any?>("kr", 7)).let { jdbcStruct ->
            assertEquals(ClickHouseTypeBoundaryFixture.tupleValue, tupleType.valueFromDB(jdbcStruct))
        }
    }
}
