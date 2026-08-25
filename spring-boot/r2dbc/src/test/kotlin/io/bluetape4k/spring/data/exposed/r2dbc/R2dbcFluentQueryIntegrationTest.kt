package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.UserNameRecord
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue

class R2dbcFluentQueryIntegrationTest: AbstractExposedR2dbcRepositoryTest() {

    @Autowired
    private lateinit var userRepository: UserR2dbcRepository

    @Test
    fun `coroutine QBE supports direct terminals and cold Flow`() = runSuspendIO {
        withTables(TestDB.H2, Users) {
            insertUser("Alice", "alice@example.com", 30)
            insertUser("Bob", "bob@example.com", 25)
            insertUser("Carol", "carol@example.com", 40)

            val example = Example.of(
                User(name = "Alice", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "email", "age"),
            )
            val flow = userRepository.findAll(example)
            val alice = userRepository.findOne(example)

            alice?.name shouldBeEqualTo "Alice"
            flow.toList().map { it.name } shouldBeEqualTo listOf("Alice")
            flow.toList().map { it.name } shouldBeEqualTo listOf("Alice")
            userRepository.count(example) shouldBeEqualTo 1L
            userRepository.exists(example).shouldBeTrue()
        }
    }

    @Test
    fun `fluent query uses immutable plan, projection, page and slice`() = runSuspendIO {
        withTables(TestDB.H2, Users) {
            insertUser("Alice", "alice@example.com", 30)
            insertUser("Bob", "bob@example.com", 25)
            insertUser("Carol", "carol@example.com", 40)

            val example = Example.of(
                User(name = "ignored", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "name", "email", "age"),
            )
            val names = userRepository.findBy(example) { query ->
                query
                    .asType(NameView::class)
                    .project("name")
                    .sortBy(Sort.by(Sort.Direction.DESC, "age"))
                    .all()
            }

            names.toList().map { it.name } shouldBeEqualTo listOf("Carol", "Alice", "Bob")

            val autoProjectedNames = userRepository.findBy(example) { query ->
                query.asType(NameView::class).all()
            }
            autoProjectedNames.toList().map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Carol")

            val dataClassNames = userRepository.findBy(example) { query ->
                query.asType(NameDto::class).project("name").all()
            }
            dataClassNames.toList().map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Carol")

            val recordNames = userRepository.findBy(example) { query ->
                query.asType(UserNameRecord::class).project("name").all()
            }
            recordNames.toList().map { it.name } shouldBeEqualTo listOf("Alice", "Bob", "Carol")

            assertFailsWith<InvalidDataAccessApiUsageException> {
                userRepository.findBy(example) { query ->
                    query.asType(NameView::class).project("email").all()
                }
            }

            val page = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").page(PageRequest.of(0, 2))
            }
            page.content.size shouldBeEqualTo 2
            page.totalElements shouldBeEqualTo 3

            val unpagedWithLimit = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").limit(1).page(Pageable.unpaged())
            }
            unpagedWithLimit.content.size shouldBeEqualTo 1
            unpagedWithLimit.totalElements shouldBeEqualTo 3

            val slice = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").slice(PageRequest.of(0, 2))
            }
            slice.hasNext().shouldBeTrue()
        }
    }

    @Test
    fun `findOne and fluent one share strict cardinality`() = runSuspendIO {
        withTables(TestDB.H2, Users) {
            insertUser("Alice", "alice@example.com", 30)
            insertUser("Bob", "bob@example.com", 25)
            val example = Example.of(
                User(name = "ignored", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "name", "email", "age"),
            )

            assertFailsWith<IncorrectResultSizeDataAccessException> { userRepository.findOne(example) }
            assertFailsWith<IncorrectResultSizeDataAccessException> {
                userRepository.findBy(example) { query -> query.one() }
            }
        }
    }

    @Test
    fun `active nested transaction fails before QBE SQL`() = runSuspendIO {
        withTables(TestDB.H2, Users, configure = { useNestedTransactions = true }) {
            val example = Example.of(
                User(name = "Alice", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "email", "age"),
            )

            assertFailsWith<InvalidDataAccessApiUsageException> {
                userRepository.findOne(example)
            }
        }
    }

    private suspend fun insertUser(name: String, email: String, age: Int): User {
        val id = Users.insertAndGetId {
            it[Users.name] = name
            it[Users.email] = email
            it[Users.age] = age
        }.value
        return User(id, name, email, age)
    }

    private interface NameView {
        val name: String
    }

    data class NameDto(val name: String)
}
