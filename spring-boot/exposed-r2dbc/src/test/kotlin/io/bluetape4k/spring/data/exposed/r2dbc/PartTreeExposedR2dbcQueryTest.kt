package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired

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
    fun `findByName returns matching rows`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByName("Alice")
            results shouldHaveSize 2
            results.all { it.name == "Alice" }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByAgeGreaterThan filters rows`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByAgeGreaterThan(25)
            results.all { it.age > 25 }.shouldBeTrue()
            results shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByEmailContaining filters by substring`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            userRepository.findByEmailContaining("alice") shouldHaveSize 2
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByNameAndAge returns single row`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val user = userRepository.findByNameAndAge("Alice", 30)
            user.shouldNotBeNull()
            user.email shouldBeEqualTo "alice@example.com"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `countByAge returns matching count`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            userRepository.countByAge(30) shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `existsByEmail returns true when row exists`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            userRepository.existsByEmail("alice@example.com").shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `existsByEmail returns false when row does not exist`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            userRepository.existsByEmail("nobody@example.com").shouldBeFalse()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `deleteByName removes matching rows`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            userRepository.deleteByName("Alice") shouldBeEqualTo 2L
            userRepository.findByName("Alice").shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findTop3ByOrderByAgeDesc applies order before limit`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findTop3ByOrderByAgeDesc()
            results shouldHaveSize 3
            results.map { it.age } shouldBeEqualTo listOf(35, 30, 25)
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findFirstByNameOrderByAgeDesc returns first sorted row`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val user = userRepository.findFirstByNameOrderByAgeDesc("Alice")
            user.shouldNotBeNull()
            user.age shouldBeEqualTo 30
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `findByNameOrderByAgeAsc returns flow rows`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val results = userRepository.findByNameOrderByAgeAsc("Alice").toList()
            results.map { it.age } shouldBeEqualTo listOf(20, 30)
        }
    }
}
