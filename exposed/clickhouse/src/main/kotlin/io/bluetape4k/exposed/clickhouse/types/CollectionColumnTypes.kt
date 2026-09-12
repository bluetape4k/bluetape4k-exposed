package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import java.sql.ResultSet
import java.sql.Struct
import java.util.Collections
import java.util.LinkedHashMap

/** `Array(Nullable(T))` — 배열 컨테이너는 필수이고 원소가 nullable입니다. */
class ClickHouseArrayNullableElementsColumnType<T: Any>(val inner: ColumnType<T>): ColumnType<List<T?>>() {
    override fun sqlType(): String = "Array(Nullable(${inner.sqlType()}))"

    override fun valueFromDB(value: Any): List<T?> =
        clickHouseImmutableList(clickHouseArrayElements(value).map { element ->
            if (element == null) {
                null
            } else {
                @Suppress("UNCHECKED_CAST")
                clickHouseValueFromDB(inner, element) as T
            }
        })

    override fun notNullValueToDB(value: List<T?>): Any =
        value.map { element -> element?.let { clickHouseValueToDB(inner, it) } }.toTypedArray()

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** `Nullable(Array(T))` — 배열 컨테이너가 nullable인 배열입니다. */
class ClickHouseNullableArrayColumnType<T: Any>(
    val inner: ColumnType<T>,
    val nullableContainer: Boolean = true,
): ColumnType<List<T>>(nullableContainer) {

    init {
        require(nullableContainer) {
            "Use ClickHouseArrayNullableElementsColumnType for Array(Nullable(T))"
        }
    }

    override fun sqlType(): String = if (nullableContainer) {
        "Nullable(Array(${inner.sqlType()}))"
    } else {
        "Array(Nullable(${inner.sqlType()}))"
    }

    override fun valueFromDB(value: Any): List<T> =
        clickHouseImmutableList(clickHouseArrayElements(value).map { element ->
            if (element == null) {
                throw IllegalArgumentException(
                    "Nullable(Array(T)) does not allow null elements; use chArrayNullableElements for that shape",
                )
            } else {
                @Suppress("UNCHECKED_CAST")
                clickHouseValueFromDB(inner, element) as T
            }
        })

    override fun notNullValueToDB(value: List<T>): Any =
        value.map { element -> clickHouseValueToDB(inner, element) }.toTypedArray()

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** ClickHouse `Map(K, V)` 컬럼 타입. */
class ClickHouseMapColumnType<K: Any, V>(
    val keyType: ColumnType<K>,
    val valueType: ColumnType<V>,
): ColumnType<Map<K, V>>() {

    init {
        require(!keyType.nullable) { "ClickHouse Map keys cannot be nullable" }
    }

    override fun sqlType(): String = "Map(${keyType.sqlType()}, ${valueType.sqlType()})"

    override fun valueFromDB(value: Any): Map<K, V> {
        val entries = when (value) {
            is Map<*, *> -> value.entries.map { it.key to it.value }
            else -> clickHouseElements(value).map(::clickHouseMapEntry)
        }
        val result = LinkedHashMap<K, V>(entries.size)
        entries.forEach { (rawKey, rawValue) ->
            require(rawKey != null) { "ClickHouse Map key cannot be null" }
            @Suppress("UNCHECKED_CAST")
            val key = clickHouseValueFromDB(keyType, rawKey) as K
            require(!result.containsKey(key)) { "Duplicate ClickHouse Map key: $key" }
            @Suppress("UNCHECKED_CAST")
            val mappedValue = clickHouseValueFromDB(valueType, rawValue) as V
            result[key] = mappedValue
        }
        return Collections.unmodifiableMap(result)
    }

    override fun notNullValueToDB(value: Map<K, V>): Any {
        val result = LinkedHashMap<Any, Any?>(value.size)
        value.forEach { (key, mappedValue) ->
            val dbKey = clickHouseValueToDB(keyType, key)
            require(dbKey != null) { "ClickHouse Map key cannot be null" }
            require(!result.containsKey(dbKey)) { "Duplicate ClickHouse Map key: $dbKey" }
            result[dbKey] = clickHouseValueToDB(valueType, mappedValue)
        }
        return result
    }

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

private fun clickHouseMapEntry(value: Any?): Pair<Any?, Any?> = when (value) {
    is Pair<*, *> -> value.first to value.second
    is Struct -> {
        val attributes = value.attributes.toList()
        require(attributes.size == 2) { "ClickHouse Map entry must contain exactly two values" }
        attributes[0] to attributes[1]
    }
    is List<*> -> {
        require(value.size == 2) { "ClickHouse Map entry must contain exactly two values" }
        value[0] to value[1]
    }
    is Array<*> -> {
        require(value.size == 2) { "ClickHouse Map entry must contain exactly two values" }
        value[0] to value[1]
    }
    else -> error("Unexpected ClickHouse Map entry: $value")
}

/** ClickHouse `Tuple(...)` 컬럼 타입. Kotlin에서는 고정 arity list로 표현합니다. */
class ClickHouseTupleColumnType(elements: List<ColumnType<*>>): ColumnType<List<Any?>>() {
    val elements: List<ColumnType<*>> = elements.toList()

    init {
        require(this.elements.isNotEmpty()) { "ClickHouse Tuple must contain at least one element" }
    }

    override fun sqlType(): String = "Tuple(${elements.joinToString(", ") { it.sqlType() }})"

    override fun valueFromDB(value: Any): List<Any?> {
        val values = when (value) {
            is com.clickhouse.data.Tuple -> value.values.toList()
            is Struct -> value.attributes.toList()
            else -> clickHouseElements(value)
        }
        require(values.size == elements.size) {
            "ClickHouse Tuple arity mismatch: expected ${elements.size}, got ${values.size}"
        }
        return clickHouseImmutableList(values.mapIndexed { index, raw ->
            clickHouseValueFromDB(elements[index], raw)
        })
    }

    override fun notNullValueToDB(value: List<Any?>): Any {
        require(value.size == elements.size) {
            "ClickHouse Tuple arity mismatch: expected ${elements.size}, got ${value.size}"
        }
        val encoded = value.mapIndexed { index, item -> clickHouseValueToDB(elements[index], item) }.toTypedArray()
        // JDBC V2 encodes Object[] as an Array literal. Use its Tuple marker
        // so mixed tuple fields are emitted as `(field_0, field_1)` instead.
        return com.clickhouse.data.Tuple(*encoded)
    }

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/** ClickHouse `Nested(...)` 컬럼 타입. 각 row는 선언된 arity를 가져야 합니다. */
class ClickHouseNestedColumnType(elements: List<ColumnType<*>>): ColumnType<List<List<Any?>>>() {
    val elements: List<ColumnType<*>> = elements.toList()

    init {
        require(this.elements.isNotEmpty()) { "ClickHouse Nested must contain at least one element" }
    }

    override fun sqlType(): String = "Nested(${elements.mapIndexed { index, element ->
        "field_$index ${element.sqlType()}"
    }.joinToString(", ")})"

    override fun valueFromDB(value: Any): List<List<Any?>> {
        val rows = when (value) {
            is Map<*, *> -> clickHouseNestedRowsFromColumns(value)
            is ResultSet -> clickHouseElements(value)
            else -> clickHouseElements(value)
        }
        return clickHouseImmutableList(rows.map { rawRow ->
            val row = when (rawRow) {
                is Struct -> rawRow.attributes.toList()
                else -> clickHouseElements(rawRow ?: error("ClickHouse Nested row cannot be null"))
            }
            require(row.size == elements.size) {
                "ClickHouse Nested row arity mismatch: expected ${elements.size}, got ${row.size}"
            }
            clickHouseImmutableList(row.mapIndexed { index, raw ->
                clickHouseValueFromDB(elements[index], raw)
            })
        })
    }

    override fun notNullValueToDB(value: List<List<Any?>>): Any = value.map { row ->
        require(row.size == elements.size) {
            "ClickHouse Nested row arity mismatch: expected ${elements.size}, got ${row.size}"
        }
        row.mapIndexed { index, item -> clickHouseValueToDB(elements[index], item) }.toTypedArray()
    }.toTypedArray()

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

private fun clickHouseNestedRowsFromColumns(columns: Map<*, *>): List<List<Any?>> {
    require(columns.isNotEmpty()) { "ClickHouse Nested requires at least one column" }
    val values = columns.values.map { column -> clickHouseElements(column as Any) }
    val rowCount = values.first().size
    require(values.all { it.size == rowCount }) { "ClickHouse Nested columns must have equal lengths" }
    return (0 until rowCount).map { rowIndex -> values.map { it[rowIndex] } }
}

/** `Map(K,V)` 컬럼을 등록합니다. */
fun <K: Any, V> Table.chMap(
    name: String,
    keyType: ColumnType<K>,
    valueType: ColumnType<V>,
): Column<Map<K, V>> = registerColumn(name, ClickHouseMapColumnType(keyType, valueType))

/** 고정 arity `Tuple(...)` 컬럼을 등록합니다. */
fun Table.chTuple(name: String, elements: List<ColumnType<*>>): Column<List<Any?>> =
    registerColumn(name, ClickHouseTupleColumnType(elements))

/** 고정 arity row를 갖는 `Nested(...)` 컬럼을 등록합니다. */
fun Table.chNested(name: String, elements: List<ColumnType<*>>): Column<List<List<Any?>>> =
    registerColumn(name, ClickHouseNestedColumnType(elements))
