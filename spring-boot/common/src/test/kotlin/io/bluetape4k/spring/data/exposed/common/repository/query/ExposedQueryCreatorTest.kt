package io.bluetape4k.spring.data.exposed.common.repository.query

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.spring.data.exposed.common.mapping.UserEntity
import io.bluetape4k.spring.data.exposed.common.mapping.Users
import org.junit.jupiter.api.Test
import org.springframework.data.projection.SpelAwareProxyProjectionFactory
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.QueryMethod
import org.springframework.data.repository.query.parser.PartTree

class ExposedQueryCreatorTest {

    private val metadata = DefaultRepositoryMetadata(PartTreeQueryRepository::class.java)
    private val projectionFactory = SpelAwareProxyProjectionFactory()

    @Test
    fun `escapeLikeWildcards escapes SQL wildcard characters`() {
        ExposedQueryCreator.escapeLikeWildcards("100%_ready\\now") shouldBeEqualTo
            "100\\%\\_ready\\\\now"
    }

    @Test
    fun `PartTree containing query creates an Exposed predicate`() {
        val method = PartTreeQueryRepository::class.java.getMethod("findByNameContaining", String::class.java)
        val queryMethod = QueryMethod(method, metadata, projectionFactory)
        val provider = ParameterMetadataProvider.of(queryMethod.parameters, arrayOf("Ali"))
        val tree = PartTree("findByNameContaining", UserEntity::class.java)

        val predicate = ExposedQueryCreator(tree, provider.accessor, Users).createQuery()

        predicate.shouldNotBeNull()
    }
}

private interface PartTreeQueryRepository: Repository<UserEntity, Long> {
    fun findByNameContaining(keyword: String): List<UserEntity>
}
