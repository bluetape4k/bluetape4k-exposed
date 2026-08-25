package io.bluetape4k.spring.data.exposed.jdbc.repository.support

import io.bluetape4k.spring.data.exposed.common.mapping.ExposedPersistentEntity
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.Sort
import org.springframework.data.projection.ProjectionFactory
import java.util.concurrent.atomic.AtomicBoolean

internal class JdbcFluentQueryScope private constructor(
    private val transactionIdentity: Any,
    private val ownerThreadId: Long,
) : AutoCloseable {

    private val active = AtomicBoolean(true)

    fun validate(currentTransactionIdentity: Any) {
        val failureMessage = when {
            !active.get() ->
                "FluentQuery callback scope is already closed; execute the terminal operation inside findBy."
            Thread.currentThread().threadId() != ownerThreadId ->
                "FluentQuery must be used on the callback owner thread."
            currentTransactionIdentity !== transactionIdentity ->
                "FluentQuery must use the Exposed transaction captured by findBy."
            else -> null
        }
        if (failureMessage != null) throw InvalidDataAccessApiUsageException(failureMessage)
    }

    override fun close() {
        active.set(false)
    }

    companion object {
        fun open(transactionIdentity: Any): JdbcFluentQueryScope =
            JdbcFluentQueryScope(transactionIdentity, Thread.currentThread().threadId())
    }
}

internal class JdbcFluentQueryPlan<E: Any> internal constructor(
    val example: Example<E>,
    val domainType: Class<E>,
    val resultType: Class<*>,
    val explicitProperties: Set<String>,
    val propertiesSpecified: Boolean,
    val sort: Sort,
    val limit: Int,
    val projectionFactory: ProjectionFactory,
    val persistentEntity: ExposedPersistentEntity<*>,
    val scope: JdbcFluentQueryScope,
) {

    val hasLimit: Boolean get() = limit > 0

    fun <R: Any> asType(projectionType: Class<R>): JdbcFluentQueryPlan<E> =
        copy(resultType = projectionType)

    fun withProperties(properties: Collection<String>?): JdbcFluentQueryPlan<E> {
        requireNotNull(properties) { "FluentQuery projection properties must not be null." }
        val explicit = properties.toCollection(LinkedHashSet())
        return copy(
            explicitProperties = explicit,
            propertiesSpecified = explicit.isNotEmpty(),
        )
    }

    fun withSort(additionalSort: Sort?): JdbcFluentQueryPlan<E> {
        requireNotNull(additionalSort) { "FluentQuery sort must not be null." }
        return if (additionalSort.isUnsorted) this else copy(sort = sort.and(additionalSort))
    }

    fun withPageableSort(pageableSort: Sort): JdbcFluentQueryPlan<E> =
        copy(sort = pageableSort)

    fun withLimit(newLimit: Int): JdbcFluentQueryPlan<E> {
        require(newLimit >= 0) { "FluentQuery limit must be zero or positive." }
        return copy(limit = newLimit)
    }

    fun validateScope(currentTransactionIdentity: Any) {
        scope.validate(currentTransactionIdentity)
    }

    private fun copy(
        resultType: Class<*> = this.resultType,
        explicitProperties: Set<String> = this.explicitProperties,
        propertiesSpecified: Boolean = this.propertiesSpecified,
        sort: Sort = this.sort,
        limit: Int = this.limit,
    ): JdbcFluentQueryPlan<E> = JdbcFluentQueryPlan(
        example = example,
        domainType = domainType,
        resultType = resultType,
        explicitProperties = explicitProperties,
        propertiesSpecified = propertiesSpecified,
        sort = sort,
        limit = limit,
        projectionFactory = projectionFactory,
        persistentEntity = persistentEntity,
        scope = scope,
    )

    companion object {
        fun <E: Any> create(
            example: Example<E>,
            domainType: Class<E>,
            projectionFactory: ProjectionFactory,
            persistentEntity: ExposedPersistentEntity<*>,
            scope: JdbcFluentQueryScope,
        ): JdbcFluentQueryPlan<E> = JdbcFluentQueryPlan(
            example = example,
            domainType = domainType,
            resultType = domainType,
            explicitProperties = emptySet(),
            propertiesSpecified = false,
            sort = Sort.unsorted(),
            limit = 0,
            projectionFactory = projectionFactory,
            persistentEntity = persistentEntity,
            scope = scope,
        )
    }
}
