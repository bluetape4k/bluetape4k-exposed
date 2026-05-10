package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.exposed.core.auditable.AuditableIdTable
import io.bluetape4k.exposed.core.auditable.AuditableIntIdTable
import io.bluetape4k.exposed.core.auditable.AuditableLongIdTable
import io.bluetape4k.exposed.core.auditable.AuditableUUIDTable
import io.bluetape4k.exposed.core.auditable.UserContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.*

/**
 * 감사(Auditing) 기능이 포함된 R2DBC Repository 인터페이스입니다.
 *
 * [auditedUpdateById] 및 [auditedUpdateAll]을 통해 UPDATE 시
 * DB `CURRENT_TIMESTAMP`(UTC)로 [AuditableIdTable.updatedAt]을 설정하고,
 * 호출 시점에 전달된 `updatedBy` 값으로 [AuditableIdTable.updatedBy]를 설정합니다.
 *
 * 일반 [R2dbcRepository.updateById]를 사용하면 감사 필드가 자동 설정되지 않으므로
 * UPDATE 시에는 반드시 [auditedUpdateById] 또는 [auditedUpdateAll]을 사용하세요.
 *
 * @param ID 기본키 타입
 * @param E 엔티티 타입
 * @param T [AuditableIdTable] 구현체
 *
 * ## 사용 예
 *
 * ```kotlin
 * object UserTable : AuditableLongIdTable("users") {
 *     val name = varchar("name", 128)
 *     val email = varchar("email", 256)
 * }
 *
 * data class UserRecord(
 *     val id: Long = 0L,
 *     val name: String,
 *     val email: String,
 * )
 *
 * class UserRepository : LongAuditableR2dbcRepository<UserRecord, UserTable> {
 *     override val table = UserTable
 *
 *     override fun extractId(entity: UserRecord) = entity.id
 *
 *     override suspend fun ResultRow.toEntity() = UserRecord(
 *         id    = this[UserTable.id].value,
 *         name  = this[UserTable.name],
 *         email = this[UserTable.email],
 *     )
 * }
 *
 * suspendTransaction {
 *     repo.auditedUpdateById(1L, updatedBy = "admin") {
 *         it[name] = "Alice"
 *     }
 * }
 * ```
 */
interface AuditableR2dbcRepository<ID: Any, E: Any, T: AuditableIdTable<ID>>: R2dbcRepository<ID, E> {

    override val table: T

    /**
     * ID로 엔티티를 업데이트하고 감사 필드를 자동 설정합니다.
     *
     * `updatedAt`은 DB `CURRENT_TIMESTAMP`(UTC)로, `updatedBy`는 [updatedBy] 인자로 설정됩니다.
     * [updatedBy]를 생략하면 호출 시점의 [UserContext.getCurrentUser] 값이 사용됩니다.
     *
     * @param id 업데이트할 엔티티의 ID
     * @param updatedBy 수정 사용자명
     * @param limit 최대 수정 개수
     * @param updateStatement 수정할 컬럼과 값을 지정하는 람다
     * @return 수정된 행 수
     */
    suspend fun auditedUpdateById(
        id: ID,
        updatedBy: String = UserContext.getCurrentUser(),
        limit: Int? = null,
        updateStatement: T.(UpdateStatement) -> Unit,
    ): Int = table.update(where = { table.id eq id }, limit = limit) {
        it[table.updatedAt] = CurrentTimestamp
        it[table.updatedBy] = updatedBy
        updateStatement(table, it)
    }

    /**
     * 조건에 맞는 엔티티들을 업데이트하고 감사 필드를 자동 설정합니다.
     *
     * `updatedAt`은 DB `CURRENT_TIMESTAMP`(UTC)로, `updatedBy`는 [updatedBy] 인자로 설정됩니다.
     * [updatedBy]를 생략하면 호출 시점의 [UserContext.getCurrentUser] 값이 사용됩니다.
     *
     * @param updatedBy 수정 사용자명
     * @param predicate WHERE 절 조건
     * @param limit 최대 수정 개수
     * @param updateStatement 수정할 컬럼과 값을 지정하는 람다
     * @return 수정된 행 수
     */
    suspend fun auditedUpdateAll(
        updatedBy: String = UserContext.getCurrentUser(),
        predicate: () -> Op<Boolean> = { Op.TRUE },
        limit: Int? = null,
        updateStatement: T.(UpdateStatement) -> Unit,
    ): Int = table.update(where = predicate, limit = limit) {
        it[table.updatedAt] = CurrentTimestamp
        it[table.updatedBy] = updatedBy
        updateStatement(table, it)
    }
}

/**
 * `Int` 기본키를 사용하는 [AuditableR2dbcRepository]의 편의 인터페이스입니다.
 *
 * @param E 엔티티 타입
 * @param T [AuditableIntIdTable] 구현체
 */
interface IntAuditableR2dbcRepository<E: Any, T: AuditableIntIdTable>: AuditableR2dbcRepository<Int, E, T>

/**
 * `Long` 기본키를 사용하는 [AuditableR2dbcRepository]의 편의 인터페이스입니다.
 *
 * @param E 엔티티 타입
 * @param T [AuditableLongIdTable] 구현체
 */
interface LongAuditableR2dbcRepository<E: Any, T: AuditableLongIdTable>: AuditableR2dbcRepository<Long, E, T>

/**
 * `java.util.UUID` 기본키를 사용하는 [AuditableR2dbcRepository]의 편의 인터페이스입니다.
 *
 * @param E 엔티티 타입
 * @param T [AuditableUUIDTable] 구현체
 */
interface UUIDAuditableR2dbcRepository<E: Any, T: AuditableUUIDTable>: AuditableR2dbcRepository<UUID, E, T>
