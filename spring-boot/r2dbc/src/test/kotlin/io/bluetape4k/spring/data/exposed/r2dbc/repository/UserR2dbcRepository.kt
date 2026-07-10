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

    @Query("SELECT id FROM $USERS_TABLE_NAME ORDER BY age ASC LIMIT 2")
    suspend fun findYoungestTwoNative(): List<User>

    @Query("SELECT DISTINCT id FROM $USERS_TABLE_NAME ORDER BY id LIMIT 2")
    suspend fun findDistinctIdsNative(): List<User>

    @Query("SELECT/* query shape */DISTINCT/* entity key */id FROM $USERS_TABLE_NAME ORDER BY id LIMIT 2")
    suspend fun findDistinctIdsWithBlockCommentsNative(): List<User>

    @Query(
        """
        SELECT source.id, (SELECT MAX(nested.age) FROM $USERS_TABLE_NAME nested) AS max_age
          FROM $USERS_TABLE_NAME source
         ORDER BY source.id
         LIMIT 1
        """
    )
    suspend fun findIdWithNestedProjectionNative(): List<User>

    @Query(
        """
        SELECT source.email, (SELECT MAX(nested.id) FROM $USERS_TABLE_NAME nested) AS max_id
          FROM $USERS_TABLE_NAME source
         WHERE 1 = 0
        """
    )
    suspend fun findOuterProjectionWithNestedIdNative(): List<User>

    @Query(
        """
        SELECT source.id AS id
          FROM $USERS_TABLE_NAME source
          JOIN $USERS_TABLE_NAME matched ON matched.age >= source.age
         ORDER BY source.age DESC, matched.age ASC
         LIMIT 3
        """
    )
    suspend fun findOldestJoinRowsNative(): List<User>

    @Query(
        """
        SELECT missing.id
          FROM (SELECT id + 1000000 AS id FROM $USERS_TABLE_NAME) missing
         ORDER BY missing.id
         LIMIT 1
        """
    )
    suspend fun findMissingEntityIdNative(): List<User>

    @Query("SELECT email FROM $USERS_TABLE_NAME ORDER BY email")
    suspend fun findEmailsProjectionNative(): List<User>

    @Query("SELECT id AS other_id FROM $USERS_TABLE_NAME WHERE 1 = 0")
    suspend fun findWrongIdAliasNative(): List<User>

    @Query("SELECT email AS id FROM $USERS_TABLE_NAME WHERE 1 = 0")
    suspend fun findExpressionIdAliasNative(): List<User>

    @Query(
        """
        SELECT email -- AS id
          FROM $USERS_TABLE_NAME
         WHERE 1 = 0
        """
    )
    suspend fun findCommentAliasProjectionNative(): List<User>

    @Query(
        """
        SELECT id, ${'$'}${'$'} SELECT email FROM ignored ${'$'}${'$'} AS marker
          FROM $USERS_TABLE_NAME
         WHERE 1 = 0
        """
    )
    suspend fun findPostgresDollarQuoteNative(): List<User>

    @Query(
        """
        SELECT email, ${'$'}${'$'}x,id,y${'$'}${'$'} AS marker
          FROM $USERS_TABLE_NAME
         WHERE 1 = 0
        """
    )
    suspend fun findPostgresDollarQuoteIdProjectionNative(): List<User>

    @Query(
        """
        SELECT id # SELECT email FROM ignored
          FROM $USERS_TABLE_NAME
         WHERE 1 = 0
        """
    )
    suspend fun findMySqlHashCommentNative(): List<User>

    @Query("SELECT age, COUNT(*) FROM $USERS_TABLE_NAME GROUP BY age")
    suspend fun groupByAgeNative(): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?10")
    suspend fun findByEmailNativeTenthPlaceholder(
        p1: String, p2: String, p3: String, p4: String, p5: String,
        p6: String, p7: String, p8: String, p9: String, p10: String,
    ): List<User>

    @Query("SELECT * FROM $USERS_TABLE_NAME WHERE email = ?2")
    suspend fun findByEmailNativeBrokenPlaceholder(email: String): List<User>
}
