package io.bluetape4k.exposed.clickhouse.types

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContainIgnoringCase
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.exposed.clickhouse.AbstractClickHouseTest
import io.bluetape4k.support.equalsIgnoreCase
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    @Suppress("UnusedReceiverParameter")
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

    @Suppress("UnusedReceiverParameter")
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
            List(meta.columnCount) {
                val index = it + 1
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

        metadata.shouldNotBeEmpty()
        metadata.forEach { column ->
            column.typeName.shouldNotBeEmpty()
            column.jdbcType shouldNotBeEqualTo JDBCType.NULL.vendorTypeNumber
            column.nullability shouldBeEqualTo ResultSetMetaData.columnNoNulls
        }
        val decimalMetadata = metadata.first { it.name.equals("amount", ignoreCase = true) }
        decimalMetadata.typeName shouldContainIgnoringCase "Decimal"
        decimalMetadata.precision shouldBeEqualTo 18
        decimalMetadata.scale shouldBeEqualTo 4

        metadata.first { it.name.equalsIgnoreCase("nested_array") }
            .typeName shouldContainIgnoringCase "Array"

        metadata.first { it.name.equalsIgnoreCase("labels") }
            .typeName shouldContainIgnoringCase "Map"

        metadata.first { it.name.equalsIgnoreCase("tuple") }
            .typeName shouldContainIgnoringCase "Tuple"

        metadata.first { it.name.equalsIgnoreCase("state") }
            .typeName shouldContainIgnoringCase "Enum"
    }

    private fun JdbcTransaction.assertJdbcBoundaries() {
        // The V2 driver exposes Array/Struct objects for composite columns.
        exec("SELECT nullable_array, tuple FROM ${ClickHouseTypeBoundaryFixture.tableName} LIMIT 1") { resultSet ->
            check(resultSet.next())
            resultSet.getArray(1)?.free()
            (resultSet.getObject(2) as? java.sql.Struct)?.attributes
        }

        // Verify the JDBC factory boundary independently from Exposed's row mapper.
        val arrayType = ClickHouseArrayNullableElementsColumnType(ClickHouseStringColumnType())
        val jdbcConnection = connection.connection as java.sql.Connection

        jdbcConnection.createArrayOf("Nullable(String)", arrayOf<Any?>("alpha", null, "omega"))
            .let { jdbcArray ->
                try {
                    arrayType.valueFromDB(jdbcArray) shouldBeEqualTo listOf("alpha", null, "omega")
                } finally {
                    // valueFromDB owns and frees a JDBC Array returned by the driver.
                    assertFailsWith<java.sql.SQLException> {
                        jdbcArray.array
                    }
                }
            }

        exec("SELECT nullable_array FROM ${ClickHouseTypeBoundaryFixture.tableName} LIMIT 1") { resultSet ->
            check(resultSet.next())
            val jdbcArray = resultSet.getArray(1)
            try {
                arrayType.valueFromDB(jdbcArray.resultSet) shouldBeEqualTo
                    ClickHouseTypeBoundaryFixture.nullableArrayValue

            } finally {
                jdbcArray.free()
            }
        }

        val tupleType = ClickHouseTupleColumnType(
            listOf(ClickHouseStringColumnType(), ClickHouseInt32ColumnType()),
        )

        val struct = jdbcConnection.createStruct("Tuple(String, Int32)", arrayOf<Any?>("kr", 7))
        tupleType.valueFromDB(struct) shouldBeEqualTo ClickHouseTypeBoundaryFixture.tupleValue
    }
}
