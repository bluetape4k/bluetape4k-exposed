package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import io.bluetape4k.spring.data.exposed.r2dbc.repository.support.ExposedR2dbcRepositoryFactory
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.domain.Sort.Direction
import org.springframework.data.repository.query.QueryLookupStrategy

class PartTreeExposedR2dbcQueryTest: AbstractExposedR2dbcRepositoryTest() {

    @Autowired
    private lateinit var userRepository: UserR2dbcRepository

    private suspend fun createUsers() {
        Users.insertAndGetId { row ->
            row[name] = "Alice"
            row[email] = "alice@example.com"
            row[age] = 30
        }
        Users.insertAndGetId { row ->
            row[name] = "Bob"
            row[email] = "bob@example.com"
            row[age] = 25
        }
        Users.insertAndGetId { row ->
            row[name] = "Charlie"
            row[email] = "charlie@example.com"
            row[age] = 35
        }
        Users.insertAndGetId { row ->
            row[name] = "Alice"
            row[email] = "alice2@example.com"
            row[age] = 20
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByName returns matching rows`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByName("Alice")
            results shouldHaveSize 2
            results.all { it.name == "Alice" }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByName with Sort applies dynamic sort`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val sort = Sort.by(Direction.DESC, "age")
            val results = userRepository.findByName("Alice", sort)
            results.map { it.age } shouldBeEqualTo listOf(30, 20)
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByName with Pageable returns page`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val pageable = PageRequest.of(0, 1, Sort.by(Direction.ASC, "age"))
            val page = userRepository.findByName("Alice", pageable)
            page.totalElements shouldBeEqualTo 2L
            page.content.map { it.age } shouldBeEqualTo listOf(20)
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByAgeGreaterThan filters rows`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByAgeGreaterThan(25)
            results.all { it.age > 25 }.shouldBeTrue()
            results shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByAgeGreaterThan with Pageable returns slice`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val pageable = PageRequest.of(0, 2, Sort.by(Direction.ASC, "age"))
            val slice = userRepository.findByAgeGreaterThan(10, pageable)
            slice.content.map { it.age } shouldBeEqualTo listOf(20, 25)
            slice.hasNext().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByEmailContaining filters by substring`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByEmailContaining("alice")
            results shouldHaveSize 2
            results.all { "alice" in it.email }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByNameAndAge returns single row`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val user = userRepository.findByNameAndAge("Alice", 30)
            user.shouldNotBeNull()
            user.email shouldBeEqualTo "alice@example.com"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `countByAge returns matching count`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            userRepository.countByAge(30) shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `existsByEmail returns true when row exists`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            userRepository.existsByEmail("alice@example.com").shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `existsByEmail returns false when row does not exist`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            userRepository.existsByEmail("nobody@example.com").shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `deleteByName removes matching rows`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            userRepository.deleteByName("Alice") shouldBeEqualTo 2L
            userRepository.findByName("Alice").shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findTop3ByOrderByAgeDesc applies order before limit`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findTop3ByOrderByAgeDesc()
            results shouldHaveSize 3
            results.map { it.age } shouldBeEqualTo listOf(35, 30, 25)
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findFirstByNameOrderByAgeDesc returns first sorted row`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val user = userRepository.findFirstByNameOrderByAgeDesc("Alice")
            user.shouldNotBeNull()
            user.age shouldBeEqualTo 30
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByNameOrderByAgeAsc returns flow rows`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByNameOrderByAgeAsc("Alice").toList()
            results.map { it.age } shouldBeEqualTo listOf(20, 30)
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `USE_DECLARED_QUERY rejects derived methods in direct proxy path`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            val repo = ExposedR2dbcRepositoryFactory()
                .apply { setQueryLookupStrategyKey(QueryLookupStrategy.Key.USE_DECLARED_QUERY) }
                .getRepository(UserR2dbcRepository::class.java)

            assertFailsWith<IllegalArgumentException> {
                repo.findByName("Alice")
            }
        }
    }
}
