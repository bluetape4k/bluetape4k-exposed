package io.bluetape4k.spring.data.exposed.r2dbc.repository

import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import kotlinx.coroutines.flow.Flow
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * 테스트용 suspend 기반 User Repository 입니다.
 */
interface UserR2dbcRepository: ExposedR2dbcRepository<User, Long> {

    override val table: Users get() = Users

    override fun extractId(entity: User): Long? = entity.id

    override fun toDomain(row: ResultRow): User =
        User(
            id = row[Users.id].value,
            name = row[Users.name],
            email = row[Users.email],
            age = row[Users.age],
        )

    override fun toPersistValues(domain: User): Map<Column<*>, Any?> =
        mapOf(
            Users.name to domain.name,
            Users.email to domain.email,
            Users.age to domain.age,
        )

    suspend fun findByName(name: String): List<User>

    suspend fun findByAgeGreaterThan(age: Int): List<User>

    suspend fun findByEmailContaining(keyword: String): List<User>

    suspend fun findByNameAndAge(name: String, age: Int): User?

    suspend fun countByAge(age: Int): Long

    suspend fun existsByEmail(email: String): Boolean

    suspend fun deleteByName(name: String): Long

    suspend fun findTop3ByOrderByAgeDesc(): List<User>

    suspend fun findFirstByNameOrderByAgeDesc(name: String): User?

    fun findByNameOrderByAgeAsc(name: String): Flow<User>
}
