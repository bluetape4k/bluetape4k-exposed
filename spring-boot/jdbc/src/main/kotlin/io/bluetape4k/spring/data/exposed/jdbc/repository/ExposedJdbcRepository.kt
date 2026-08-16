package io.bluetape4k.spring.data.exposed.jdbc.repository

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.ListPagingAndSortingRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.query.QueryByExampleExecutor

/**
 * Exposed DAO Entity를 위한 Spring Data Repository 인터페이스입니다.
 *
 * Spring Data 4.x의 [ListCrudRepository], [ListPagingAndSortingRepository],
 * [QueryByExampleExecutor]를 모두 지원합니다.
 *
 * Exposed DSL Op 직접 사용을 위한 확장 메서드도 제공합니다.
 *
 * ```kotlin
 * @ExposedEntity
 * class User(id: EntityID<Long>) : LongEntity(id) {
 *     companion object : LongEntityClass<User>(Users)
 *     var name by Users.name
 *     var age  by Users.age
 * }
 *
 * interface UserRepository : ExposedJdbcRepository<User, Long> {
 *     override val table: IdTable<Long> get() = Users
 *     override fun extractId(entity: User): Long? = if (entity.id.value == 0L) null else entity.id.value
 *     fun findByName(name: String): List<User>
 * }
 *
 * // 사용 예
 * val adults = userRepository.findAll { Users.age greaterEq 18 }
 * val count  = userRepository.count  { Users.age greaterEq 18 } // 18세 이상 수
 * val exists = userRepository.exists { Users.name eq "Alice" }  // true/false
 * ```
 *
 * `QueryByExampleExecutor.findBy`의 probe는 현재 Exposed transaction에 연결된
 * 영속 Entity여야 합니다. Closed getter interface, Kotlin data class, Java record
 * projection은 필요한 column만 조회하며, open SpEL projection과 nested property는
 * SQL 실행 전에 거부합니다. 문자열 matcher는 `DEFAULT`, `EXACT`, `CONTAINING`,
 * `STARTING`, `ENDING`만 지원하고 ignore-case와 regex는 지원하지 않습니다.
 * `project(properties)`의 non-empty property set은 projection의 필수 input과 정확히
 * 같아야 하며 빈 set은 필수 input 자동 선택을 사용합니다. `first`는 첫 행만 읽고,
 * `one`은 limit 설정과 무관하게 둘 이상의 행을 검출해
 * `IncorrectResultSizeDataAccessException`을 발생시킵니다.
 *
 * Cursor-backed `stream()`은 caller-owned outer transaction과 생성 thread 안에서만
 * 소비할 수 있습니다. Kotlin `use` 또는 Java try-with-resources로 명시적으로 닫고,
 * cursor가 열린 동안 같은 transaction에서 다른 repository/Exposed SQL을 실행하지
 * 마세요. Positive `DatabaseConfig.defaultFetchSize`가 없으면 bounded fetch size
 * `100`을 적용합니다. MySQL server-side cursor는 JDBC URL의 `useCursorFetch=true`도
 * 필요합니다. Driver cleanup 실패는 `DataAccessResourceFailureException`으로
 * 노출되며, 이 경우 현재 transaction을 종료해야 합니다.
 */
@NoRepositoryBean
interface ExposedJdbcRepository<E: Entity<ID>, ID: Any>: ListCrudRepository<E, ID>,
                                                         ListPagingAndSortingRepository<E, ID>,
                                                         QueryByExampleExecutor<E> {

    /**
     * 이 Repository가 사용하는 Exposed [IdTable].
     */
    val table: IdTable<ID>

    /**
     * 도메인 객체 [entity]에서 ID를 추출합니다. 신규 엔티티는 null을 반환합니다.
     */
    fun extractId(entity: E): ID?

    /**
     * 주어진 Exposed DSL 조건으로 Entity 목록을 조회합니다.
     *
     * ```kotlin
     * userRepository.findAll { Users.age greaterEq 18 }
     * ```
     */
    fun findAll(op: () -> Op<Boolean>): List<E>

    /**
     * 주어진 Exposed DSL 조건에 맞는 Entity 수를 반환합니다.
     *
     * ```kotlin
     * val count = userRepository.count { Users.age greaterEq 18 } // 18세 이상 수
     * ```
     */
    fun count(op: () -> Op<Boolean>): Long

    /**
     * 주어진 Exposed DSL 조건에 맞는 Entity가 존재하는지 확인합니다.
     *
     * ```kotlin
     * val exists = userRepository.exists { Users.name eq "Alice" } // true 또는 false
     * ```
     */
    fun exists(op: () -> Op<Boolean>): Boolean
}
