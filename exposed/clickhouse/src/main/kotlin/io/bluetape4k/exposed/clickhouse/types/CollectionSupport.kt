package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import java.sql.ResultSet
import java.util.Collections

private const val JDBC_FIRST_COLUMN = 1
private const val JDBC_ARRAY_VALUE_COLUMN = 2

/** Executes a conversion and releases the JDBC-owned resource afterwards. */
internal inline fun <T> withClickHouseCleanup(cleanup: () -> Unit, block: () -> T): T {
    val result = runCatching(block)
    val cleanupFailure = runCatching(cleanup).exceptionOrNull()
    if (cleanupFailure != null) {
        result.exceptionOrNull()?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
    }
    return result.getOrThrow()
}

/**
 * JDBC가 반환하는 배열/결과 집합을 안전하게 읽고 즉시 해제합니다.
 *
 * ClickHouse JDBC V2는 동일한 컬럼을 driver 버전에 따라 [List], Java 배열,
 * `java.sql.Array`, 또는 중첩 [ResultSet]으로 반환할 수 있습니다. 반환된
 * 컬렉션은 항상 새 인스턴스로 복사하며, JDBC 리소스는 변환 직후 닫습니다.
 */
internal fun clickHouseElements(value: Any): List<Any?> = when (value) {
    is java.sql.Array -> withClickHouseCleanup({ value.free() }) { clickHouseArrayElements(value.array) }
    is ResultSet -> clickHouseResultSetElements(value, valueColumn = null)
    is List<*> -> value.toList()
    is Array<*> -> value.toList()
    is String -> error("ClickHouse collection value returned as String literal — unsupported")
    else -> error("Unexpected ClickHouse collection value: $value (${value::class.simpleName})")
}

/** JDBC [java.sql.Array.getResultSet] exposes a one-based index in column 1 and
 * the actual element in column 2. */
internal fun clickHouseArrayElements(value: Any): List<Any?> =
    if (value is ResultSet) {
        clickHouseResultSetElements(value, valueColumn = JDBC_ARRAY_VALUE_COLUMN)
    } else {
        clickHouseElements(value)
    }

private fun clickHouseResultSetElements(value: ResultSet, valueColumn: Int?): List<Any?> =
    withClickHouseCleanup({ value.close() }) {
        val columnCount = value.metaData.columnCount
        if (valueColumn != null) {
            require(columnCount >= valueColumn) {
                "JDBC Array ResultSet must expose an element column at index $valueColumn"
            }
        }
        buildList {
            while (value.next()) {
                add(if (valueColumn == null) {
                    (JDBC_FIRST_COLUMN..columnCount).map(value::getObject)
                } else {
                    value.getObject(valueColumn)
                })
            }
        }
    }

internal fun <T> clickHouseImmutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

@Suppress("UNCHECKED_CAST")
internal fun clickHouseValueFromDB(type: ColumnType<*>, value: Any?): Any? =
    if (value == null) {
        require(type.nullable) { "Null value is not allowed for ${type.sqlType()}" }
        null
    } else {
        (type as ColumnType<Any?>).valueFromDB(value)
    }

@Suppress("UNCHECKED_CAST")
internal fun clickHouseValueToDB(type: ColumnType<*>, value: Any?): Any? =
    if (value == null) {
        require(type.nullable) { "Null value is not allowed for ${type.sqlType()}" }
        null
    } else {
        (type as ColumnType<Any>).notNullValueToDB(value)
    }

internal fun clickHouseSetParameter(
    stmt: PreparedStatementApi,
    index: Int,
    value: Any?,
    columnType: ColumnType<*>,
) {
    if (value == null) {
        stmt.setNull(index, columnType)
    } else {
        stmt.set(index, value, columnType)
    }
}
