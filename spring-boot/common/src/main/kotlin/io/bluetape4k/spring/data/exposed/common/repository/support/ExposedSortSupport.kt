package io.bluetape4k.spring.data.exposed.common.repository.support

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.warn
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.springframework.data.domain.Sort

private val log = KotlinLogging.logger {}
private val camelCaseBoundary = Regex("([a-z])([A-Z])")
private val snakeCaseBoundary = Regex("_([a-zA-Z])")

/** Spring Data [Sort]를 Exposed [SortOrder] 쌍 배열로 변환합니다. */
fun Sort.toExposedOrderBy(table: Table): Array<Pair<Expression<*>, SortOrder>> {
    val result = mutableListOf<Pair<Expression<*>, SortOrder>>()
    for (order in this) {
        val column: Column<*> = table.columns.firstOrNull { candidate ->
            candidate.name.equals(order.property, ignoreCase = true) ||
                candidate.name.equals(toSnakeCase(order.property), ignoreCase = true)
        } ?: run {
            log.warn { "Sort property '${order.property}' not found in table '${table.tableName}', skipped." }
            continue
        }

        val sortOrder = if (order.isAscending) SortOrder.ASC else SortOrder.DESC
        result.add(column to sortOrder)
    }
    return result.toTypedArray()
}

/** camelCase 프로퍼티 이름을 snake_case 컬럼 이름으로 변환합니다. */
internal fun toSnakeCase(camelCase: String): String =
    camelCase.replace(camelCaseBoundary, "$1_$2").lowercase()

/** snake_case 컬럼 이름을 camelCase 프로퍼티 이름으로 변환합니다. */
internal fun toCamelCase(snakeCase: String): String =
    snakeCase.replace(snakeCaseBoundary) { match -> match.groupValues[1].uppercase() }
