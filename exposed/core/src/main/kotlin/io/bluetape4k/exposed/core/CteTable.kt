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
 * Exposed SELECT query를 Common Table Expression(CTE)으로 참조할 수 있게 하는 [Table] facade입니다.
 *
 * ## 계약
 * - CTE 본문과 최종 query를 같은 [QueryBuilder]로 렌더링하여 prepared argument 순서를 보존합니다.
 * - [query]가 선택한 column과 alias를 이 임시 table의 field로 매핑합니다.
 * - [recursiveQuery]가 있으면 `WITH RECURSIVE ... UNION [ALL] ...`을 렌더링합니다.
 * - CTE는 단일 SQL statement 범위에서만 유효하므로 DDL statement를 지원하지 않습니다.
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
     * `cte_name [(columns...)] AS (...)` 조각을 [builder]에 추가합니다.
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
     * [queryField]에 대응하는 임시 CTE field를 반환합니다.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(queryField: Expression<T>): Expression<T> =
        fieldMap[queryField] as? Expression<T>
            ?: error("$queryField is not in CTE query set")

    /**
     * [queryColumn]에 대응하는 임시 CTE column을 반환합니다.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(queryColumn: Column<T>): Column<T> =
        fieldMap[queryColumn] as? Column<T>
            ?: error("$queryColumn is not in CTE query set")

    /**
     * [queryAlias]에 대응하는 임시 CTE field를 반환합니다.
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
