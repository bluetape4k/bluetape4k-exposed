package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import org.springframework.data.domain.Sort
import kotlin.reflect.KClass

/** Fluent query의 immutable plan입니다. transaction/result/cursor는 보관하지 않습니다. */
internal data class R2dbcFluentQueryPlan(
    val snapshot: R2dbcExampleSnapshot,
    val resultType: KClass<*>,
    val sort: Sort = Sort.unsorted(),
    val limit: Int? = null,
    val projectedProperties: List<String>? = null,
) {

    fun sortBy(additionalSort: Sort): R2dbcFluentQueryPlan {
        require(!additionalSort.isUnsorted) { "FluentQuery sort must not be empty." }
        return copy(sort = sort.and(additionalSort))
    }

    fun limit(newLimit: Int): R2dbcFluentQueryPlan {
        require(newLimit >= 0) { "FluentQuery limit must be zero or positive." }
        return copy(limit = newLimit.takeIf { it > 0 })
    }

    fun asType(newResultType: KClass<*>): R2dbcFluentQueryPlan = copy(resultType = newResultType)

    fun project(vararg properties: String): R2dbcFluentQueryPlan =
        copy(projectedProperties = properties.toList().takeIf { it.isNotEmpty() })
}
