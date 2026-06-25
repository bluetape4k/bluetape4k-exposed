package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.resolveColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.data.repository.query.RepositoryQuery
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/**
 * Executes raw SQL declared with [@Query][io.bluetape4k.spring.data.exposed.jdbc.annotation.Query]
 * inside the current R2DBC transaction boundary.
 *
 * If the caller already runs inside `suspendTransaction { }`, that active transaction is
 * reused so uncommitted caller data remains visible. Otherwise this query opens the same
 * transaction boundary used by PartTree and base R2DBC repository methods.
 *
 * Positional parameters (`?1`, `?2`, ...) are bound before execution, and entities
 * are reloaded from the id column returned by the SELECT query.
 *
 * **Two-query pattern constraint**: the raw SQL is used only to extract ids. Actual
 * entity loading is performed with `selectAll().where { id inList ids }`, so ORDER BY,
 * JOIN, GROUP BY, LIMIT, and similar semantics from the raw SQL are not preserved in
 * the final result. Use [PartTreeExposedR2dbcQuery] or direct Exposed DSL when sorting
 * or aggregation semantics must be preserved.
 */
internal class DeclaredExposedR2dbcQuery<R: Any, ID: Any>(
    private val queryMethod: ExposedR2dbcQueryMethod,
    private val mapper: R2dbcQueryMapper<R, ID>,
): RepositoryQuery {

    companion object: KLoggingChannel()

    private val positionalPlaceholderRegex = Regex("\\?(\\d+)")

    private val rawSql: String = queryMethod.getAnnotatedQuery()
        ?: error("@Query annotation is required for DeclaredExposedR2dbcQuery on method '${queryMethod.name}'")

    override fun getQueryMethod(): ExposedR2dbcQueryMethod = queryMethod

    override fun execute(parameters: Array<out Any?>): Any? =
        error("DeclaredExposedR2dbcQuery '${queryMethod.name}' must be invoked as a suspend method")

    suspend fun executeSuspending(parameters: Array<out Any?>): Any? {
        val values = parameters.withoutContinuation()
        val boundSql = bindParameters(rawSql, values)

        return TransactionManager.currentOrNull()?.let { tx ->
            executeInTransaction(tx, boundSql)
        } ?: suspendTransaction {
            executeInTransaction(this, boundSql)
        }
    }

    private suspend fun executeInTransaction(tx: R2dbcTransaction, boundSql: BoundSql): Any? {
        val idColumnName = mapper.table.id.name
        val rawIds = tx.exec(boundSql.sql, boundSql.args, StatementType.SELECT) { row ->
            // Fall back to ordinal 0 when name-based lookup fails for aliases or expressions.
            try {
                row.get(idColumnName, Any::class.java)
            } catch (_: Exception) {
                row.get(0, Any::class.java)
            }
        }?.toList().orEmpty()

        if (rawIds.isEmpty()) return emptyList<R>()

        @Suppress("UNCHECKED_CAST")
        val ids = rawIds.filterNotNull() as List<ID>
        val results = mutableListOf<R>()
        mapper.table.selectAll()
            .where { mapper.table.id inList ids }
            .collect { results.add(mapper.toDomain(it)) }
        return results
    }

    private data class BoundSql(
        val sql: String,
        val args: List<Pair<IColumnType<*>, Any?>>,
    )

    private fun bindParameters(sql: String, parameters: Array<out Any?>): BoundSql {
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
        val normalizedSql = positionalPlaceholderRegex.replace(sql) { match ->
            val idx = match.groupValues[1].toInt() - 1
            require(idx in parameters.indices) {
                "Query placeholder index out of bounds: ${match.value} (param count: ${parameters.size})"
            }
            // Duplicate repeated placeholders in args because positional binding needs
            // one independent argument for each question-mark slot.
            args += toSqlArg(parameters[idx])
            "?"
        }
        return BoundSql(normalizedSql, args)
    }

    @OptIn(InternalApi::class)
    private fun toSqlArg(value: Any?): Pair<IColumnType<*>, Any?> {
        if (value == null) return TextColumnType() to null
        val columnType = try {
            @Suppress("UNCHECKED_CAST")
            resolveColumnType(value::class as KClass<Any>, defaultType = TextColumnType())
        } catch (e: Exception) {
            log.warn(e) { "Cannot resolve column type for ${value::class.simpleName}, falling back to TextColumnType" }
            TextColumnType()
        }
        val normalized = if (columnType is TextColumnType && value !is String) value.toString() else value
        return columnType to normalized
    }

    private fun Array<out Any?>.withoutContinuation(): Array<Any?> =
        if (lastOrNull() is Continuation<*>) dropLast(1).toTypedArray()
        else toList().toTypedArray()
}
