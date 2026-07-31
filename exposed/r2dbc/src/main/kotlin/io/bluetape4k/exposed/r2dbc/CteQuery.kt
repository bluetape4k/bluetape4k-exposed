package io.bluetape4k.exposed.r2dbc

import io.bluetape4k.exposed.core.CteTable
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.r2dbc.Query

/**
 * R2DBC SELECT 쿼리 앞에 `WITH` CTE 절을 추가하는 [Query] 구현체입니다.
 *
 * ## 계약
 * - CTE 본문과 최종 SELECT를 같은 [QueryBuilder]로 렌더링하여 매개변수 바인딩 순서를 보존합니다.
 * - [Query.copyTo]를 통해 원본 [Query]의 where/order/limit/group/having/distinct 상태를 유지합니다.
 * - SELECT CTE만 지원합니다. DML CTE에는 별도의 Statement 구현이 필요합니다.
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
 * 이 [Query] 앞에 하나 이상의 CTE를 추가합니다.
 */
fun Query.withCtes(vararg ctes: CteTable): Query = withCtes(ctes.toList())

/**
 * 이 [Query] 앞에 하나 이상의 CTE를 추가합니다.
 */
fun Query.withCtes(ctes: Iterable<CteTable>): Query = CteQuery(ctes.toList(), this)

/**
 * 이 [Query] 앞에 단일 CTE를 추가합니다.
 */
fun Query.withCte(cte: CteTable): Query = withCtes(cte)
