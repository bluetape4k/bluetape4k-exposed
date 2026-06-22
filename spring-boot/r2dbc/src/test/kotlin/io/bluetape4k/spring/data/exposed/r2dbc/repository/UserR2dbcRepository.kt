package io.bluetape4k.spring.data.exposed.r2dbc.repository

import io.bluetape4k.spring.data.exposed.jdbc.annotation.Query
import io.bluetape4k.spring.data.exposed.r2dbc.domain.USERS_TABLE_NAME
import io.bluetape4k.spring.data.exposed.r2dbc.domain.User
import io.bluetape4k.spring.data.exposed.r2dbc.domain.Users
import kotlinx.coroutines.flow.Flow
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort

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

    suspend fun findByName(name: String, sort: Sort): List<User>

    suspend fun findByName(name: String, pageable: Pageable): Page<User>

    suspend fun findByAgeGreaterThan(age: Int): List<User>

    suspend fun findByAgeGreaterThan(age: Int, pageable: Pageable): Slice<User>

    suspend fun findByEmailContaining(keyword: String): List<User>

    suspend fun findByNameAndAge(name: String, age: Int): User?

    suspend fun countByAge(age: Int): Long

    suspend fun existsByEmail(email: String): Boolean

    suspend fun deleteByName(name: String): Long

    suspend fun findTop3ByOrderByAgeDesc(): List<User>

    suspend fun findFirstByNameOrderByAgeDesc(name: String): User?

    fun findByNameOrderByAgeAsc(name: String): Flow<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?1")
    suspend fun findByEmailNative(email: String): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE age = ?2 AND email = ?1")
    suspend fun findByEmailAndAgeNative(email: String, age: Int): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?1 OR email = ?1")
    suspend fun findByEmailNativeDuplicatedPlaceholder(email: String): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE age = ?1")
    suspend fun findByAgeNativeLong(age: Long): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE age BETWEEN ?1 AND ?2")
    suspend fun findByAgeRangeNative(minAge: Int, maxAge: Int): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?10")
    suspend fun findByEmailNativeTenthPlaceholder(
        p1: String, p2: String, p3: String, p4: String, p5: String,
        p6: String, p7: String, p8: String, p9: String, p10: String,
    ): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?2")
    suspend fun findByEmailNativeBrokenPlaceholder(email: String): List<User>
}
