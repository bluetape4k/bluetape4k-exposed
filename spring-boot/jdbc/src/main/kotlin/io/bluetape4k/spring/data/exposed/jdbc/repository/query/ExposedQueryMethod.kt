package io.bluetape4k.spring.data.exposed.jdbc.repository.query

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.annotation.Query
import io.bluetape4k.spring.data.exposed.common.annotation.Query as CommonQuery
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.repository.core.RepositoryMetadata
import org.springframework.data.repository.query.Parameters
import org.springframework.data.repository.query.ParametersSource
import org.springframework.data.repository.query.QueryMethod
import java.lang.reflect.Method

/**
 * Exposed Repository 메서드에 대한 메타데이터를 표현합니다.
 *
 * ```kotlin
 * // ExposedQueryLookupStrategy 내부에서 자동 생성됩니다.
 * val method = ExposedQueryMethod(
 *     method     = UserRepository::class.java.getMethod("findByName", String::class.java),
 *     metadata   = metadata,
 *     factory    = projectionFactory,
 * )
 * val hasQuery = method.isAnnotatedQuery  // @Query 어노테이션 존재 여부
 * val sql      = method.getAnnotatedQuery() // SQL 문자열 또는 null
 * ```
 */
class ExposedQueryMethod(
    method: Method,
    metadata: RepositoryMetadata,
    factory: ProjectionFactory,
    parametersFunction: ((ParametersSource) -> Parameters<*, *>)? = null,
): QueryMethod(method, metadata, factory, parametersFunction) {

    companion object: KLogging()

    private val legacyQueryAnnotation: Query? = method.getAnnotation(Query::class.java)
    private val commonQueryAnnotation: CommonQuery? = method.getAnnotation(CommonQuery::class.java)

    /**
     * @Query 어노테이션이 존재하는지 여부
     */
    val isAnnotatedQuery: Boolean get() = legacyQueryAnnotation != null || commonQueryAnnotation != null

    /**
     * @Query 어노테이션의 SQL 문자열 (없으면 null)
     */
    fun getAnnotatedQuery(): String? = queryText

    /**
     * @Query 어노테이션의 count 쿼리 문자열 (없으면 null)
     */
    fun getCountQuery(): String? = countQueryText

    private val queryText: String? get() = legacyQueryAnnotation?.value ?: commonQueryAnnotation?.value
    private val countQueryText: String?
        get() = (legacyQueryAnnotation?.countQuery ?: commonQueryAnnotation?.countQuery)
            ?.takeIf { it.isNotBlank() }
}
