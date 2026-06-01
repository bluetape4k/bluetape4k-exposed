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
 * Executes an Exposed [Query] against the BigQuery REST API.
 *
 * Created via [BigQueryContext.withBigQuery].
 *
 * ```kotlin
 * with(context) {
 *     // Synchronous
 *     val rows = Events.selectAll().where { Events.region eq "kr" }.withBigQuery().toList()
 *
 *     // Asynchronous (suspend)
 *     val rows = Events.selectAll().withBigQuery().toListSuspending()
 *
 *     // Streaming (suitable for large result sets)
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

    /** Executes the query and returns all result rows (following BigQuery page tokens) as a list. */
    fun toList(): List<BigQueryResultRow> = context.collectAllRows(sql(), options)

    /** Executes the query asynchronously on [BigQueryContext.dispatcher] and returns all rows. */
    suspend fun toListSuspending(): List<BigQueryResultRow> = withContext(context.dispatcher) { toList() }

    /** Returns a [Flow] that emits rows page by page without loading the entire result set into memory. */
    fun toFlow(): Flow<BigQueryResultRow> = context.collectRowsFlow(sql(), options)

    /** Validates the generated SQL with a BigQuery dry run. */
    fun dryRun() = context.validateRawQuery(sql(), options)

    /** Asynchronously validates the generated SQL with a BigQuery dry run. */
    suspend fun dryRunSuspending() = withContext(context.dispatcher) { dryRun() }

    /** Returns the single result row; throws if there are zero or more than one rows. */
    fun single(): BigQueryResultRow = toList().single()

    /** Returns the single result row, or null if there are zero rows; throws if there are more than one. */
    fun singleOrNull(): BigQueryResultRow? = toList().singleOrNull()

    /** Returns the first result row, or null if the result is empty. */
    fun firstOrNull(): BigQueryResultRow? = toList().firstOrNull()
}

/**
 * A single row from a BigQuery REST API response.
 *
 * Values are read in a type-safe way using Exposed [Column] references.
 * Internal map keys are normalized to lowercase, so column name lookups are case-insensitive.
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

    /** Returns the value for the given Exposed [Column], converted to type [T]. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(column: Column<T>): T =
        convertValue(normalizedData[column.name.lowercase(Locale.ROOT)], column) as T

    /** Returns the raw value for the given column name. */
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
