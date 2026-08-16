package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class R2dbcPersistentPropertyResolverTest {

    private val resolver = R2dbcPersistentPropertyResolver(User::class, Users)

    @Test
    fun `exact and snake case properties resolve to one exposed column`() {
        assertEquals("name", resolver.resolve("name").logicalName)
        assertEquals("email", resolver.resolve("email").logicalName)
        assertEquals("age", resolver.resolve("age").logicalName)
        assertEquals("id", resolver.resolve("id").logicalName)
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

        assertEquals(1, transformerCalls.get())
        assertEquals(listOf("name"), snapshot.properties.map { it.property })
        assertEquals("alpha", snapshot.properties.single().value)
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

        assertEquals(1, snapshot.properties.size)
        assertEquals("id", snapshot.properties.single().property)
        assertEquals(true, snapshot.properties.single().includeNull)
    }
}
