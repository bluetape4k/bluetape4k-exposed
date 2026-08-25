package io.bluetape4k.spring.data.exposed.common.repository.query

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.spring.data.exposed.common.mapping.UserEntity
import org.junit.jupiter.api.Test
import org.springframework.data.projection.SpelAwareProxyProjectionFactory
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.QueryMethod

class ParameterMetadataProviderTest {

    private val metadata = DefaultRepositoryMetadata(ParameterQueryRepository::class.java)
    private val projectionFactory = SpelAwareProxyProjectionFactory()

    @Test
    fun `of exposes bindable values in declaration order`() {
        val method = ParameterQueryRepository::class.java.getMethod(
            "findByNameAndAge",
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        val queryMethod = QueryMethod(method, metadata, projectionFactory)
        val provider = ParameterMetadataProvider.of(
            queryMethod.parameters,
            arrayOf("Alice", 30),
        )

        provider.accessor.getBindableValue(0) shouldBeEqualTo "Alice"
        provider.accessor.getBindableValue(1) shouldBeEqualTo 30
    }
}

private interface ParameterQueryRepository: Repository<UserEntity, Long> {
    fun findByNameAndAge(name: String, age: Int): List<UserEntity>
}
