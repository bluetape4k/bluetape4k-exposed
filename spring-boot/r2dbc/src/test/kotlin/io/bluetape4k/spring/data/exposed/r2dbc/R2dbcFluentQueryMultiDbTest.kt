package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher

/**
 * H2, PostgreSQL, and MySQL V8에서 coroutine QBE의 대표 결과 의미를 확인합니다.
 *
 * 전체 edge-case 계약은 H2 통합 테스트가 담당하고, 이 테스트는 실제 dialect별
 * Exposed SQL 생성·바인딩·결과 매핑 경로가 동일한지 확인하는 좁은 smoke matrix입니다.
 */
class R2dbcFluentQueryMultiDbTest: AbstractExposedR2dbcRepositoryTest() {

    @Autowired
    private lateinit var userRepository: UserR2dbcRepository

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `QBE representative terminals preserve semantics across R2DBC dialects`(testDB: TestDB) = runSuspendIO {
        withTables(testDB, Users) {
            insertUser("Alice", "alice@example.com", 30)
            insertUser("Bob", "bob@example.com", 25)

            val example = Example.of(
                User(name = "Alice", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "email", "age"),
            )

            userRepository.findOne(example)?.name shouldBeEqualTo "Alice"
            userRepository.count(example) shouldBeEqualTo 1L
            userRepository.exists(example).shouldBeTrue()

            val projectedNames = userRepository.findBy(example) { query ->
                query.asType(NameView::class).project("name").all()
            }.toList().map { it.name }
            projectedNames shouldBeEqualTo listOf("Alice")
        }
    }

    private suspend fun insertUser(name: String, email: String, age: Int) {
        Users.insertAndGetId {
            it[Users.name] = name
            it[Users.email] = email
            it[Users.age] = age
        }
    }

    private interface NameView {
        val name: String
    }
}
