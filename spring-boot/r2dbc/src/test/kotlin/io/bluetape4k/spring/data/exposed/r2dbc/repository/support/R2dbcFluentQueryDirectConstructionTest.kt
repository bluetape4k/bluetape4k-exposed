package io.bluetape4k.spring.data.exposed.r2dbc.repository.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.exposed.r2dbc.tests.TestDB
import io.bluetape4k.exposed.r2dbc.tests.withTables
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Example
import org.springframework.data.domain.ExampleMatcher

class R2dbcFluentQueryDirectConstructionTest {

    @Test
    fun `public four argument repository construction supports coroutine QBE`() = runSuspendIO {
        withTables(TestDB.H2, Users) {
            Users.insertAndGetId {
                it[Users.name] = "Alice"
                it[Users.email] = "alice@example.com"
                it[Users.age] = 30
            }
            val repository = SimpleExposedR2dbcRepository(
                Users,
                { row -> row.toUser() },
                { user ->
                    mapOf<Column<*>, Any?>(
                        Users.name to user.name,
                        Users.email to user.email,
                        Users.age to user.age,
                    )
                },
                { user -> user.id },
            )
            val example = Example.of(
                User(name = "Alice", email = "ignored", age = 0),
                ExampleMatcher.matching().withIgnorePaths("id", "email", "age"),
            )

            repository.findOne(example)?.name shouldBeEqualTo "Alice"
            repository.findAll(example).toList().map { it.name } shouldBeEqualTo listOf("Alice")
        }
    }

    private fun ResultRow.toUser() = User(
        id = this[Users.id].value,
        name = this[Users.name],
        email = this[Users.email],
        age = this[Users.age],
    )
}
