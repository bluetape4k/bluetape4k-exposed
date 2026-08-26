package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import org.springframework.data.domain.ExampleMatcher
import kotlin.test.Test

class R2dbcExamplePredicateCompilerTest {

    @Test
    fun `compiler produces a predicate for all and any snapshots`() {
        val resolver = R2dbcPersistentPropertyResolver(User::class, Users)
        val all = R2dbcExampleSnapshot(
            expectedDomainType = User::class,
            matchingAll = true,
            nullHandler = ExampleMatcher.NullHandler.IGNORE,
            ignoredPaths = emptySet(),
            properties = listOf(
                R2dbcExamplePropertySnapshot(
                    "name", "alpha", ExampleMatcher.StringMatcher.EXACT, false, false,
                ),
                R2dbcExamplePropertySnapshot(
                    "email", "@example", ExampleMatcher.StringMatcher.CONTAINING, false, false,
                ),
            ),
        )
        val any = all.copy(matchingAll = false)
        val compiler = R2dbcExamplePredicateCompiler(resolver)

        compiler.compile(all).shouldNotBeNull()
        compiler.compile(any).shouldNotBeNull()
    }

    @Test
    fun `empty snapshot compiles to true predicate`() {
        val resolver = R2dbcPersistentPropertyResolver(User::class, Users)
        val snapshot = R2dbcExampleSnapshot(
            expectedDomainType = User::class,
            matchingAll = true,
            nullHandler = ExampleMatcher.NullHandler.IGNORE,
            ignoredPaths = emptySet(),
            properties = emptyList(),
        )

        R2dbcExamplePredicateCompiler(resolver).compile(snapshot).shouldNotBeNull()
    }
}
