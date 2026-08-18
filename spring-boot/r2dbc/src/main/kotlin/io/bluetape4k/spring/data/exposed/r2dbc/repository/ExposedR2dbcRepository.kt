package io.bluetape4k.spring.data.exposed.r2dbc.repository

import kotlinx.coroutines.flow.Flow
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * Exposed [IdTable] 기반 suspend CRUD/Paging/Streaming Repository 계약입니다.
 *
 * [CoroutineCrudRepository] 를 상속하여 Spring Data 표준 코루틴 인터페이스를 따르고,
 * 페이징([findAll]) 및 R2DBC row streaming([streamAll])도 제공합니다.
 *
 * ```kotlin
 * interface UserRepository : ExposedR2dbcRepository<UserDto, Long> {
 *     override val table: IdTable<Long> get() = Users
 *     override fun extractId(entity: UserDto): Long? = entity.id
 *     override fun toDomain(row: ResultRow): UserDto = UserDto(...)
 *     override fun toPersistValues(domain: UserDto): Map<Column<*>, Any?> = mapOf(...)
 * }
 * ```
 */
@NoRepositoryBean
interface ExposedR2dbcRepository<R: Any, ID: Any>: CoroutineCrudRepository<R, ID> {

    /** 이 Repository가 사용하는 Exposed [IdTable]. */
    val table: IdTable<ID>

    /** 도메인 객체 [entity]에서 ID를 추출합니다. 신규 엔티티는 null을 반환합니다. */
    fun extractId(entity: R): ID?

    override suspend fun <S: R> save(entity: S): S

    /**
     * Iterable 입력을 하나의 Exposed transaction에서 저장하고 block 정상 종료 후 결과를
     * 방출합니다. repository가 소유한 최상위 transaction에서는 입력 재순회를 막기 위해
     * `maxAttempts = 1`을 적용합니다. 이미 active outer transaction을 호출자가 소유한
     * 경우에는 caller의 retry 설정이 유지되므로 outer block 재시도에 안전한 Iterable을
     * 제공해야 합니다. 입력 수집 중 예외나 cancellation은 rollback되며 결과를 방출하지
     * 않습니다. commit 이후 downstream cancellation 또는 exception은 이미 완료된
     * transaction을 rollback하지 못하고 남은 결과 방출만 중단할 수 있습니다.
     */
    override fun <S: R> saveAll(entities: Iterable<S>): Flow<S>

    /**
     * Flow 입력을 하나의 Exposed transaction에서 순차 저장합니다.
     *
     * 최상위 transaction에서는 commit 이후 결과를 방출합니다. 활성 outer transaction을
     * 재사용하면 nested block 반환 후 outer commit 전에 방출될 수 있으며 최종 경계는
     * caller가 소유합니다. commit 이후 downstream cancellation 또는 exception은 이미
     * 완료된 transaction을 rollback하지 못하고 남은 결과 방출만 중단할 수 있습니다.
     * repository가 소유한 최상위 transaction에서는 입력 재수집과 side effect 반복을
     * 막기 위해 `maxAttempts = 1`을 적용합니다. 이미 active outer transaction을
     * 호출자가 소유한 경우에는 caller의 retry 설정이 유지되므로 outer block 재시도에
     * 안전한 replayable·side-effect-free 입력을 사용해야 합니다.
     */
    override fun <S: R> saveAll(entityStream: Flow<S>): Flow<S>

    /** [CoroutineCrudRepository.findById] 와 동일하나 명시적 nullable 이름을 제공합니다. */
    suspend fun findByIdOrNull(id: ID): R?

    override suspend fun existsById(id: ID): Boolean

    /**
     * 모든 결과를 메모리에 적재한 뒤 List로 반환합니다.
     *
     * WebFlux 응답처럼 트랜잭션 바깥에서 소비되는 경로에서는 이 메서드를 우선 사용합니다.
     */
    suspend fun findAllAsList(): List<R>

    override fun findAll(): Flow<R>
    override fun findAllById(ids: Iterable<ID>): Flow<R>
    override fun findAllById(ids: Flow<ID>): Flow<R>

    /** 페이징 조회. */
    suspend fun findAll(pageable: Pageable): Page<R>

    override suspend fun count(): Long

    override suspend fun deleteById(id: ID)
    override suspend fun delete(entity: R)
    override suspend fun deleteAllById(ids: Iterable<ID>)
    override suspend fun deleteAll(entities: Iterable<R>)
    override suspend fun <S: R> deleteAll(entityStream: Flow<S>)
    override suspend fun deleteAll()

    /**
     * ResultRow를 도메인 객체 [R]로 변환합니다.
     * 각 Repository 인터페이스에서 반드시 재정의해야 합니다.
     */
    fun toDomain(row: ResultRow): R

    /**
     * 저장/수정 시 사용할 컬럼 값을 제공합니다.
     * ID 컬럼은 제외하고 반환해야 합니다.
     */
    fun toPersistValues(domain: R): Map<Column<*>, Any?>

    /** Exposed DSL 조건으로 Entity 목록을 Flow로 조회합니다. */
    fun findAll(op: () -> Op<Boolean>): Flow<R>

    suspend fun count(op: () -> Op<Boolean>): Long
    suspend fun exists(op: () -> Op<Boolean>): Boolean

    /**
     * 지정한 [database]에서 모든 row를 순차적으로 읽어 [Flow]로 방출합니다.
     *
     * 진짜 row-by-row streaming으로, [findAll]의 eager materialization 과 달리
     * large result set 을 백프레셔와 함께 처리할 수 있습니다.
     */
    fun streamAll(database: R2dbcDatabase? = null): Flow<R>
}
