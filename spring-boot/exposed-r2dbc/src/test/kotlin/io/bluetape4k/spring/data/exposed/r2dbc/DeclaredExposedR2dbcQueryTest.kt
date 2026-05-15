package io.bluetape4k.spring.data.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.exposed.r2dbc.tests.AbstractExposedR2dbcTest
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import io.bluetape4k.spring.data.exposed.r2dbc.repository.UserR2dbcRepository
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * @Query raw SQL 지원 — JDBC DeclaredExposedQuery와 parity 검증
 */
class DeclaredExposedR2dbcQueryTest: AbstractExposedR2dbcRepositoryTest() {

    companion object: KLoggingChannel()

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
    fun `@Query native - 위치 기반 파라미터 바인딩으로 단일 엔티티 조회`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByEmailNative("alice@example.com")
            found shouldHaveSize 1
            found.first().name shouldBeEqualTo "Alice"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - 파라미터 순서가 역순이어도 올바르게 바인딩된다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByEmailAndAgeNative("alice@example.com", 30)
            found shouldHaveSize 1
            found.first().email shouldBeEqualTo "alice@example.com"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - SQL injection 문자열은 값으로 취급되어 우회되지 않는다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val injected = "alice@example.com' OR 1=1 --"
            val found = userRepository.findByEmailNative(injected)
            found.shouldBeEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - 따옴표가 포함된 문자열도 안전하게 조회된다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            Users.insertAndGetId { row ->
                row[name] = "O'Hara"
                row[email] = "o'hara@example.com"
                row[age] = 41
            }
            val found = userRepository.findByEmailNative("o'hara@example.com")
            found shouldHaveSize 1
            found.first().name shouldBeEqualTo "O'Hara"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - placeholder 인덱스가 잘못되면 예외를 던진다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            assertFailsWith<IllegalArgumentException> {
                userRepository.findByEmailNativeBrokenPlaceholder("alice@example.com")
            }
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - 동일 placeholder 재사용 시 같은 인자가 재사용된다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByEmailNativeDuplicatedPlaceholder("alice@example.com")
            found shouldHaveSize 1
            found.first().email shouldBeEqualTo "alice@example.com"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - Long 타입 숫자 파라미터도 정상 바인딩된다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByAgeNativeLong(30L)
            found shouldHaveSize 1
            found.first().name shouldBeEqualTo "Alice"
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - 범위 조건 파라미터를 순서대로 바인딩한다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByAgeRangeNative(25, 30)
            found shouldHaveSize 2
            found.all { it.age in 25..30 }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(AbstractExposedR2dbcTest.ENABLE_DIALECTS_METHOD)
    fun `@Query native - 10번째 placeholder 인덱스를 올바르게 해석한다`(testDB: TestDB) = runTest {
        withTables(testDB, Users) {
            createUsers()
            val found = userRepository.findByEmailNativeTenthPlaceholder(
                "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9", "alice@example.com"
            )
            found shouldHaveSize 1
            found.first().name shouldBeEqualTo "Alice"
        }
    }
}
