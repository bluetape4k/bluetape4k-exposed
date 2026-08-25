package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class R2dbcPersistentPropertyResolverTest {

    private val resolver = R2dbcPersistentPropertyResolver(User::class, Users)

    @Test
    fun `exact and snake case properties resolve to one exposed column`() {
        resolver.resolve("name").logicalName shouldBeEqualTo "name"
        resolver.resolve("email").logicalName shouldBeEqualTo "email"
        resolver.resolve("age").logicalName shouldBeEqualTo "age"
        resolver.resolve("id").logicalName shouldBeEqualTo "id"
    }

    @Test
    fun `unknown nested and ambiguous properties fail before getter`() {
        assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
            resolver.resolve("unknown")
        }
        assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
            resolver.resolve("address.city")
        }
    }

    @Test
    fun `snapshot reads getter and transformer exactly once and detaches the probe`() {
        val transformerCalls = AtomicInteger()
        val matcher = ExampleMatcher.matching()
            .withIgnorePaths("id", "email", "age")
            .withMatcher("name") { specifier ->
                specifier.contains().transform { value ->
                    transformerCalls.incrementAndGet()
                    value
                }
            }
        val probe = User(name = "alpha", email = "ignored", age = 0)

        val snapshot = resolver.snapshot(Example.of(probe, matcher))

        transformerCalls.get() shouldBeEqualTo 1
        snapshot.properties.map { it.property } shouldBeEqualTo listOf("name")
        snapshot.properties.single().value shouldBeEqualTo "alpha"
    }

    @Test
    fun `unsupported matcher and probe subtype mismatch fail before SQL`() {
        val regex = ExampleMatcher.matching()
            .withIgnorePaths("id", "email", "age")
            .withMatcher("name") { it.regex() }
        assertFailsWith<UnsupportedOperationException> {
            resolver.snapshot(Example.of(User(name = "alpha", email = "x", age = 1), regex))
        }

        assertFailsWith<org.springframework.dao.InvalidDataAccessApiUsageException> {
            @Suppress("UNCHECKED_CAST")
            resolver.snapshot(Example.of("not-a-user") as Example<Any>)
        }
    }

    @Test
    fun `include null wins when transformer returns empty`() {
        val matcher = ExampleMatcher.matching()
            .withIncludeNullValues()
            .withIgnorePaths("name", "email", "age")
            .withTransformer("id") { java.util.Optional.empty() }

        val snapshot = resolver.snapshot(Example.of(User(name = "alpha", email = "x", age = 1), matcher))

        snapshot.properties.size shouldBeEqualTo 1
        snapshot.properties.single().property shouldBeEqualTo "id"
        snapshot.properties.single().includeNull.shouldBeTrue()
    }
}
