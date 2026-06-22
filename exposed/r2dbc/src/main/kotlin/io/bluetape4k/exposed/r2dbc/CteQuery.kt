package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.exposed.core.CteTable
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.r2dbc.Query

/**
 * [Query] implementation that prepends a `WITH` CTE clause to an R2DBC SELECT query.
 *
 * ## Contract
 * - Renders CTE bodies and the final SELECT through the same [QueryBuilder], preserving parameter binding order.
 * - Preserves the source [Query] state such as where/order/limit/group/having/distinct via [Query.copyTo].
 * - Supports SELECT CTEs only. DML CTEs require a separate Statement implementation.
 *
 * ```kotlin
 * val recent = CteTable("recent_orders", Orders.selectAll().where { Orders.status eq "PAID" })
 * val query = recent.selectAll().withCte(recent)
 * ```
 */
class CteQuery(
    private val ctes: List<CteTable>,
    source: Query,
): Query(source.set, source.where) {

    init {
        source.copyTo(this)
    }

    override fun prepareSQL(builder: QueryBuilder): String {
        require(ctes.isNotEmpty()) { "At least one CTE table is required" }

        builder {
            append("WITH ")
            if (ctes.any { it.recursive }) {
                append("RECURSIVE ")
            }
            ctes.forEachIndexed { index, cte ->
                if (index > 0) append(", ")
                cte.describeWith(builder)
            }
            append(" ")
        }

        return super.prepareSQL(builder)
    }

    override fun copy(): CteQuery = CteQuery(ctes, this)
}

/**
 * Prepends one or more CTEs to this [Query].
 */
fun Query.withCtes(vararg ctes: CteTable): Query = withCtes(ctes.toList())

/**
 * Prepends one or more CTEs to this [Query].
 */
fun Query.withCtes(ctes: Iterable<CteTable>): Query = CteQuery(ctes.toList(), this)

/**
 * Prepends a single CTE to this [Query].
 */
fun Query.withCte(cte: CteTable): Query = withCtes(cte)
