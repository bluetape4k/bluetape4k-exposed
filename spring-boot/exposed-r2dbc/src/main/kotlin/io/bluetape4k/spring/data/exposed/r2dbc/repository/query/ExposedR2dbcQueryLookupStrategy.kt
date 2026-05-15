package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.KLogging
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.repository.core.NamedQueries
import org.springframework.data.repository.core.RepositoryMetadata
import org.springframework.data.repository.query.QueryLookupStrategy
import org.springframework.data.repository.query.RepositoryQuery
import java.lang.reflect.Method

/**
 * Exposed R2DBC Repository 메서드의 쿼리 전략을 결정합니다.
 *
 * 현재 R2DBC Repository는 테이블 기반 매핑을 사용하므로 `@Query` raw SQL은 제공하지 않고,
 * Spring Data PartTree 기반 메서드명 파생 쿼리를 지원합니다.
 */
internal class ExposedR2dbcQueryLookupStrategy(
    private val key: QueryLookupStrategy.Key,
    private val mapperResolver: (Class<*>) -> R2dbcQueryMapper<Any, Any>,
): QueryLookupStrategy {

    companion object: KLogging() {
        internal fun create(
            key: QueryLookupStrategy.Key,
            mapperResolver: (Class<*>) -> R2dbcQueryMapper<Any, Any>,
        ): ExposedR2dbcQueryLookupStrategy =
            ExposedR2dbcQueryLookupStrategy(key, mapperResolver)
    }

    override fun resolveQuery(
        method: Method,
        metadata: RepositoryMetadata,
        factory: ProjectionFactory,
        namedQueries: NamedQueries,
    ): RepositoryQuery {
        val queryMethod = ExposedR2dbcQueryMethod(method, metadata, factory)
        val mapper = mapperResolver(metadata.repositoryInterface)

        return when (key) {
            QueryLookupStrategy.Key.USE_DECLARED_QUERY -> {
                require(queryMethod.isAnnotatedQuery) {
                    "No @Query annotation found on method '${method.name}'"
                }
                DeclaredExposedR2dbcQuery(queryMethod, mapper)
            }

            QueryLookupStrategy.Key.CREATE -> PartTreeExposedR2dbcQuery(queryMethod, mapper)

            QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND -> {
                if (queryMethod.isAnnotatedQuery) {
                    DeclaredExposedR2dbcQuery(queryMethod, mapper)
                } else {
                    PartTreeExposedR2dbcQuery(queryMethod, mapper)
                }
            }
        }
    }
}
