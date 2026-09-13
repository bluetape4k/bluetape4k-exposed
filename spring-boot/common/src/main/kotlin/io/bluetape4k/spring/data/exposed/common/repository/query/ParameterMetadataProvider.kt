package io.bluetape4k.spring.data.exposed.common.repository.query

import org.springframework.data.repository.query.ParameterAccessor
import org.springframework.data.repository.query.Parameters
import org.springframework.data.repository.query.ParametersParameterAccessor

/** Spring Data repository 파라미터에서 [ParameterAccessor]를 제공합니다. */
class ParameterMetadataProvider(
    val accessor: ParameterAccessor,
) {

    companion object {
        /** 선언된 파라미터 메타데이터와 실제 호출 값을 하나의 accessor로 결합합니다. */
        fun of(parameters: Parameters<*, *>, values: Array<Any?>): ParameterMetadataProvider =
            ParameterMetadataProvider(ParametersParameterAccessor(parameters, values))
    }
}
