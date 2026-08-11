package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.exposed.core.ExposedCursorPage
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.selectAll

private const val MAX_CURSOR_PAGE_SIZE = 10_000

/**
 * 기본 키 커서를 기준으로 다음 R2DBC 페이지를 조회합니다.
 *
 * 커서는 [table]의 raw primary-key 값이며, 호출자는 동일한 정렬과 predicate를
 * 다음 요청에도 재사용해야 합니다. 커서 토큰의 직렬화·서명·범위 검증은 호출자 책임입니다.
 * 삭제 행을 자동으로 제외하지 않으므로 soft-delete 저장소는 predicate에 활성 조건을
 * 명시해야 합니다.
 */
suspend fun <ID : Comparable<ID>, E : Any> R2dbcRepository<ID, E>.findCursorPage(
    pageSize: Int,
    cursor: ID? = null,
    sortOrder: SortOrder = SortOrder.ASC,
    predicate: () -> Op<Boolean> = { Op.TRUE },
): ExposedCursorPage<E, ID> {
    require(pageSize in 1..MAX_CURSOR_PAGE_SIZE) {
        "pageSize must be between 1 and $MAX_CURSOR_PAGE_SIZE"
    }

    val basePredicate = predicate()
    val wherePredicate = cursor?.let { basePredicate and table.cursorBoundary(it, sortOrder) } ?: basePredicate
    val rows = table
        .selectAll()
        .where(wherePredicate)
        .orderBy(table.id to sortOrder)
        .limit(pageSize + 1)
        .toList()

    val hasNext = rows.size > pageSize
    val pageRows = if (hasNext) rows.take(pageSize) else rows
    val nextCursor = pageRows.lastOrNull()?.let { row ->
        if (hasNext) row[table.id].value else null
    }

    val content = mutableListOf<E>()
    for (row in pageRows) {
        content += row.toEntity()
    }

    return ExposedCursorPage(
        content = content,
        nextCursor = nextCursor,
        hasNext = hasNext,
    )
}

private fun <ID : Comparable<ID>> IdTable<ID>.cursorBoundary(
    cursor: ID,
    sortOrder: SortOrder,
): Op<Boolean> {
    @Suppress("UNCHECKED_CAST")
    val idColumn = (id.columnType as EntityIDColumnType<ID>).idColumn
    return if (sortOrder.isAscending()) {
        idColumn greater cursor
    } else {
        idColumn less cursor
    }
}

private fun SortOrder.isAscending(): Boolean = when (this) {
    SortOrder.ASC,
    SortOrder.ASC_NULLS_FIRST,
    SortOrder.ASC_NULLS_LAST,
    -> true

    SortOrder.DESC,
    SortOrder.DESC_NULLS_FIRST,
    SortOrder.DESC_NULLS_LAST,
    -> false
}
