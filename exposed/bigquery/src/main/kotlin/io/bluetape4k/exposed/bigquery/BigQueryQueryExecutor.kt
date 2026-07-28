package io.bluetape4k.exposed.bigquery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Exposed [Query]를 BigQuery REST API를 통해 실행합니다.
 *
 * 이 실행기는 [BigQueryContext.withBigQuery]로 생성합니다.
 *
 * ```kotlin
 * with(context) {
 *     // 동기 실행
 *     val rows = Events.selectAll().where { Events.region eq "kr" }.withBigQuery().toList()
 *
 *     // 비동기 실행(suspend)
 *     val rows = Events.selectAll().withBigQuery().toListSuspending()
 *
 *     // 스트리밍 실행(대용량 result set에 적합)
 *     Events.selectAll().withBigQuery().toFlow().collect { row -> ... }
 * }
 * ```
 */
class BigQueryQueryExecutor(
    private val query: Query,
    private val context: BigQueryContext,
    private val options: BigQueryQueryOptions = BigQueryQueryOptions(),
) {
    private fun sql(): String = transaction(context.sqlGenDb) { query.prepareSQL(this, prepared = false) }

    /** BigQuery page token을 따라가며 query를 실행하고 모든 result row를 list로 반환합니다. */
    fun toList(): List<BigQueryResultRow> = context.collectAllRows(sql(), options)

    /** [BigQueryContext.dispatcher]에서 query를 비동기로 실행하고 모든 row를 반환합니다. */
    suspend fun toListSuspending(): List<BigQueryResultRow> = withContext(context.dispatcher) { toList() }

    /** 전체 result set을 메모리에 올리지 않고 page 단위로 row를 방출하는 [Flow]를 반환합니다. */
    fun toFlow(): Flow<BigQueryResultRow> = context.collectRowsFlow(sql(), options)

    /** 생성된 SQL을 BigQuery dry run으로 검증합니다. */
    fun dryRun() = context.validateRawQuery(sql(), options)

    /** 생성된 SQL을 BigQuery dry run으로 비동기 검증합니다. */
    suspend fun dryRunSuspending() = withContext(context.dispatcher) { dryRun() }

    /** result row가 정확히 하나일 때 그 row를 반환하고, 0개이거나 2개 이상이면 예외를 던집니다. */
    fun single(): BigQueryResultRow = toList().single()

    /** result row가 하나이면 반환하고, 0개이면 `null`, 2개 이상이면 예외를 던집니다. */
    fun singleOrNull(): BigQueryResultRow? = toList().singleOrNull()

    /** 첫 번째 result row를 반환하고, result가 비어 있으면 `null`을 반환합니다. */
    fun firstOrNull(): BigQueryResultRow? = toList().firstOrNull()
}

/**
 * BigQuery REST API 응답에서 얻은 단일 row입니다.
 *
 * Exposed [Column] 참조를 사용해 값을 type-safe하게 읽을 수 있습니다.
 * 내부 map key는 lowercase로 정규화되므로 column 이름 조회는 대소문자를 구분하지 않습니다.
 *
 * ```kotlin
 * val row: BigQueryResultRow = ...
 * val region: String      = row[Events.region]
 * val userId: Long        = row[Events.userId]
 * val amount: BigDecimal? = row[Events.amount]
 * ```
 */
class BigQueryResultRow(private val data: Map<String, Any?>) {
    private val normalizedData: Map<String, Any?> = data.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) }

    /** 지정한 Exposed [Column]의 값을 [T] 타입으로 변환해 반환합니다. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(column: Column<T>): T =
        convertValue(normalizedData[column.name.lowercase(Locale.ROOT)], column) as T

    /** 지정한 column 이름의 raw value를 반환합니다. */
    operator fun get(name: String): Any? = normalizedData[name.lowercase(Locale.ROOT)]

    @Suppress("UNCHECKED_CAST")
    private fun <T> convertValue(raw: Any?, column: Column<T>): T? {
        if (raw == null || raw.javaClass == Any::class.java) return null
        val s = raw.toString()
        if (s.equals("null", ignoreCase = true)) return null
        return try {
            when (column.columnType) {
                is DecimalColumnType ->
                    BigDecimal(s)
                is JavaInstantColumnType ->
                    // BigQuery REST API: TIMESTAMP = 초 단위 float 문자열 (예: "1.704067200E9")
                    Instant.ofEpochMilli((s.toDouble() * 1000).toLong())
                else                 ->
                    column.columnType.valueFromDB(s)
            } as T?
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to convert BigQuery value '$s' for column '${column.name}' " +
                    "(type: ${column.columnType::class.simpleName})",
                e
            )
        }
    }

    override fun toString(): String = normalizedData.toString()
}
