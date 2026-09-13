package io.bluetape4k.exposed.clickhouse.types

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.jetbrains.exposed.v1.core.Table

/**
 * ClickHouse `Array(T)` 컬럼 타입.
 *
 * Kotlin [List]<T> 와 매핑됩니다.
 *
 * ## 주의
 * - 기본 타입은 non-null element를 사용하며, nullable element가 필요하면
 *   [ClickHouseArrayNullableElementsColumnType] 또는 [chArrayNullableElements]를 사용합니다.
 * - JDBC가 [java.sql.Array], [List], 또는 native Java 배열로 반환할 수 있어 모두 방어적으로 처리합니다.
 *
 * @property inner 원소 컬럼 타입
 */
@Suppress("UNCHECKED_CAST")
class ClickHouseArrayColumnType<T: Any>(val inner: ColumnType<T>): ColumnType<List<T>>() {
    override fun sqlType(): String = "Array(${inner.sqlType()})"

    override fun valueFromDB(value: Any): List<T> = clickHouseImmutableList(
        clickHouseArrayElements(value).map { elem ->
            require(elem != null) {
                "Array element is null — use Array(Nullable(T)) for nullable elements"
            }
            inner.valueFromDB(elem) as T
        },
    )

    override fun notNullValueToDB(value: List<T>): Any =
        value.map { inner.notNullValueToDB(it) }.toTypedArray()

    override fun readObject(rs: RowApi, index: Int): Any? =
        super.readObject(rs, index)?.let(::valueFromDB)

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        clickHouseSetParameter(stmt, index, value, this)
    }
}

/**
 * ClickHouse `Array(T)` 컬럼을 등록합니다. [List]&lt;T&gt; 와 매핑됩니다.
 *
 * ```kotlin
 * object EventTable : Table("events") {
 *     val tags = chArray("tags", ClickHouseStringColumnType())
 *     val scores = chArray("scores", ClickHouseFloat32ColumnType())
 * }
 * ```
 *
 * @param name 컬럼명
 * @param innerType 배열 원소의 컬럼 타입
 */
fun <T: Any> Table.chArray(name: String, innerType: ColumnType<T>): Column<List<T>> =
    registerColumn(name, ClickHouseArrayColumnType(innerType))

/**
 * ClickHouse `Array(Nullable(T))` 컬럼을 등록합니다.
 *
 * 배열 컨테이너 자체는 필수이고 원소만 nullable인 `Column<List<T?>>`를 반환합니다.
 */
fun <T: Any> Table.chArrayNullableElements(name: String, innerType: ColumnType<T>): Column<List<T?>> =
    registerColumn(name, ClickHouseArrayNullableElementsColumnType(innerType))

/**
 * ClickHouse `Nullable(Array(T))` 컬럼을 등록합니다.
 *
 * 배열 컨테이너만 nullable로 표현하는 `Column<List<T>?>`를 반환합니다.
 * 원소까지 nullable한 배열은 [chArrayNullableElements]를 사용하세요.
 */
@Suppress("UNCHECKED_CAST")
fun <T: Any> Table.chNullableArray(name: String, innerType: ColumnType<T>): Column<List<T>?> =
    registerColumn<List<T>>(name, ClickHouseNullableArrayColumnType(innerType)) as Column<List<T>?>
