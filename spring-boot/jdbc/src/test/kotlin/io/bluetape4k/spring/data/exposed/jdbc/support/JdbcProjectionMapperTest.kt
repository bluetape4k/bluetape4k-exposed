package io.bluetape4k.spring.data.exposed.jdbc.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.common.mapping.ExposedMappingContext
import io.bluetape4k.spring.data.exposed.jdbc.domain.UserEntity
import io.bluetape4k.spring.data.exposed.jdbc.domain.Users
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcPersistentPropertyResolver
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.JdbcProjectionMapper
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.shape
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.mapping.MappingException
import org.springframework.data.projection.SpelAwareProxyProjectionFactory

class JdbcProjectionMapperTest {

    companion object: KLogging()

    private val mapper = JdbcProjectionMapper(
        domainType = UserEntity::class.java,
        projectionFactory = SpelAwareProxyProjectionFactory(),
        propertyResolver = JdbcPersistentPropertyResolver(
            ExposedMappingContext().getRequiredPersistentEntity(UserEntity::class.java),
        ),
    )

    @Test
    fun `closed getter interface maps eagerly copied values`() {
        val shape = mapper.shape<UserView>(listOf("name", "age"))
        val result = shape.map(0, mapOf("name" to "alice", "age" to 42))

        shape.requiredProperties.map { it.logicalName } shouldBeEqualTo listOf("name", "age")
        result.name shouldBeEqualTo "alice"
        result.age shouldBeEqualTo 42
    }

    @Test
    fun `inherited getter remains a closed projection input`() {
        val shape = mapper.shape<InheritedUserView>(listOf("name", "age"))
        val result = shape.map(0, mapOf("name" to "alice", "age" to 42))

        result.name shouldBeEqualTo "alice"
        result.age shouldBeEqualTo 42
    }

    @Test
    fun `Kotlin data class and Java record use named constructor inputs`() {
        val dto = mapper.shape(UserNameDto::class.java, listOf("name", "age"))
            .map(0, mapOf("name" to "alice", "age" to 42))

        val record = mapper.shape(UserNameRecord::class.java, listOf("name", "age"))
            .map(0, mapOf("name" to "alice", "age" to 42))

        dto shouldBeEqualTo UserNameDto("alice", 42)
        record shouldBeEqualTo UserNameRecord("alice", 42)
    }

    @Test
    fun `zero argument projection is rejected before row mapping`() {
        assertFailsWith<UnsupportedOperationException> {
            mapper.shape<ZeroArgumentProjection>()
        }
    }

    @Test
    fun `snake case interface input maps through its canonical property`() {
        val profileMapper = JdbcProjectionMapper(
            domainType = ProfileEntity::class.java,
            projectionFactory = SpelAwareProxyProjectionFactory(),
            propertyResolver = JdbcPersistentPropertyResolver(
                ExposedMappingContext().getRequiredPersistentEntity(ProfileEntity::class.java),
            ),
        )

        val shape = profileMapper.shape<SnakeCaseNameView>(listOf("display_name"))
        val result = shape.map(0, mapOf("displayName" to "alice"))

        shape.requiredProperties.single().logicalName shouldBeEqualTo "displayName"
        result.display_name shouldBeEqualTo "alice"
    }

    @Test
    fun `EntityID is unwrapped before projection creation`() {
        val result = mapper.shape<UserIdView>(listOf("id"))
            .map(0, mapOf("id" to EntityID(7L, Users)))

        result.id shouldBeEqualTo 7L
    }

    @Test
    fun `explicit properties must exactly match required inputs`() {
        assertFailsWith<InvalidDataAccessApiUsageException> {
            mapper.shape<UserView>(listOf("name"))
        }
        assertFailsWith<InvalidDataAccessApiUsageException> {
            mapper.shape<UserView>(listOf("name", "age", "email"))
        }
    }

    @Test
    fun `partial Entity and open projection are rejected before row mapping`() {
        assertFailsWith<InvalidDataAccessApiUsageException> {
            mapper.shape<UserEntity>(listOf("name"))
        }
        assertFailsWith<UnsupportedOperationException> {
            mapper.shape<OpenUserView>(listOf("name"))
        }
    }

    @Test
    fun `nullability and type mismatch fail without exposing row values`() {
        val nullFailure = assertFailsWith<MappingException> {
            mapper.shape<UserNameDto>(listOf("name", "age"))
                .map(3, mapOf("name" to null, "age" to 42))
        }
        val typeFailure = assertFailsWith<MappingException> {
            mapper.shape<UserNameDto>(listOf("name", "age"))
                .map(4, mapOf("name" to "sensitive-name", "age" to "not-a-number"))
        }

        nullFailure.message shouldNotContain "alice"
        typeFailure.message shouldNotContain "sensitive-name"
        typeFailure.message shouldNotContain "not-a-number"
    }

    @Test
    fun `constructor failure redacts the nested cause message`() {
        val failure = assertFailsWith<MappingException> {
            mapper.shape<FailingUserProjection>(listOf("name"))
                .map(5, mapOf("name" to "alice"))
        }

        throwableGraph(failure) shouldNotContain "sensitive-constructor-value"
        failure.cause?.cause.shouldBeNull()
    }

    private fun throwableGraph(failure: Throwable): String = buildString {
        var current: Throwable? = failure
        while (current != null) {
            append(current::class.java.name)
            append(':')
            appendLine(current.message)
            current.suppressed.forEach { suppressed -> appendLine(suppressed.message) }
            current = current.cause
        }
    }

}

internal interface NamedView {
    val name: String
}

internal interface UserView {
    val name: String
    val age: Int
}

internal interface InheritedUserView: NamedView {
    val age: Int
}

internal interface UserIdView {
    val id: Long
}

internal interface OpenUserView {
    @get:Value("#{target.name}")
    val displayName: String
}

internal data class UserNameDto(
    val name: String,
    val age: Int,
)

internal class ZeroArgumentProjection

internal class FailingUserProjection(
    val name: String,
) {
    init {
        throw IllegalArgumentException("sensitive-constructor-value")
    }
}

internal interface SnakeCaseNameView {
    @Suppress("VariableNaming")
    val display_name: String
}
