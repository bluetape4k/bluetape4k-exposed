package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import org.springframework.data.domain.Sort
import org.springframework.data.projection.ProjectionFactory
import kotlin.reflect.KClass

/** QBE snapshot을 Exposed R2DBC terminal로 실행하는 단일 coroutine executor입니다. */
@Suppress("TooManyFunctions")
internal class R2dbcFluentQueryExecutor<R: Any> internal constructor(
    private val expectedDomainType: KClass<R>,
    private val table: IdTable<Any>,
    toDomainMapper: (ResultRow) -> R,
    projectionFactory: ProjectionFactory,
    private val database: R2dbcDatabase? = null,
    private val constructionMode: R2dbcQbeConstructionMode,
) {

    private val resolver = R2dbcPersistentPropertyResolver(expectedDomainType, table)
    private val compiler = R2dbcExamplePredicateCompiler(resolver)
    private val mapper = R2dbcProjectionMapper(
        domainType = expectedDomainType,
        toDomainMapper = toDomainMapper,
        propertyResolver = resolver,
        projectionFactory = projectionFactory,
    )

    init {
        require(
            constructionMode == R2dbcQbeConstructionMode.DIRECT ||
                constructionMode == R2dbcQbeConstructionMode.FACTORY,
        )
    }

    suspend fun findOne(example: Example<R>): R? =
        one(buildPlan(example), R2dbcQbeOperation.FIND_ONE)

    fun findAll(example: Example<R>): Flow<R> =
        all(buildPlan(example), R2dbcQbeOperation.FIND_ALL)

    fun findAll(example: Example<R>, sort: Sort): Flow<R> {
        validateSort(sort)
        return all(buildPlan(example).copy(sort = sort), R2dbcQbeOperation.FIND_ALL)
    }

    suspend fun count(example: Example<R>): Long =
        count(buildPlan(example), R2dbcQbeOperation.COUNT)

    suspend fun exists(example: Example<R>): Boolean =
        exists(buildPlan(example), R2dbcQbeOperation.EXISTS)

    suspend fun <Q> findBy(
        example: Example<R>,
        queryFunction: suspend (io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<R>) -> Q,
    ): Q {
        val scope = R2dbcFluentQueryScope()
        val query = ExposedCoroutineFluentQueryImpl(this, buildPlan(example), scope)
        return try {
            queryFunction(query)
        } finally {
            scope.close()
        }
    }

    internal fun <T: Any> all(plan: R2dbcFluentQueryPlan, operation: R2dbcQbeOperation): Flow<T> = flow {
        executeTransaction(streaming = true, operation = operation) {
            val query = query(plan)
            query.collect { row ->
                @Suppress("UNCHECKED_CAST")
                emit(mapper.map(row, plan.resultType as KClass<T>, plan.projectedProperties))
            }
        }
    }

    internal suspend fun <T: Any> one(
        plan: R2dbcFluentQueryPlan,
        operation: R2dbcQbeOperation,
    ): T? {
        var result: T? = null
        var count = 0
        executeTransaction(streaming = false, operation = operation) {
            query(plan, limitOverride = 2).collect { row ->
                count++
                if (count <= 2) {
                    @Suppress("UNCHECKED_CAST")
                    result = mapper.map(row, plan.resultType as KClass<T>, plan.projectedProperties)
                }
            }
        }
        if (count > 1) throw IncorrectResultSizeDataAccessException(1, count)
        return result
    }

    internal suspend fun <T: Any> first(plan: R2dbcFluentQueryPlan): T? {
        var result: T? = null
        executeTransaction(streaming = false, operation = R2dbcQbeOperation.FLUENT_FIRST) {
            query(plan, limitOverride = 1).collect { row ->
                if (result == null) {
                    @Suppress("UNCHECKED_CAST")
                    result = mapper.map(row, plan.resultType as KClass<T>, plan.projectedProperties)
                }
            }
        }
        return result
    }

    internal suspend fun page(plan: R2dbcFluentQueryPlan, pageable: Pageable): Page<Any> {
        val pagePlan = if (pageable.sort.isSorted) {
            validateSort(pageable.sort)
            plan.copy(sort = pageable.sort)
        } else plan
        val content = mutableListOf<Any>()
        var total = 0L
        executeTransaction(streaming = false, operation = R2dbcQbeOperation.FLUENT_PAGE) {
            val query = if (pageable.isUnpaged) {
                query(pagePlan)
            } else {
                query(pagePlan, limitOverride = pageable.pageSize, offset = pageable.offset)
            }
            query.collect { row ->
                @Suppress("UNCHECKED_CAST")
                content += mapper.map(row, pagePlan.resultType as KClass<Any>, pagePlan.projectedProperties)
            }
            total = when {
                pageable.isUnpaged && pagePlan.limit == null -> content.size.toLong()
                !pageable.isUnpaged &&
                    content.size < pageable.pageSize &&
                    pagePlan.limit == null -> pageable.offset + content.size
                else -> countInCurrentTransaction(pagePlan)
            }
        }
        return PageImpl(content, pageable, total)
    }

    internal suspend fun slice(plan: R2dbcFluentQueryPlan, pageable: Pageable): Slice<Any> {
        val slicePlan = if (pageable.sort.isSorted) {
            validateSort(pageable.sort)
            plan.copy(sort = pageable.sort)
        } else plan
        val content = mutableListOf<Any>()
        var hasNext = false
        executeTransaction(streaming = false, operation = R2dbcQbeOperation.FLUENT_SLICE) {
            val query = if (pageable.isUnpaged) {
                query(slicePlan)
            } else {
                query(slicePlan, limitOverride = pageable.pageSize + 1, offset = pageable.offset)
            }
            query.collect { row ->
                if (pageable.isUnpaged || content.size < pageable.pageSize) {
                    @Suppress("UNCHECKED_CAST")
                    content += mapper.map(row, slicePlan.resultType as KClass<Any>, slicePlan.projectedProperties)
                } else {
                    hasNext = true
                }
            }
        }
        return SliceImpl(content, pageable, hasNext)
    }

    internal suspend fun count(plan: R2dbcFluentQueryPlan, operation: R2dbcQbeOperation): Long {
        var result = 0L
        executeTransaction(streaming = false, operation = operation) {
            result = countInCurrentTransaction(plan)
        }
        return result
    }

    internal suspend fun exists(plan: R2dbcFluentQueryPlan, operation: R2dbcQbeOperation): Boolean {
        var result = false
        executeTransaction(streaming = false, operation = operation) {
            val predicate = compiler.compile(plan.snapshot)
            result = !table.select(table.id).where { predicate }.limit(1).empty()
        }
        return result
    }

    internal fun buildPlan(example: Example<R>): R2dbcFluentQueryPlan =
        R2dbcFluentQueryPlan(resolver.snapshot(example), expectedDomainType)

    internal fun validateSort(sort: Sort) {
        sort.forEach { order ->
            if (order.isIgnoreCase || order.nullHandling != Sort.NullHandling.NATIVE) {
                throw InvalidDataAccessApiUsageException("QBE sort ignore-case/null handling is not supported")
            }
            resolver.resolve(order.property)
        }
    }

    internal fun validateProjection(resultType: KClass<*>, properties: List<String>) {
        if (resultType == expectedDomainType) {
            throw UnsupportedOperationException("Partial domain projection is not supported")
        }
        resolver.resolveAll(properties)
        val required = mapper.requiredProperties(resultType)
        if (properties.size != required.size || properties.toSet() != required.toSet()) {
            throw InvalidDataAccessApiUsageException(
                "Fluent projection properties must exactly match required source properties",
            )
        }
    }

    private suspend fun countInCurrentTransaction(plan: R2dbcFluentQueryPlan): Long {
        val predicate = compiler.compile(plan.snapshot)
        return table.selectAll().where { predicate }.count()
    }

    private fun query(
        plan: R2dbcFluentQueryPlan,
        limitOverride: Int? = null,
        offset: Long = 0,
    ): Query {
        val predicate = compiler.compile(plan.snapshot)
        val selected = if (plan.resultType == expectedDomainType && plan.projectedProperties.isNullOrEmpty()) {
            null
        } else {
            val properties = plan.projectedProperties ?: mapper.requiredProperties(plan.resultType)
            resolver.resolveAll(properties).map { it.column }
        }
        var result = if (selected == null) table.selectAll() else table.select(selected)
        result = result.where { predicate }
        if (plan.sort.isSorted) {
            val pairs = buildList<Pair<Expression<*>, SortOrder>> {
                plan.sort.forEach { order ->
                    val sortOrder = if (order.isAscending) SortOrder.ASC else SortOrder.DESC
                    add(resolver.resolve(order.property).column to sortOrder)
                }
            }.toTypedArray()
            result.orderBy(*pairs)
        }
        val limit = limitOverride ?: plan.limit
        if (limit != null && limit > 0) result.limit(limit)
        if (offset > 0) result.offset(offset)
        return result
    }

    private suspend fun <T> executeTransaction(
        streaming: Boolean,
        operation: R2dbcQbeOperation,
        block: suspend R2dbcTransaction.() -> T,
    ): T {
        R2dbcDiagnosticSanitizer.validateOperationLabel(operation.label)
        val current = TransactionManager.currentOrNull()
        if (current != null) {
            rejectNestedTransaction(current)
            return R2dbcTransactionLeaseRegistry.leaseFor(current).withLease { block(current) }
        }

        suspend fun executeTopLevel(transaction: R2dbcTransaction): T {
            if (streaming) transaction.maxAttempts = 1
            return R2dbcTransactionLeaseRegistry.leaseFor(transaction).withLease { block(transaction) }
        }

        return if (database == null) {
            suspendTransaction { executeTopLevel(this) }
        } else {
            suspendTransaction(database) { executeTopLevel(this) }
        }
    }

    private fun rejectNestedTransaction(transaction: R2dbcTransaction) {
        if (transaction.db.config.useNestedTransactions) {
            throw InvalidDataAccessApiUsageException(
                "R2DBC QBE does not support useNestedTransactions=true in an active transaction",
            )
        }
    }
}

internal enum class R2dbcQbeConstructionMode { FACTORY, DIRECT }

internal class R2dbcFluentQueryScope {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)

    fun validate() {
        if (!active.get()) {
            throw InvalidDataAccessApiUsageException(
                "FluentQuery callback scope is closed; use the terminal operation inside findBy",
            )
        }
    }

    fun close() {
        active.set(false)
    }
}

internal class ExposedCoroutineFluentQueryImpl<R: Any> internal constructor(
    private val executor: R2dbcFluentQueryExecutor<R>,
    private val plan: R2dbcFluentQueryPlan,
    private val scope: R2dbcFluentQueryScope,
): io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<R> {

    override fun sortBy(sort: Sort): io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<R> {
        scope.validate()
        executor.validateSort(sort)
        return ExposedCoroutineFluentQueryImpl(executor, plan.sortBy(sort), scope)
    }

    override fun limit(limit: Int): io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<R> {
        scope.validate()
        return ExposedCoroutineFluentQueryImpl(executor, plan.limit(limit), scope)
    }

    override fun <T: Any> asType(
        resultType: KClass<T>,
    ): io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<T> {
        scope.validate()
        @Suppress("UNCHECKED_CAST")
        return ExposedCoroutineFluentQueryImpl(executor, plan.asType(resultType), scope) as
            io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<T>
    }

    override fun project(
        vararg properties: String,
    ): io.bluetape4k.spring.data.exposed.r2dbc.repository.ExposedCoroutineFluentQuery<R> {
        scope.validate()
        if (properties.isNotEmpty()) {
            executor.validateProjection(plan.resultType, properties.toList())
        }
        return ExposedCoroutineFluentQueryImpl(executor, plan.project(*properties), scope)
    }

    override suspend fun one(): R? {
        scope.validate()
        return executor.one(plan, R2dbcQbeOperation.FLUENT_ONE)
    }

    override suspend fun first(): R? {
        scope.validate()
        return executor.first(plan)
    }

    override fun all(): Flow<R> {
        scope.validate()
        return executor.all(plan, R2dbcQbeOperation.FLUENT_ALL)
    }

    override suspend fun page(pageable: Pageable): Page<R> {
        scope.validate()
        @Suppress("UNCHECKED_CAST")
        return executor.page(plan, pageable) as Page<R>
    }

    override suspend fun slice(pageable: Pageable): Slice<R> {
        scope.validate()
        @Suppress("UNCHECKED_CAST")
        return executor.slice(plan, pageable) as Slice<R>
    }

    override suspend fun count(): Long {
        scope.validate()
        return executor.count(plan, R2dbcQbeOperation.COUNT)
    }

    override suspend fun exists(): Boolean {
        scope.validate()
        return executor.exists(plan, R2dbcQbeOperation.EXISTS)
    }
}
