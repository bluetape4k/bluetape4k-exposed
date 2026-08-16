package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.spring.data.exposed.jdbc.mapping.ExposedPersistentEntity
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.support.PageableExecutionUtils
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.NoTransactionException
import java.util.Optional
import java.util.stream.Stream

@Suppress("TooManyFunctions")
internal class JdbcFluentQueryExecutor<E: Entity<ID>, ID: Any>(
    private val entityClass: EntityClass<ID, E>,
    private val table: IdTable<ID>,
    private val creationMode: JdbcRepositoryCreationMode,
) {

    fun <R: Any> first(plan: JdbcFluentQueryPlan<E>): Optional<R> =
        Optional.ofNullable(firstValue(plan))

    fun <R: Any> firstValue(plan: JdbcFluentQueryPlan<E>): R? =
        rows<R>(plan, terminalLimit = 1).firstOrNull()

    fun <R: Any> one(plan: JdbcFluentQueryPlan<E>): Optional<R> =
        Optional.ofNullable(oneValue(plan))

    fun <R: Any> oneValue(plan: JdbcFluentQueryPlan<E>): R? {
        val rows = rows<R>(plan.withLimit(0), terminalLimit = 2)
        if (rows.size > 1) {
            throw IncorrectResultSizeDataAccessException(1, rows.size)
        }
        return rows.singleOrNull()
    }

    fun <R: Any> all(plan: JdbcFluentQueryPlan<E>): List<R> = rows(plan)

    fun <R: Any> page(plan: JdbcFluentQueryPlan<E>, pageable: Pageable): Page<R> {
        validate(plan)
        val pagePlan = plan.withPageableSort(pageable.sort)
        if (pageable.isUnpaged) {
            val content = rows<R>(pagePlan)
            return PageableExecutionUtils.getPage(content, pageable) { content.size.toLong() }
        }

        val content = rows<R>(
            plan = pagePlan.withLimit(0),
            terminalLimit = pageable.pageSize,
            offset = pageable.offset,
        )
        return PageableExecutionUtils.getPage(content, pageable) { count(plan) }
    }

    fun count(plan: JdbcFluentQueryPlan<E>): Long {
        validate(plan)
        return baseQuery(plan).count()
    }

    fun exists(plan: JdbcFluentQueryPlan<E>): Boolean {
        validate(plan)
        return !baseQuery(plan)
            .adjustSelect { select(table.id) }
            .limit(1)
            .empty()
    }

    fun <R: Any> stream(plan: JdbcFluentQueryPlan<E>): Stream<R> {
        validate(plan)
        validateStreamTransactionOwnership()
        val transaction = TransactionManager.current()
        val shape: JdbcProjectionShape<R>? = if (Entity::class.java.isAssignableFrom(plan.resultType)) {
            require(!plan.propertiesSpecified) {
                "Partial Entity projection is not supported; use as(ClosedProjection::class.java)."
            }
            null
        } else {
            val resolver = JdbcPersistentPropertyResolver(plan.persistentEntity)
            val projectionMapper = JdbcProjectionMapper(plan.domainType, plan.projectionFactory, resolver)
            val explicit = plan.explicitProperties.takeIf { plan.propertiesSpecified }
            @Suppress("UNCHECKED_CAST")
            projectionMapper.shape(plan.resultType as Class<R>, explicit)
        }

        val query = baseQuery(plan)
        val mapper: (Int, org.jetbrains.exposed.v1.core.ResultRow) -> R
        if (shape == null) {
            @Suppress("UNCHECKED_CAST")
            mapper = { _, row -> entityClass.wrapRow(row) as R }
        } else {
            query.adjustSelect { select(shape.requiredProperties.map { it.column }) }
            mapper = { rowIndex, row ->
                val values = shape.requiredProperties.associate { property ->
                    @Suppress("UNCHECKED_CAST")
                    property.logicalName to row[property.column as Column<Any?>]
                }
                shape.map(rowIndex, values)
            }
        }

        if (plan.sort.isSorted) {
            query.orderBy(*resolveSort(plan.sort, JdbcPersistentPropertyResolver(plan.persistentEntity)))
        }
        if (plan.hasLimit) query.limit(plan.limit)
        return JdbcResultRowStream.open(transaction, query, mapper)
    }

    private fun <R: Any> rows(
        plan: JdbcFluentQueryPlan<E>,
        terminalLimit: Int? = null,
        offset: Long = 0L,
    ): List<R> {
        validate(plan)
        val effectiveLimit = effectiveLimit(plan.limit, terminalLimit)
        return if (Entity::class.java.isAssignableFrom(plan.resultType)) {
            require(!plan.propertiesSpecified) {
                "Partial Entity projection is not supported; use as(ClosedProjection::class.java)."
            }
            entityRows(plan, effectiveLimit, offset)
        } else {
            projectionRows(plan, effectiveLimit, offset)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R: Any> entityRows(
        plan: JdbcFluentQueryPlan<E>,
        limit: Int?,
        offset: Long,
    ): List<R> {
        var query: SizedIterable<E> = entityClass.wrapRows(baseQuery(plan))
        if (plan.sort.isSorted) query = query.orderBy(*resolveSort(plan.sort))
        if (limit != null) query = query.limit(limit)
        if (offset > 0) query = query.offset(offset)
        return query.toList() as List<R>
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R: Any> projectionRows(
        plan: JdbcFluentQueryPlan<E>,
        limit: Int?,
        offset: Long,
    ): List<R> {
        val resolver = JdbcPersistentPropertyResolver(plan.persistentEntity)
        val mapper = JdbcProjectionMapper(plan.domainType, plan.projectionFactory, resolver)
        val explicit = plan.explicitProperties.takeIf { plan.propertiesSpecified }
        val shape = mapper.shape(plan.resultType as Class<R>, explicit)
        val query = baseQuery(plan).adjustSelect { select(shape.requiredProperties.map { it.column }) }
        if (plan.sort.isSorted) query.orderBy(*resolveSort(plan.sort, resolver))
        if (limit != null) query.limit(limit)
        if (offset > 0) query.offset(offset)

        return query.mapIndexed { rowIndex, row ->
            val values = shape.requiredProperties.associate { property ->
                @Suppress("UNCHECKED_CAST")
                property.logicalName to row[property.column as Column<Any?>]
            }
            shape.map(rowIndex, values)
        }
    }

    private fun predicate(plan: JdbcFluentQueryPlan<E>): Op<Boolean> {
        @Suppress("UNCHECKED_CAST")
        val persistentEntity = plan.persistentEntity as ExposedPersistentEntity<E>
        return JdbcExamplePredicateCompiler(
            persistentEntity = persistentEntity,
            propertyResolver = JdbcPersistentPropertyResolver(persistentEntity),
            entityClass = entityClass,
            transaction = TransactionManager.current(),
        ).compile(plan.example)
    }

    private fun baseQuery(plan: JdbcFluentQueryPlan<E>): org.jetbrains.exposed.v1.jdbc.Query {
        validateRootTableQuery(entityClass.searchQuery(Op.TRUE))
        return entityClass.searchQuery(predicate(plan)).also(::validateRootTableQuery)
    }

    private fun validateRootTableQuery(query: org.jetbrains.exposed.v1.jdbc.Query) {
        val rootColumnsOnly = query.set.source == table && query.set.fields.all { field ->
            field is Column<*> && field.table == table
        } && query.set.fields.toSet() == table.columns.toSet()
        val unsupportedShape = query.distinct ||
            query.distinctOn != null ||
            query.groupedByColumns.isNotEmpty() ||
            query.having != null ||
            query.orderByExpressions.isNotEmpty() ||
            query.limit != null ||
            query.offset > 0 ||
            query.isForUpdate()
        if (!rootColumnsOnly || unsupportedShape) {
            throw UnsupportedOperationException(
                "JDBC FluentQuery supports only root-table filter-only EntityClass.searchQuery shapes.",
            )
        }
    }

    private fun validateStreamTransactionOwnership() {
        if (creationMode != JdbcRepositoryCreationMode.FACTORY) return
        val status = try {
            TransactionAspectSupport.currentTransactionStatus()
        } catch (_: NoTransactionException) {
            null
        }
        if (status == null || status.isNewTransaction) {
            throw InvalidDataAccessApiUsageException(
                "JDBC FluentQuery stream requires a caller-owned outer Spring transaction.",
            )
        }
    }

    private fun validate(plan: JdbcFluentQueryPlan<E>) {
        val transaction = TransactionManager.currentOrNull()
            ?: throw InvalidDataAccessApiUsageException(
                "JDBC FluentQuery requires the Exposed transaction captured by findBy.",
            )
        plan.validateScope(transaction)
    }

    private fun resolveSort(
        sort: Sort,
        resolver: JdbcPersistentPropertyResolver? = null,
    ): Array<Pair<Expression<*>, SortOrder>> {
        val resolveColumn: (String) -> Column<*> = resolver
            ?.let { propertyResolver -> { name -> propertyResolver.resolve(name).column } }
            ?: { name -> resolveTableColumn(name) }
        return sort.map { order ->
            if (order.isIgnoreCase || order.nullHandling != Sort.NullHandling.NATIVE) {
                throw InvalidDataAccessApiUsageException(
                    "FluentQuery sort options ignoreCase/nullHandling are not supported for '${order.property}'.",
                )
            }
            resolveColumn(order.property) to
                if (order.isAscending) SortOrder.ASC else SortOrder.DESC
        }.toList().toTypedArray()
    }

    private fun effectiveLimit(planLimit: Int, terminalLimit: Int?): Int? = when {
        terminalLimit == null -> planLimit.takeIf { it > 0 }
        planLimit == 0 -> terminalLimit
        else -> minOf(planLimit, terminalLimit)
    }

    private fun resolveTableColumn(propertyName: String): Column<*> =
        table.columns.singleOrNull { column ->
            column.name == propertyName ||
                column.name == toSnakeCase(propertyName) ||
                toCamelCase(column.name) == propertyName
        } ?: throw InvalidDataAccessApiUsageException(
            "FluentQuery sort property '$propertyName' is unknown or ambiguous.",
        )
}
