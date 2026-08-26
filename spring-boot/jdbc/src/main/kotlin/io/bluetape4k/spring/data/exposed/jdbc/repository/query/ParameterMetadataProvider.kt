package io.bluetape4k.spring.data.exposed.jdbc.repository.query

import io.bluetape4k.logging.KLogging
import org.springframework.data.repository.query.ParameterAccessor
import org.springframework.data.repository.query.Parameters

/** JDBC artifact에 남겨 둔 공통 [ParameterAccessor] facade입니다. */
@Deprecated(
    message = "common.repository.query.ParameterMetadataProvider를 사용하십시오.",
    replaceWith = ReplaceWith(
        "ParameterMetadataProvider",
        "io.bluetape4k.spring.data.exposed.common.repository.query",
    ),
)
class ParameterMetadataProvider(
    val accessor: ParameterAccessor,
) {

    companion object: KLogging() {
        fun of(parameters: Parameters<*, *>, values: Array<Any?>): ParameterMetadataProvider {
            val commonProvider =
                io.bluetape4k.spring.data.exposed.common.repository.query.ParameterMetadataProvider
                    .of(parameters, values)
            return ParameterMetadataProvider(commonProvider.accessor)
        }
    }
}
