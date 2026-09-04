package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.BatchUpsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertBuilder
import org.jetbrains.exposed.v1.core.vendors.SQLiteDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteIgnoreWhere
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Exposed 1.5.0의 행 수 추정 한도를 INSERT 전에 확인합니다.
 * 실행 경계별 모듈 독립성을 유지하기 위해 JDBC/R2DBC에서 같은 작은 검증을 사용합니다.
 */
private fun <D> multiRowValuesData(iterator: Iterator<D>, table: Table): List<D> {
    if (!iterator.hasNext()) return emptyList()
    val columns = table.columns.size
    val parameterLimit = if (currentDialect is SQLiteDialect) 32_766 else 65_535
    val rows = iterator.asSequence().take(parameterLimit / columns.coerceAtLeast(1) + 1).toList()
    (rows.size.toLong() * columns.toLong()).requireLe(parameterLimit.toLong()) {
        "Multi-row VALUES limit exceeded: rows=${rows.size}, columns=$columns, parameterLimit=$parameterLimit"
    }
    return rows
}

/**
 * [IdTable]을 기반으로 Exposed R2DBC를 사용하는 기본 저장소 인터페이스입니다.
 *
 * 기본 키 타입 [ID]로 식별되는 [E] 엔티티의 조회, 저장, 삭제를 위한 공통 CRUD 연산을 제공합니다.
 * 단일 행 조회와 쓰기는 일시 중단 함수이며, 다중 행 조회는 [kotlinx.coroutines.flow.Flow]를 반환합니다.
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
 * }
 *
 * // 2. 레코드 DTO
 * data class ActorRecord(
 *     val id: Long = 0L,
 *     val firstName: String,
 *     val lastName: String,
 * )
 *
 * // 3. 저장소 구현
 * class ActorRepository : LongR2dbcRepository<ActorRecord> {
 *     override val table = ActorTable
 *
 *     override suspend fun ResultRow.toEntity() = ActorRecord(
 *         id        = this[ActorTable.id].value,
 *         firstName = this[ActorTable.firstName],
 *         lastName  = this[ActorTable.lastName],
 *     )
 *
 *     suspend fun save(record: ActorRecord): ActorRecord {
 *         val id = ActorTable.insertAndGetId {
 *             it[firstName] = record.firstName
 *             it[lastName]  = record.lastName
 *         }
 *         return record.copy(id = id.value)
 *     }
 * }
 *
 * // 4. 일시 중단 트랜잭션 내부에서 사용
 * suspendTransaction {
 *     val repo = ActorRepository()
 *     val saved = repo.save(ActorRecord(firstName = "Johnny", lastName = "Depp"))
 *
 *     val found = repo.findById(saved.id)
 *     val all   = repo.findAll(limit = 10) { ActorTable.lastName eq "Depp" }.toList()
 *     val page  = repo.findPage(pageNumber = 0, pageSize = 20)
 * }
 * ```
 */
interface R2dbcRepository<ID: Any, E: Any> {
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
     */
    suspend fun ResultRow.toEntity(): E

    /**
     * [entity] 값을 [saveAll]이 사용하는 배치 삽입 문에 바인딩합니다.
     *
     * 기본 [saveAll]을 사용하는 저장소 구현체는 이 훅을 재정의하고
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
            "Override BatchInsertStatement.bindSave(entity) before calling R2dbcRepository.saveAll()."
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
    suspend fun saveAll(entities: Iterable<E>): List<ID> {
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
     * 전체 엔티티 수를 반환합니다.
     */
    suspend fun count(): Long = table.selectAll().count()

    /**
     * [predicate]와 일치하는 엔티티 수를 반환합니다.
     * @param predicate 필터 조건을 반환하는 함수
     */
    suspend fun countBy(predicate: () -> Op<Boolean> = { Op.TRUE }): Long = table.selectAll().where(predicate).count()

    /**
     * [op]와 일치하는 엔티티 수를 반환합니다.
     * @param op 필터 조건
     */
    suspend fun countBy(op: Op<Boolean>): Long = table.selectAll().where(op).count()

    /**
     * 테이블에 행이 없는지 반환합니다.
     */
    suspend fun isEmpty(): Boolean = table.selectAll().empty()

    /**
     * 테이블에 행이 하나 이상 있는지 반환합니다.
     */
    suspend fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * [query]가 행을 하나 이상 생성하는지 반환합니다.
     * @param query 존재 여부를 확인할 [AbstractQuery]
     */
    suspend fun exists(query: AbstractQuery<*>): Boolean {
        val exists =
            org.jetbrains.exposed.v1.core
                .exists(query)
        return table.select(exists).firstOrNull()?.getOrNull(exists) ?: false
    }

    /**
     * [id]에 해당하는 엔티티가 존재하는지 반환합니다.
     * @param id 엔티티 기본 키
     */
    suspend fun existsById(id: ID): Boolean = !table.selectAll().where { table.id eq id }.empty()

    /**
     * [predicate]와 일치하는 엔티티가 있는지 반환합니다.
     * @param predicate 필터 조건
     */
    suspend fun existsBy(predicate: () -> Op<Boolean>): Boolean = !table.selectAll().where(predicate).empty()

    /**
     * [id]로 엔티티를 조회하며, 존재하지 않으면 예외를 던집니다.
     * @param id 엔티티 기본 키
     */
    suspend fun findById(id: ID): E =
        table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()

    /**
     * [id]로 엔티티를 조회하며, 존재하지 않으면 `null`을 반환합니다.
     * @param id 엔티티 기본 키
     */
    suspend fun findByIdOrNull(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()

    /**
     * [predicate]와 일치하는 모든 엔티티를 조회합니다.
     * @param limit 조회할 최대 행 수
     * @param offset 0부터 시작하는 행 오프셋
     * @param sortOrder 정렬 방향
     * @param predicate 필터 조건
     */
    fun findAll(
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
        predicate: () -> Op<Boolean> = { Op.TRUE },
    ): Flow<E> =
        table
            .selectAll()
            .where(predicate)
            .apply {
                limit?.run { limit(limit) }
                offset?.run { offset(offset) }
            }.orderBy(table.id, sortOrder)
            .map { it.toEntity() }

    /**
     * [filters]를 논리 AND로 결합하여 엔티티를 조회합니다.
     * @param filters 필터 조건 함수
     * @param limit 조회할 최대 행 수
     * @param offset 0부터 시작하는 행 오프셋
     * @param sortOrder 정렬 방향
     */
    fun findWithFilters(
        vararg filters: () -> Op<Boolean>,
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): Flow<E> {
        val condition: Op<Boolean> =
            filters.fold(Op.TRUE as Op<Boolean>) { acc, filter ->
                acc.and(filter.invoke())
            }
        return findAll(limit, offset, sortOrder) { condition }
    }

    /**
     * [filters]를 논리 AND로 결합하여 엔티티를 조회합니다. [findWithFilters]의 별칭입니다.
     * @param filters 필터 조건 함수
     * @param limit 조회할 최대 행 수
     * @param offset 0부터 시작하는 행 오프셋
     * @param sortOrder 정렬 방향
     */
    fun findBy(
        vararg filters: () -> Op<Boolean>,
        limit: Int? = null,
        offset: Long? = null,
        sortOrder: SortOrder = SortOrder.ASC,
    ): Flow<E> =
        findWithFilters(
            *filters,
            limit = limit,
            offset = offset,
            sortOrder = sortOrder
        )

    /**
     * [predicate]와 일치하는 첫 엔티티를 조회합니다.
     * @param offset 0부터 시작하는 행 오프셋
     * @param predicate 필터 조건
     */
    suspend fun findFirstOrNull(
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
     * [predicate]와 일치하는 마지막 엔티티를 조회합니다.
     * @param offset 0부터 시작하는 행 오프셋
     * @param predicate 필터 조건
     */
    suspend fun findLastOrNull(
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
     * [field] 값이 [value]와 같은 엔티티를 조회합니다.
     * @param field 비교할 컬럼
     * @param value 예상 컬럼 값
     */
    fun <V> findByField(
        field: Column<V>,
        value: V,
    ): Flow<E> =
        table
            .selectAll()
            .where { field eq value }
            .map { it.toEntity() }

    /**
     * [field] 값이 [value]와 같은 첫 엔티티를 조회하며, 없으면 `null`을 반환합니다.
     * @param field 비교할 컬럼
     * @param value 예상 컬럼 값
     */
    suspend fun <V> findByFieldOrNull(
        field: Column<V>,
        value: V,
    ): E? =
        table
            .selectAll()
            .where { field eq value }
            .firstOrNull()
            ?.toEntity()

    /**
     * ID 모음으로 엔티티를 조회합니다.
     *
     * **참고:** 매우 큰 `ids` 모음은 데이터베이스별 `IN` 절 제한을 초과할 수 있습니다.
     * 호출 전에 큰 ID 모음을 청크로 나누십시오.
     *
     * @param ids 조회할 엔티티 기본 키
     */
    fun findAllByIds(ids: Iterable<ID>): Flow<E> =
        table
            .selectAll()
            .where { table.id inList ids }
            .map { it.toEntity() }

    /**
     * [entity]를 삭제합니다.
     */
    suspend fun delete(entity: E): Int = deleteById(extractId(entity))

    /**
     * [id]로 엔티티를 삭제합니다.
     * @param id 삭제할 엔티티 기본 키
     */
    suspend fun deleteById(id: ID): Int = table.deleteWhere { table.id eq id }

    /**
     * [op]와 일치하는 모든 엔티티를 삭제합니다.
     * @param limit 삭제할 최대 행 수
     * @param op 필터 조건
     */
    suspend fun deleteAll(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteWhere(limit = limit, op = op)

    /**
     * 존재하지 않는 행을 무시하고 [id]로 엔티티를 삭제합니다.
     * @param id 삭제할 엔티티 기본 키
     */
    suspend fun deleteByIdIgnore(id: ID): Int = table.deleteIgnoreWhere { table.id eq id }

    /**
     * 존재하지 않는 행을 무시하고 [op]와 일치하는 모든 엔티티를 삭제합니다.
     * @param limit 삭제할 최대 행 수
     * @param op 필터 조건
     */
    suspend fun deleteAllIgnore(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteIgnoreWhere(limit, op = op)

    /**
     * ID 모음으로 엔티티를 삭제합니다.
     *
     * **참고:** 매우 큰 `ids` 모음은 데이터베이스별 `IN` 절 제한을 초과할 수 있습니다.
     * 호출 전에 큰 ID 모음을 청크로 나누십시오.
     *
     * @param ids 삭제할 엔티티 기본 키
     */
    suspend fun deleteAllByIds(ids: Iterable<ID>): Int = table.deleteWhere { table.id inList ids }

    /**
     * [id]로 엔티티를 갱신합니다.
     * @param id 갱신할 엔티티 기본 키
     * @param limit 갱신할 최대 행 수
     * @param updateStatement 갱신 본문
     */
    suspend fun updateById(
        id: ID,
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int =
        table.update(
            where = { table.id eq id },
            limit = limit,
            body = updateStatement
        )

    /**
     * [predicate]와 일치하는 모든 엔티티를 갱신합니다.
     * @param predicate 필터 조건
     * @param limit 갱신할 최대 행 수
     * @param updateStatement 갱신 본문
     */
    suspend fun updateAll(
        predicate: () -> Op<Boolean> = { Op.TRUE },
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = predicate, limit = limit, body = updateStatement)

    /**
     * Exposed 배치 삽입으로 [entities]를 삽입합니다.
     * @param entities 삽입할 엔티티
     * @param ignore 중복 행을 무시할지 여부
     * @param shouldReturnGeneratedValues 생성된 값을 반환할지 여부
     * @param insertStatement 삽입 본문
     */
    suspend fun <D> batchInsert(
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
     * Exposed 배치 삽입으로 [entities]를 삽입합니다.
     * @param entities 삽입할 엔티티 시퀀스
     * @param ignore 중복 행을 무시할지 여부
     * @param shouldReturnGeneratedValues 생성된 값을 반환할지 여부
     * @param insertStatement 삽입 본문
     */
    suspend fun <D> batchInsert(
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
     * [useMultiRowValues]로 multi-row VALUES를 명시적으로 선택하여 삽입합니다.
     *
     * `false`는 기존 batchInsert에 위임합니다. `true`는 입력을 허용 행 수 + 1개까지만
     * 수집하고, 행 수 × 전체 컬럼 수가 Exposed 한도(일반 65,535, SQLite 32,766)를
     * 넘으면 바인더·INSERT 실행 전에 거부합니다. 이 값은 실제 bind 수의 추정치이며,
     * 여러 bind를 만드는 표현식과 더 작은 driver 한도는 호출자가 청크를 줄여 관리해야 합니다.
     *
     * 기존 외부 트랜잭션의 선행 쓰기는 취소하지 않습니다. SQL 오류는 그대로 전파하므로
     * 호출자가 rollback해야 합니다. 입력 수집은 O(허용 행 수)의 참조 메모리를 사용합니다.
     * `true`와 `ignore=true` 조합은 빈 입력도 순회 전에 거부합니다. 충돌 무시는 기존
     * `false` 경로를 사용하십시오. 생성 ID가 필요한 MySQL/Oracle 등
     * 미검증 조합은 `false`를 사용하십시오. 생성 값 요청을 끄면 mapper가 해당 값을 요구하면 안 됩니다.
     *
     * @param entities 삽입할 데이터
     * @param ignore 방언이 지원하는 중복 무시 옵션. 기본값은 false입니다.
     * @param shouldReturnGeneratedValues 생성 값 요청 여부. 기본값은 true입니다.
     * @param useMultiRowValues multi-row VALUES 사용 여부. 생략한 기존 호출은 false 경로입니다.
     * @param insertStatement 각 데이터의 컬럼 할당. 외부 부작용을 두지 마십시오.
     * @return Exposed가 반환한 행을 매핑한 엔티티. 중복 무시 시 입력보다 적을 수 있습니다.
     * @throws IllegalArgumentException multi-row와 ignore를 함께 요청하거나 행 수 추정 한도를 초과한 경우
     */
    suspend fun <D> batchInsert(
        entities: Iterable<D>,
        ignore: Boolean = false,
        shouldReturnGeneratedValues: Boolean = true,
        useMultiRowValues: Boolean,
        insertStatement: BatchInsertStatement.(D) -> Unit,
    ): List<E> {
        if (!useMultiRowValues) {
            return batchInsert(entities, ignore, shouldReturnGeneratedValues, insertStatement)
        }
        require(!ignore) { "useMultiRowValues=true cannot be combined with ignore=true; use the legacy batch path" }
        val rows = multiRowValuesData(entities.iterator(), table)
        return if (rows.isEmpty()) emptyList() else table.batchInsert(
            data = rows,
            useMultiRowValues = true,
            ignore = ignore,
            shouldReturnGeneratedValues = shouldReturnGeneratedValues,
            body = insertStatement,
        ).map { it.toEntity() }
    }

    /**
     * [useMultiRowValues]로 multi-row VALUES를 명시적으로 선택하여 삽입합니다.
     *
     * `false`는 기존 batchInsert에 위임합니다. `true`는 입력을 허용 행 수 + 1개까지만
     * 수집하고, 행 수 × 전체 컬럼 수가 Exposed 한도(일반 65,535, SQLite 32,766)를
     * 넘으면 바인더·INSERT 실행 전에 거부합니다. 이 값은 실제 bind 수의 추정치이며,
     * 여러 bind를 만드는 표현식과 더 작은 driver 한도는 호출자가 청크를 줄여 관리해야 합니다.
     *
     * 기존 외부 트랜잭션의 선행 쓰기는 취소하지 않습니다. SQL 오류는 그대로 전파하므로
     * 호출자가 rollback해야 합니다. 입력 수집은 O(허용 행 수)의 참조 메모리를 사용합니다.
     * `true`와 `ignore=true` 조합은 빈 입력도 순회 전에 거부합니다. 충돌 무시는 기존
     * `false` 경로를 사용하십시오. 생성 ID가 필요한 MySQL/Oracle 등
     * 미검증 조합은 `false`를 사용하십시오. 생성 값 요청을 끄면 mapper가 해당 값을 요구하면 안 됩니다.
     *
     * @param entities 삽입할 1회 순회 시퀀스
     * @param ignore 방언이 지원하는 중복 무시 옵션. 기본값은 false입니다.
     * @param shouldReturnGeneratedValues 생성 값 요청 여부. 기본값은 true입니다.
     * @param useMultiRowValues multi-row VALUES 사용 여부. 생략한 기존 호출은 false 경로입니다.
     * @param insertStatement 각 데이터의 컬럼 할당. 외부 부작용을 두지 마십시오.
     * @return Exposed가 반환한 행을 매핑한 엔티티. 중복 무시 시 입력보다 적을 수 있습니다.
     * @throws IllegalArgumentException multi-row와 ignore를 함께 요청하거나 행 수 추정 한도를 초과한 경우
     */
    suspend fun <D> batchInsert(
        entities: Sequence<D>,
        ignore: Boolean = false,
        shouldReturnGeneratedValues: Boolean = true,
        useMultiRowValues: Boolean,
        insertStatement: BatchInsertStatement.(D) -> Unit,
    ): List<E> {
        if (!useMultiRowValues) {
            return batchInsert(entities, ignore, shouldReturnGeneratedValues, insertStatement)
        }
        require(!ignore) { "useMultiRowValues=true cannot be combined with ignore=true; use the legacy batch path" }
        val rows = multiRowValuesData(entities.iterator(), table)
        return if (rows.isEmpty()) emptyList() else table.batchInsert(
            data = rows,
            useMultiRowValues = true,
            ignore = ignore,
            shouldReturnGeneratedValues = shouldReturnGeneratedValues,
            body = insertStatement,
        ).map { it.toEntity() }
    }

    /**
     * Exposed 배치 업서트로 [entities]를 업서트합니다.
     *
     * 자세한 내용은 [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert)를 참고하십시오.
     *
     * @param entities 업서트할 엔티티
     * @param keys 고유 제약 조건 일치를 판별할 선택적 컬럼. 비어 있으면 기본 키를 사용하고,
     *   기본 키도 없으면 첫 번째 고유 인덱스를 사용합니다.
     * @param onUpdate [UpdateStatement]를 인자로 받아 UPDATE 절에 값을 할당하는 람다.
     *   표현식이나 함수에서 삽입 값을 재사용하려면 대상 컬럼으로 `insertValue()`를 호출합니다.
     *   `null`이면 삽입에 제공한 값으로 모든 컬럼을 갱신합니다.
     * @param onUpdateExclude 갱신에서 제외할 컬럼. `null`이면 모든 컬럼을 갱신합니다.
     * @param shouldReturnGeneratedValues 자동 증가 ID 등 새로 생성된 값을 반환할지 여부
     * @return 업서트 연산이 반환한 행
     */
    suspend fun <D: Any> batchUpsert(
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
     * Exposed 배치 업서트로 [entities]를 업서트합니다.
     *
     * 자세한 내용은 [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert)를 참고하십시오.
     *
     * @param entities 업서트할 엔티티 시퀀스
     * @param keys 고유 제약 조건 일치를 판별할 선택적 컬럼. 비어 있으면 기본 키를 사용하고,
     *   기본 키도 없으면 첫 번째 고유 인덱스를 사용합니다.
     * @param onUpdate [UpdateStatement]를 인자로 받아 UPDATE 절에 값을 할당하는 람다.
     *   표현식이나 함수에서 삽입 값을 재사용하려면 대상 컬럼으로 `insertValue()`를 호출합니다.
     *   `null`이면 삽입에 제공한 값으로 모든 컬럼을 갱신합니다.
     * @param onUpdateExclude 갱신에서 제외할 컬럼. `null`이면 모든 컬럼을 갱신합니다.
     * @param shouldReturnGeneratedValues 자동 증가 ID 등 새로 생성된 값을 반환할지 여부
     * @return 업서트 연산이 반환한 행
     */
    suspend fun <D: Any> batchUpsert(
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
     * 엔티티 페이지를 조회합니다.
     *
     * **참고:** `totalCount`와 `content`는 별도 쿼리로 조회하므로 원자적으로 일관되지 않습니다.
     * 두 쿼리 사이에 다른 트랜잭션이 행을 삽입하거나 삭제하면 값이 달라질 수 있습니다.
     * 엄격한 일관성이 필요하면 더 강한 격리 수준을 사용하십시오.
     *
     * @param pageNumber 0부터 시작하는 페이지 번호
     * @param pageSize 페이지 크기
     * @param sortOrder 정렬 방향
     * @param predicate 필터 조건
     * @return 페이지 결과 [ExposedPage]
     */
    suspend fun findPage(
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
            ).toList()
        return ExposedPage(
            content = content,
            totalCount = totalCount,
            pageNumber = pageNumber,
            pageSize = pageSize
        )
    }
}

/**
 * [Int] 기본 키를 사용하는 [R2dbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface IntR2dbcRepository<E: Any>: R2dbcRepository<Int, E>

/**
 * [Long] 기본 키를 사용하는 [R2dbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface LongR2dbcRepository<E: Any>: R2dbcRepository<Long, E>

/**
 * Kotlin [kotlin.uuid.Uuid] 기본 키를 사용하는 [R2dbcRepository] 편의 특수화 인터페이스입니다.
 *
 * JVM class 이름이 Java UUID 특수화와 충돌하지 않도록 2.0부터 `KotlinUuid` 접두사를
 * 사용합니다. 기존 `UuidR2dbcRepository` 이름은 소스 호환을 위한 typealias로만
 * 유지되며, binary class는 생성하지 않습니다.
 *
 * @param E 엔티티 타입
 */
@OptIn(ExperimentalUuidApi::class)
interface KotlinUuidR2dbcRepository<E: Any>: R2dbcRepository<Uuid, E>

/**
 * [java.util.UUID] 기본 키를 사용하는 [R2dbcRepository] 편의 특수화 인터페이스입니다.
 *
 * JVM class 이름이 Kotlin UUID 특수화와 충돌하지 않도록 2.0부터 `JavaUuid` 접두사를
 * 사용합니다. 기존 `UUIDR2dbcRepository` 이름은 소스 호환을 위한 typealias로만
 * 유지되며, binary class는 생성하지 않습니다.
 *
 * @param E 엔티티 타입
 */
interface JavaUuidR2dbcRepository<E: Any>: R2dbcRepository<UUID, E>

/**
 * 1.x 소스 호환을 위한 Kotlin [kotlin.uuid.Uuid] repository 이름입니다.
 *
 * 2.0부터는 [KotlinUuidR2dbcRepository]를 사용하십시오. 이 typealias는 JVM class를
 * 생성하지 않으므로 기존 binary consumer는 새 이름으로 다시 컴파일해야 합니다.
 */
@Deprecated(
    message = "Use KotlinUuidR2dbcRepository instead.",
    replaceWith = ReplaceWith("KotlinUuidR2dbcRepository<E>"),
)
@OptIn(ExperimentalUuidApi::class)
typealias UuidR2dbcRepository<E> = KotlinUuidR2dbcRepository<E>

/**
 * 1.x 소스 호환을 위한 [java.util.UUID] repository 이름입니다.
 *
 * 2.0부터는 [JavaUuidR2dbcRepository]를 사용하십시오. 이 typealias는 JVM class를
 * 생성하지 않으므로 기존 binary consumer는 새 이름으로 다시 컴파일해야 합니다.
 */
@Deprecated(
    message = "Use JavaUuidR2dbcRepository instead.",
    replaceWith = ReplaceWith("JavaUuidR2dbcRepository<E>"),
)
typealias UUIDR2dbcRepository<E> = JavaUuidR2dbcRepository<E>

/**
 * [String] 기본 키를 사용하는 [R2dbcRepository] 편의 특수화 인터페이스입니다.
 *
 * @param E 엔티티 타입
 */
interface StringR2dbcRepository<E: Any>: R2dbcRepository<String, E>
