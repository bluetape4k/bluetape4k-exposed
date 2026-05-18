package io.bluetape4k.exposed.core

import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IExpressionAlias
import org.jetbrains.exposed.v1.core.InternalApi
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.core.transactions.currentTransaction

/**
 * A [Table] facade that makes an Exposed SELECT query addressable as a Common Table Expression (CTE).
 *
 * ## Contract
 * - Renders the CTE body and final query through the same [QueryBuilder], preserving prepared-argument order.
 * - Maps the columns and aliases selected by [query] to fields on this temporary table.
 * - Renders `WITH RECURSIVE ... UNION [ALL] ...` when [recursiveQuery] is present.
 * - Does not support DDL statements because a CTE is scoped to a single SQL statement.
 *
 * ```kotlin
 * val activeUsers = CteTable(
 *     name = "active_users",
 *     query = Users.select(Users.id, Users.name).where { Users.active eq true }
 * )
 * val id = activeUsers[Users.id]
 * ```
 */
open class CteTable(
    name: String,
    val query: AbstractQuery<*>,
    val recursiveQuery: ((CteTable) -> AbstractQuery<*>)? = null,
    val unionAll: Boolean = true,
): Table(name) {

    private val fieldMap: Map<Expression<*>, Expression<*>> =
        query.set.fields.associateWith { field ->
            when (field) {
                is Column<*>           -> Column(this, field.name, field.columnType)
                is IExpressionAlias<*> -> field.aliasOnlyExpression()
                else                   -> field
            }
        }

    private val cteFieldNames: List<String> =
        query.set.fields.mapNotNull { field ->
            when (field) {
                is Column<*>           -> field.name
                is IExpressionAlias<*> -> field.alias
                else                   -> null
            }
        }

    val recursive: Boolean get() = recursiveQuery != null

    final override val fields: List<Expression<*>> get() = fieldMap.values.toList()

    final override val columns: List<Column<*>> get() = fields.filterIsInstance<Column<*>>()

    final override fun createStatement(): List<String> =
        throw UnsupportedOperationException("CREATE statements are not supported by CTEs")

    final override fun modifyStatement(): List<String> =
        throw UnsupportedOperationException("ALTER statements are not supported by CTEs")

    final override fun dropStatement(): List<String> =
        throw UnsupportedOperationException("DROP statements are not supported by CTEs")

    /**
     * Appends the `cte_name [(columns...)] AS (...)` fragment to [builder].
     */
    @OptIn(InternalApi::class)
    fun describeWith(builder: QueryBuilder) {
        val transaction = currentTransaction()

        builder {
            describe(transaction, builder)
            appendColumnNamesIfComplete(transaction)
            append(" AS (")
            query.prepareSQL(builder)
            recursiveQuery?.let { recursive ->
                append(if (unionAll) " UNION ALL " else " UNION ")
                recursive(this@CteTable).prepareSQL(builder)
            }
            append(")")
        }
    }

    /**
     * Returns the temporary CTE field corresponding to [queryField].
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(queryField: Expression<T>): Expression<T> =
        fieldMap[queryField] as? Expression<T>
            ?: error("$queryField is not in CTE query set")

    /**
     * Returns the temporary CTE column corresponding to [queryColumn].
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(queryColumn: Column<T>): Column<T> =
        fieldMap[queryColumn] as? Column<T>
            ?: error("$queryColumn is not in CTE query set")

    /**
     * Returns the temporary CTE field corresponding to [queryAlias].
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(queryAlias: IExpressionAlias<T>): Expression<T> =
        fieldMap[queryAlias as Expression<*>] as? Expression<T>
            ?: error("$queryAlias is not in CTE query set")

    private fun QueryBuilder.appendColumnNamesIfComplete(transaction: Transaction) {
        if (cteFieldNames.size != fields.size) return

        cteFieldNames.appendTo(prefix = " (", postfix = ")") { name ->
            append(transaction.db.identifierManager.quoteIdentifierWhenWrongCaseOrNecessary(name))
        }
    }
}
