package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.data.exposed.jdbc.repository.query.ExposedQueryCreator
import io.bluetape4k.spring.data.exposed.jdbc.repository.query.ParameterMetadataProvider
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.toExposedOrderBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.RepositoryQuery
import org.springframework.data.repository.query.parser.PartTree

/**
 * 메서드명 기반 PartTree 쿼리를 Exposed R2DBC DSL로 실행합니다.
 */
internal class PartTreeExposedR2dbcQuery<R: Any, ID: Any>(
    private val queryMethod: ExposedR2dbcQueryMethod,
    private val mapper: R2dbcQueryMapper<R, ID>,
): RepositoryQuery {

    companion object: KLoggingChannel()

    private val table: IdTable<ID> = mapper.table
    private val partTree: PartTree = PartTree(queryMethod.name, queryMethod.entityInformation.javaType)

    override fun getQueryMethod(): ExposedR2dbcQueryMethod = queryMethod

    override fun execute(parameters: Array<out Any>): Any? {
        val values = parameters.withoutContinuation()
        return if (isFlowQuery()) {
            executeFlow(values)
        } else {
            error("Exposed R2DBC PartTree query '${queryMethod.name}' must be invoked as a suspend method")
        }
    }

    suspend fun executeSuspending(parameters: Array<out Any?>): Any? {
        val values = parameters.withoutContinuation()
        val pageable = values.firstInstanceOrNull<Pageable>() ?: Pageable.unpaged()
        val sort = partTree.sort
            .and(pageable.sort)
            .and(values.firstInstanceOrNull<Sort>() ?: Sort.unsorted())
        val bindValues = parameters.toList().toTypedArray()

        return suspendTransaction {
            val op = createOp(bindValues)
            when {
                partTree.isDelete           -> table.deleteWhere { op }.toLong()
                partTree.isCountProjection  -> query(op).count()
                partTree.isExistsProjection -> !query(op).empty()
                partTree.isLimiting         -> executeLimiting(op, partTree.maxResults, sort)
                isPageQuery()               -> executePageQuery(op, pageable, sort)
                isSliceQuery()              -> executeSliceQuery(op, pageable, sort)
                isSingleResult()            -> select(op, sort, limit = 1).firstOrNull()
                else                        -> select(op, sort)
            }
        }
    }

    private fun executeFlow(values: Array<Any?>): Flow<R> = flow {
        val rows = suspendTransaction {
            val op = createOp(values)
            val sort = partTree.sort
                .and(values.firstInstanceOrNull<Sort>() ?: Sort.unsorted())
            select(op, sort)
        }
        rows.forEach { emit(it) }
    }

    private fun createOp(values: Array<Any?>): Op<Boolean> {
        val provider = ParameterMetadataProvider.of(queryMethod.parameters, values)
        return ExposedQueryCreator(partTree, provider.accessor, table).createQuery()
    }

    private suspend fun executeLimiting(op: Op<Boolean>, maxResults: Int?, sort: Sort): Any? {
        val rows = select(op, sort, limit = maxResults)
        return if (isSingleResult()) rows.firstOrNull() else rows
    }

    private suspend fun executePageQuery(op: Op<Boolean>, pageable: Pageable, sort: Sort): Page<R> {
        val total = query(op).count()
        val content =
            if (pageable.isUnpaged) {
                select(op, sort)
            } else {
                select(op, sort, limit = pageable.pageSize, offset = pageable.offset)
            }
        return PageImpl(content, pageable, total)
    }

    private suspend fun executeSliceQuery(op: Op<Boolean>, pageable: Pageable, sort: Sort): Slice<R> {
        val fetchSize = pageable.pageSize + 1
        val content = select(op, sort, limit = fetchSize, offset = pageable.offset)
        val hasNext = content.size > pageable.pageSize
        return SliceImpl(if (hasNext) content.dropLast(1) else content, pageable, hasNext)
    }

    private fun query(op: Op<Boolean>): Query =
        table.selectAll().where { op }

    private suspend fun select(
        op: Op<Boolean>,
        sort: Sort,
        limit: Int? = null,
        offset: Long? = null,
    ): List<R> {
        val rows = mutableListOf<ResultRow>()
        val query = query(op)
        if (sort.isSorted) {
            query.orderBy(*sort.toExposedOrderBy(table))
        }
        if (limit != null) {
            query.limit(limit)
        }
        if (offset != null && offset > 0) {
            query.offset(offset)
        }
        query.collect { rows.add(it) }
        return rows.map(mapper.toDomain)
    }

    private fun isPageQuery(): Boolean =
        queryMethod.kotlinReturnClassifier == Page::class

    private fun isSliceQuery(): Boolean =
        queryMethod.kotlinReturnClassifier == Slice::class

    private fun isFlowQuery(): Boolean =
        queryMethod.kotlinReturnClassifier == Flow::class

    private fun isSingleResult(): Boolean {
        val classifier = queryMethod.kotlinReturnClassifier
        return classifier != List::class &&
                classifier != Collection::class &&
                classifier != Set::class &&
                classifier != Page::class &&
                classifier != Slice::class &&
                classifier != Flow::class
    }

    private fun Array<out Any?>.withoutContinuation(): Array<Any?> =
        if (lastOrNull() is kotlin.coroutines.Continuation<*>) {
            dropLast(1).toTypedArray()
        } else {
            toList().toTypedArray()
        }

    private inline fun <reified T: Any> Array<out Any?>.firstInstanceOrNull(): T? =
        firstOrNull { it is T } as? T
}
