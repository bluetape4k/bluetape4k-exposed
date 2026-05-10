package io.bluetape4k.spring.data.exposed.r2dbc.repository.query

import io.bluetape4k.logging.KLogging
import org.springframework.data.projection.ProjectionFactory
import org.springframework.data.repository.core.RepositoryMetadata
import org.springframework.data.repository.query.Parameters
import org.springframework.data.repository.query.ParametersSource
import org.springframework.data.repository.query.QueryMethod
import java.lang.reflect.Method
import kotlin.reflect.KClassifier
import kotlin.reflect.jvm.kotlinFunction

/**
 * Exposed R2DBC Repository 메서드에 대한 메타데이터입니다.
 */
internal class ExposedR2dbcQueryMethod(
    val sourceMethod: Method,
    metadata: RepositoryMetadata,
    factory: ProjectionFactory,
    parametersFunction: ((ParametersSource) -> Parameters<*, *>)? = null,
): QueryMethod(sourceMethod, metadata, factory, parametersFunction) {

    companion object: KLogging()

    val kotlinReturnClassifier: KClassifier? =
        sourceMethod.kotlinFunction?.returnType?.classifier
}
