package io.bluetape4k.exposed.jdbc.repository

import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.BatchUpsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertBuilder
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteIgnoreWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [IdTable]을 기반으로 Exposed JDBC를 사용하는 기본 저장소 인터페이스입니다.
 *
 * 기본 키 타입 [ID]로 식별되는 [E] 엔티티를 조회하고 저장하며 삭제하는 공통 CRUD 연산을 제공합니다.
 * 구현체는 [table]과 [ResultRow.toEntity]만 정의하면 됩니다.
 *
 * @param ID 기본 키 타입(예: [Long], [Int], [java.util.UUID])
 * @param E [ResultRow]에서 매핑되는 엔티티(레코드) 타입
 *
 * ## 사용 예
 *
 * ```kotlin
 * // 1. 테이블 정의
 * object ActorTable : LongIdTable("actors") {
 *     val firstName = varchar("first_name", 50)
 *     val lastName  = varchar("last_name",  50)
 *     val birthday  = date("birthday").nullable()
 * }
 *
 * // 2. 레코드(DTO) 타입
 * data class ActorRecord(
 *     val id: Long = 0L,
 *     val firstName: String,
 *     val lastName: String,
 * )
 *
 * // 3. 저장소 구현
 * class ActorRepository : LongJdbcRepository<ActorRecord> {
 *     override val table = ActorTable
 *
 *     override fun ResultRow.toEntity() = ActorRecord(
 *         id        = this[ActorTable.id].value,
 *         firstName = this[ActorTable.firstName],
 *         lastName  = this[ActorTable.lastName],
 *     )
 *
 *     fun save(record: ActorRecord): ActorRecord {
 *         val id = ActorTable.insertAndGetId {
 *             it[firstName] = record.firstName
 *             it[lastName]  = record.lastName
 *         }
 *         return record.copy(id = id.value)
 *     }
 * }
 *
 * // 4. 트랜잭션 내부에서 사용
 * transaction {
 *     val repo = ActorRepository()
 *     val saved = repo.save(ActorRecord(firstName = "Johnny", lastName = "Depp"))
 *
 *     val found = repo.findById(saved.id)
 *     val all   = repo.findAll(limit = 10) { ActorTable.lastName eq "Depp" }
 *     val page  = repo.findPage(pageNumber = 0, pageSize = 20)
 * }
 * ```
 */
interface JdbcRepository<ID: Any, E: Any> {
    /**
     * 이 저장소가 사용하는 [IdTable]을 반환합니다.
     */
    val table: IdTable<ID>

    /**
     * [entity]에서 기본 키를 추출합니다.
     */
    fun extractId(entity: E): ID

    /**
     * [ResultRow]를 [E] 타입 엔티티로 매핑합니다.
     *
     * @receiver Exposed 쿼리가 반환한 행
     * @return 매핑된 [E] 엔티티
     */
    fun ResultRow.toEntity(): E

    /**
     * [entity] 값을 [saveAll]이 사용하는 배치 삽입 문에 바인딩합니다.
     *
     * 기본 [saveAll]을 사용하려는 저장소 구현체는 이 훅을 재정의하고
     * 삽입에 필요한 기본 키 이외의 모든 컬럼을 할당해야 합니다.
     *
     * ```kotlin
     * override fun BatchInsertStatement.bindSave(entity: ActorRecord) {
     *     this[ActorTable.firstName] = entity.firstName
     *     this[ActorTable.lastName] = entity.lastName
     * }
     * ```
     *
     * @throws UnsupportedOperationException 구현별 바인딩 없이 [saveAll]을 호출한 경우
     */
    fun BatchInsertStatement.bindSave(entity: E): Unit =
        throw UnsupportedOperationException(
            "Override BatchInsertStatement.bindSave(entity) before calling JdbcRepository.saveAll()."
        )

    /**
     * Exposed 배치 삽입으로 [entities]를 저장하고 생성된 ID를 반환합니다.
     *
     * 반환 ID는 Exposed `batchInsert`가 보고한 입력 순서를 유지합니다.
     * 입력이 비어 있으면 아무 작업도 수행하지 않습니다.
     *
     * @param entities 삽입할 엔티티
     * @return 생성된 기본 키 값
     */
    fun saveAll(entities: Iterable<E>): List<ID> {
        val entityList = entities.toList()
        if (entityList.isEmpty()) {
            return emptyList()
        }

        return table
            .batchInsert(entityList) { entity ->
                bindSave(entity)
            }
            .map { row -> row[table.id].value }
    }

    /**
     * 테이블의 전체 행 수를 반환합니다.
     */
    fun count(): Long = table.selectAll().count()

    /**
     * [predicate]와 일치하는 행 수를 반환합니다.
     *
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     */
    fun countBy(predicate: () -> Op<Boolean> = { Op.TRUE }): Long = table.selectAll().where(predicate).count()

    /**
     * [op]와 일치하는 행 수를 반환합니다.
     *
     * @param op 필터 조건
     */
    fun countBy(op: Op<Boolean>): Long = table.selectAll().where(op).count()

    /**
     * 테이블에 행이 없으면 `true`를 반환합니다.
     */
    fun isEmpty(): Boolean = table.selectAll().empty()

    /**
     * 테이블에 행이 하나 이상 있으면 `true`를 반환합니다.
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * 주어진 [query]와 일치하는 행이 하나 이상 있으면 `true`를 반환합니다.
     *
     * @param query 존재 여부를 확인할 서브쿼리
     */
    fun exists(query: AbstractQuery<*>): Boolean {
        val exists =
            org.jetbrains.exposed.v1.core
                .exists(query)
        return table.select(exists).firstOrNull()?.getOrNull(exists) ?: false
    }

    /**
     * 주어진 [id]를 가진 행이 테이블에 존재하면 `true`를 반환합니다.
     *
     * @param id 엔티티 기본 키
     */
    fun existsById(id: ID): Boolean = !table.selectAll().where { table.id eq id }.empty()

    /**
     * [predicate]와 일치하는 행이 하나 이상 있으면 `true`를 반환합니다.
     *
     * @param predicate 필터 조건
     */
    fun existsBy(predicate: () -> Op<Boolean>): Boolean = !table.selectAll().where(predicate).empty()

    /**
     * 주어진 [id]의 엔티티를 반환하며, 존재하지 않으면 예외를 던집니다.
     *
     * @param id 엔티티 기본 키
     * @return 일치하는 [E] 엔티티
     * @throws NoSuchElementException [id]에 해당하는 행이 없는 경우
     * @throws IllegalArgumentException [id]에 해당하는 행이 둘 이상인 경우
     *
     * ## 사용 예
     *
     * ```kotlin
     * transaction {
     *     val actor = repo.findById(1L)             // throws if absent
     *     val actorOrNull = repo.findByIdOrNull(1L) // null if absent
     * }
     * ```
     */
    fun findById(id: ID): E =
        table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()

    /**
     * 주어진 [id]의 엔티티를 반환하며, 존재하지 않으면 `null`을 반환합니다.
     *
     * @param id 엔티티 기본 키
     * @return 일치하는 [E] 엔티티 또는 `null`
     */
    fun findByIdOrNull(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()

    /**
     * [predicate]와 일치하는 모든 엔티티를 반환합니다.
     *
     * @param limit 최대 결과 수. `null`이면 제한하지 않습니다.
     * @param offset 0부터 시작하는 행 오프셋. `null`이면 적용하지 않습니다.
     * @param sortOrder 결과 정렬 순서. 기본값은 [SortOrder.ASC]입니다.
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return 일치하는 엔티티 목록
     */
    fun findAll(
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
        predicate: () -> Op<Boolean> = { Op.TRUE },
    ): List<E> =
        table
            .selectAll()
            .where(predicate)
            .apply {
                limit?.run { limit(limit) }
                offset?.run { offset(offset) }
            }.orderBy(table.id, sortOrder)
            .map { it.toEntity() }

    /**
     * AND로 결합한 모든 [filters]와 일치하는 엔티티를 반환합니다.
     *
     * @param filters AND로 결합할 가변 인자 필터 람다
     * @param limit 최대 결과 수. `null`이면 제한하지 않습니다.
     * @param offset 0부터 시작하는 행 오프셋. `null`이면 적용하지 않습니다.
     * @param sortOrder 결과 정렬 순서. 기본값은 [SortOrder.ASC]입니다.
     * @return 일치하는 엔티티 목록
     */
    fun findWithFilters(
        vararg filters: () -> Op<Boolean>,
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): List<E> {
        val condition: Op<Boolean> =
            filters.fold(Op.TRUE as Op<Boolean>) { acc, filter ->
                acc.and(filter.invoke())
            }
        return findAll(limit, offset, sortOrder) { condition }
    }

    /**
     * AND로 결합한 모든 [filters]와 일치하는 엔티티를 반환합니다.
     *
     * [findWithFilters]의 별칭입니다.
     *
     * @param filters AND로 결합할 가변 인자 필터 람다
     * @param limit 최대 결과 수. `null`이면 제한하지 않습니다.
     * @param offset 0부터 시작하는 행 오프셋. `null`이면 적용하지 않습니다.
     * @param sortOrder 결과 정렬 순서. 기본값은 [SortOrder.ASC]입니다.
     * @return 일치하는 엔티티 목록
     */
    fun findBy(
        vararg filters: () -> Op<Boolean>,
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): List<E> =
        findWithFilters(
            *filters,
            limit = limit,
            offset = offset,
            sortOrder = sortOrder
        )

    /**
     * [predicate]와 일치하는 첫 엔티티를 반환하며, 없으면 `null`을 반환합니다.
     *
     * @param offset 0부터 시작하는 행 오프셋. `null`이면 적용하지 않습니다.
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return 처음 일치한 [E] 엔티티 또는 `null`
     */
    fun findFirstOrNull(
        offset: Long? = null,
        predicate: () -> Op<Boolean> = { Op.TRUE },
    ): E? =
        table
            .selectAll()
            .where(predicate)
            .limit(1)
            .apply {
                offset?.run { offset(offset) }
            }.firstOrNull()
            ?.toEntity()

    /**
     * 기본 키 내림차순으로 정렬했을 때 [predicate]와 일치하는 마지막 엔티티를 반환하며,
     * 없으면 `null`을 반환합니다.
     *
     * @param offset 0부터 시작하는 행 오프셋. `null`이면 적용하지 않습니다.
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return 마지막으로 일치한 [E] 엔티티 또는 `null`
     */
    fun findLastOrNull(
        offset: Long? = null,
        predicate: () -> Op<Boolean> = { Op.TRUE },
    ): E? =
        table
            .selectAll()
            .where(predicate)
            .orderBy(table.id, SortOrder.DESC)
            .limit(1)
            .apply {
                offset?.run { offset(offset) }
            }.firstOrNull()
            ?.toEntity()

    /**
     * [field] 값이 [value]와 같은 모든 엔티티를 반환합니다.
     *
     * @param field 필터링할 컬럼
     * @param value 비교할 값
     * @return 일치하는 엔티티 목록
     */
    fun <V> findByField(
        field: Column<V>,
        value: V,
    ): List<E> =
        table
            .selectAll()
            .where { field eq value }
            .map { it.toEntity() }

    /**
     * [field] 값이 [value]와 같은 첫 엔티티를 반환하며, 없으면 `null`을 반환합니다.
     *
     * @param field 필터링할 컬럼
     * @param value 비교할 값
     * @return 처음 일치한 [E] 엔티티 또는 `null`
     */
    fun <V> findByFieldOrNull(
        field: Column<V>,
        value: V,
    ): E? =
        table
            .selectAll()
            .where { field eq value }
            .firstOrNull()
            ?.toEntity()

    /**
     * 기본 키가 [ids]에 포함된 모든 엔티티를 반환합니다.
     *
     * **참고:** [ids]가 크면 데이터베이스 `IN` 절 제한을 초과할 수 있습니다.
     * 대량 조회 시 큰 ID 목록을 작은 청크로 나누십시오.
     *
     * @param ids 조회할 기본 키 값 모음
     * @return 일치하는 엔티티 목록
     */
    fun findAllByIds(ids: Iterable<ID>): List<E> =
        table
            .selectAll()
            .where { table.id inList ids }
            .map { it.toEntity() }

    /**
     * [entity]를 기본 키로 삭제합니다.
     *
     * @return 삭제한 행 수
     */
    fun delete(entity: E): Int = deleteById(extractId(entity))

    /**
     * 주어진 [id]의 행을 삭제합니다.
     *
     * @param id 엔티티 기본 키
     * @return 삭제한 행 수
     */
    fun deleteById(id: ID): Int = table.deleteWhere { table.id eq id }

    /**
     * [op]와 일치하는 모든 행을 삭제합니다.
     *
     * @param limit 삭제할 최대 행 수. `null`이면 제한하지 않습니다.
     * @param op 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return 삭제한 행 수
     */
    fun deleteAll(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteWhere(limit = limit, op = op)

    /**
     * 제약 조건 위반을 무시하고 [id]의 행을 삭제합니다.
     *
     * @param id 엔티티 기본 키
     * @return 삭제한 행 수
     */
    fun deleteByIdIgnore(id: ID): Int = table.deleteIgnoreWhere { table.id eq id }

    /**
     * 제약 조건 위반을 무시하고 [op]와 일치하는 모든 행을 삭제합니다.
     *
     * @param limit 삭제할 최대 행 수. `null`이면 제한하지 않습니다.
     * @param op 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return 삭제한 행 수
     */
    fun deleteAllIgnore(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteIgnoreWhere(limit, op = op)

    /**
     * 기본 키가 [ids]에 포함된 모든 행을 삭제합니다.
     *
     * **참고:** [ids]가 크면 데이터베이스 `IN` 절 제한을 초과할 수 있습니다.
     * 대량 삭제 시 큰 ID 목록을 작은 청크로 나누십시오.
     *
     * @param ids 삭제할 기본 키 값 모음
     * @return 삭제한 행 수
     */
    fun deleteAllByIds(ids: Iterable<ID>): Int = table.deleteWhere { table.id inList ids }

    /**
     * [updateStatement]로 [id]가 식별하는 행을 갱신합니다.
     *
     * @param id 엔티티 기본 키
     * @param limit 갱신할 최대 행 수. `null`이면 제한하지 않습니다.
     * @param updateStatement 적용할 컬럼 할당
     * @return 갱신한 행 수
     */
    fun updateById(
        id: ID,
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = { table.id eq id }, limit = limit, body = updateStatement)

    /**
     * [updateStatement]로 [predicate]와 일치하는 모든 행을 갱신합니다.
     *
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @param limit 갱신할 최대 행 수. `null`이면 제한하지 않습니다.
     * @param updateStatement 적용할 컬럼 할당
     * @return 갱신한 행 수
     */
    fun updateAll(
        predicate: () -> Op<Boolean> = { Op.TRUE },
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = predicate, limit = limit, body = updateStatement)

    /**
     * 호출자가 제공한 [insertStatement] 람다로 [entities]를 배치 삽입하고
     * [ResultRow.toEntity]로 매핑한 결과 엔티티를 반환합니다.
     *
     * @param entities 삽입할 데이터
     * @param ignore `true`이면 중복 키 위반을 조용히 건너뜁니다.
     * @param shouldReturnGeneratedValues `true`이면 Exposed가 자동 생성 값을 반환합니다.
     * @param insertStatement 각 원소에 적용할 컬럼 할당
     * @return 삽입된 엔티티 목록
     */
    fun <D> batchInsert(
        entities: Iterable<D>,
        ignore: Boolean = false,
        shouldReturnGeneratedValues: Boolean = true,
        insertStatement: BatchInsertStatement.(D) -> Unit,
    ): List<E> =
        table
            .batchInsert(
                data = entities,
                ignore = ignore,
                shouldReturnGeneratedValues = shouldReturnGeneratedValues,
                body = insertStatement
            ).map { it.toEntity() }

    /**
     * 호출자가 제공한 [insertStatement] 람다로 [Sequence]의 [entities]를 배치 삽입하고
     * [ResultRow.toEntity]로 매핑한 결과 엔티티를 반환합니다.
     *
     * @param entities 삽입할 데이터 시퀀스
     * @param ignore `true`이면 중복 키 위반을 조용히 건너뜁니다.
     * @param shouldReturnGeneratedValues `true`이면 Exposed가 자동 생성 값을 반환합니다.
     * @param insertStatement 각 원소에 적용할 컬럼 할당
     * @return 삽입된 엔티티 목록
     */
    fun <D> batchInsert(
        entities: Sequence<D>,
        ignore: Boolean = false,
        shouldReturnGeneratedValues: Boolean = true,
        insertStatement: BatchInsertStatement.(D) -> Unit,
    ): List<E> =
        table
            .batchInsert(
                data = entities,
                ignore = ignore,
                shouldReturnGeneratedValues = shouldReturnGeneratedValues,
                body = insertStatement
            ).map { it.toEntity() }

    /**
     * [entities]를 배치 업서트하고 [ResultRow.toEntity]로 매핑한 결과 엔티티를 반환합니다.
     *
     * 자세한 내용은 [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert)를 참고하십시오.
     *
     * @param entities 업서트할 데이터
     * @param keys 중복 행 판별에 사용할 컬럼. 기본 키를 우선하고, 없으면 첫 번째 고유 인덱스를 사용합니다.
     * @param onUpdate UPDATE 컬럼 할당을 지정할 [UpdateStatement] 수신 람다.
     *   `insertValue()`를 호출하면 해당 컬럼의 INSERT 값을 재사용하며,
     *   `null`이면 모든 컬럼을 INSERT 값으로 갱신합니다.
     * @param onUpdateExclude UPDATE 절에서 제외할 컬럼. `null`이면 모든 컬럼을 갱신합니다.
     * @param shouldReturnGeneratedValues `true`이면 Exposed가 자동 생성 값을 반환합니다.
     * @return 업서트된 엔티티 목록
     */
    fun <D: Any> batchUpsert(
        entities: Iterable<D>,
        vararg keys: Column<*>,
        onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
        onUpdateExclude: List<Column<*>>? = null,
        where: (() -> Op<Boolean>)? = null,
        shouldReturnGeneratedValues: Boolean = true,
        body: BatchUpsertStatement.(D) -> Unit,
    ): List<E> =
        table
            .batchUpsert(
                data = entities,
                keys = keys,
                onUpdate = onUpdate,
                onUpdateExclude = onUpdateExclude,
                where = where,
                shouldReturnGeneratedValues = shouldReturnGeneratedValues,
                body = body
            ).map { it.toEntity() }

    /**
     * [Sequence]의 [entities]를 배치 업서트하고 [ResultRow.toEntity]로 매핑한 결과 엔티티를 반환합니다.
     *
     * 자세한 내용은 [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert)를 참고하십시오.
     *
     * @param entities 업서트할 데이터 시퀀스
     * @param keys 중복 행 판별에 사용할 컬럼. 기본 키를 우선하고, 없으면 첫 번째 고유 인덱스를 사용합니다.
     * @param onUpdate UPDATE 컬럼 할당을 지정할 [UpdateStatement] 수신 람다.
     *   `insertValue()`를 호출하면 해당 컬럼의 INSERT 값을 재사용하며,
     *   `null`이면 모든 컬럼을 INSERT 값으로 갱신합니다.
     * @param onUpdateExclude UPDATE 절에서 제외할 컬럼. `null`이면 모든 컬럼을 갱신합니다.
     * @param shouldReturnGeneratedValues `true`이면 Exposed가 자동 생성 값을 반환합니다.
     * @return 업서트된 엔티티 목록
     */
    fun <D: Any> batchUpsert(
        entities: Sequence<D>,
        vararg keys: Column<*>,
        onUpdate: (UpsertBuilder.(UpdateStatement) -> Unit)? = null,
        onUpdateExclude: List<Column<*>>? = null,
        where: (() -> Op<Boolean>)? = null,
        shouldReturnGeneratedValues: Boolean = true,
        body: BatchUpsertStatement.(D) -> Unit,
    ): List<E> =
        table
            .batchUpsert(
                data = entities,
                keys = keys,
                onUpdate = onUpdate,
                onUpdateExclude = onUpdateExclude,
                where = where,
                shouldReturnGeneratedValues = shouldReturnGeneratedValues,
                body = body
            ).map { it.toEntity() }

    /**
     * [predicate]와 일치하는 엔티티의 페이지 조각을 반환합니다.
     *
     * ## 동작 계약
     * `totalCount`와 `content`는 별도 쿼리로 조회하므로 원자적으로 일관되지 **않습니다**.
     * 두 쿼리 사이에 동시 트랜잭션이 행을 삽입하거나 삭제하면 개수가 달라질 수 있습니다.
     * 엄격한 일관성이 필요하면 `SERIALIZABLE` 격리 수준을 사용하십시오.
     *
     * @param pageNumber 0부터 시작하는 페이지 인덱스(0 이상)
     * @param pageSize 페이지당 행 수(0보다 커야 함)
     * @param sortOrder 결과에 적용할 정렬 순서. 기본값은 [SortOrder.ASC]입니다.
     * @param predicate 필터 조건. 기본값은 `Op.TRUE`(모든 행)입니다.
     * @return `content`, `totalCount`, `pageNumber`, `pageSize`, `totalPages`를 담은 [ExposedPage]
     *
     * ## 사용 예
     *
     * ```kotlin
     * transaction {
     *     val page = repo.findPage(
     *         pageNumber = 0,
     *         pageSize   = 20,
     *     ) { ActorTable.lastName eq "Depp" }
     *
     *     println(page.content)    // 현재 페이지의 일치 엔티티
     *     println(page.totalCount) // 조건과 일치하는 전체 행 수
     *     println(page.totalPages) // 전체 페이지 수
     *     println(page.pageNumber) // 현재 페이지 인덱스
     * }
     * ```
     */
    fun findPage(
        pageNumber: Int,
        pageSize: Int,
        sortOrder: SortOrder = SortOrder.ASC,
        predicate: () -> Op<Boolean> = { Op.TRUE },
    ): ExposedPage<E> {
        pageNumber.requireGe(0, "pageNumber")
        pageSize.requirePositiveNumber("pageSize")
        val totalCount = countBy(predicate)
        val content =
            findAll(
                limit = pageSize,
                offset = (pageNumber.toLong() * pageSize),
                sortOrder = sortOrder,
                predicate = predicate
            )
        return ExposedPage(
            content = content,
            totalCount = totalCount,
            pageNumber = pageNumber,
            pageSize = pageSize
        )
    }
}

/**
 * [Int] 기본 키를 사용하는 [JdbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface IntJdbcRepository<E: Any>: JdbcRepository<Int, E>

/**
 * [Long] 기본 키를 사용하는 [JdbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 *
 * ## 사용 예
 *
 * ```kotlin
 * object ActorTable : LongIdTable("actors") {
 *     val firstName = varchar("first_name", 50)
 * }
 *
 * class ActorRepository : LongJdbcRepository<ActorRecord> {
 *     override val table = ActorTable
 *     override fun ResultRow.toEntity() = ActorRecord(
 *         id        = this[ActorTable.id].value,
 *         firstName = this[ActorTable.firstName],
 *     )
 * }
 * ```
 */
interface LongJdbcRepository<E: Any>: JdbcRepository<Long, E>

/**
 * Kotlin [Uuid] 기본 키를 사용하는 [JdbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
@OptIn(ExperimentalUuidApi::class)
interface UuidJdbcRepository<E: Any>: JdbcRepository<Uuid, E>

/**
 * [java.util.UUID] 기본 키를 사용하는 [JdbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface UUIDJdbcRepository<E: Any>: JdbcRepository<UUID, E>

/**
 * [String] 기본 키를 사용하는 [JdbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface StringJdbcRepository<E: Any>: JdbcRepository<String, E>
