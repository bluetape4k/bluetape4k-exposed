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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

            assertEquals("Alice", alice?.name)
            assertEquals(listOf("Alice"), flow.toList().map { it.name })
            assertEquals(listOf("Alice"), flow.toList().map { it.name })
            assertEquals(1L, userRepository.count(example))
            assertTrue(userRepository.exists(example))
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

            assertEquals(listOf("Carol", "Alice", "Bob"), names.toList().map { it.name })

            val autoProjectedNames = userRepository.findBy(example) { query ->
                query.asType(NameView::class).all()
            }
            assertEquals(listOf("Alice", "Bob", "Carol"), autoProjectedNames.toList().map { it.name })

            val dataClassNames = userRepository.findBy(example) { query ->
                query.asType(NameDto::class).project("name").all()
            }
            assertEquals(listOf("Alice", "Bob", "Carol"), dataClassNames.toList().map { it.name })

            val recordNames = userRepository.findBy(example) { query ->
                query.asType(UserNameRecord::class).project("name").all()
            }
            assertEquals(listOf("Alice", "Bob", "Carol"), recordNames.toList().map { it.name })

            assertFailsWith<InvalidDataAccessApiUsageException> {
                userRepository.findBy(example) { query ->
                    query.asType(NameView::class).project("email").all()
                }
            }

            val page = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").page(PageRequest.of(0, 2))
            }
            assertEquals(2, page.content.size)
            assertEquals(3, page.totalElements)

            val unpagedWithLimit = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").limit(1).page(Pageable.unpaged())
            }
            assertEquals(1, unpagedWithLimit.content.size)
            assertEquals(3, unpagedWithLimit.totalElements)

            val slice = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").slice(PageRequest.of(0, 2))
            }
            assertTrue(slice.hasNext())
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
