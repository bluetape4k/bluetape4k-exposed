package io.bluetape4k.exposed.r2dbc.repository

import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.support.requireGe
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.core.statements.BatchUpsertStatement
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertBuilder
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
 * Base repository interface for Exposed R2DBC backed by an [IdTable].
 *
 * Provides common CRUD operations for reading, persisting, and deleting
 * entities of type [E] identified by primary key type [ID]. Single-row read
 * and write operations are suspending functions, while multi-row reads return
 * [kotlinx.coroutines.flow.Flow].
 *
 * @param ID primary key type (e.g. [Long], [Int], [java.util.UUID])
 * @param E entity (record) type mapped from a [ResultRow]
 *
 * ## Usage example
 *
 * ```kotlin
 * // 1. Table definition
 * object ActorTable : LongIdTable("actors") {
 *     val firstName = varchar("first_name", 50)
 *     val lastName  = varchar("last_name",  50)
 * }
 *
 * // 2. Record DTO
 * data class ActorRecord(
 *     val id: Long = 0L,
 *     val firstName: String,
 *     val lastName: String,
 * )
 *
 * // 3. Repository implementation
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
 * // 4. Usage inside a suspend transaction
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
     * Returns the [IdTable] that backs this repository.
     */
    val table: IdTable<ID>

    /**
     * Extracts the primary key from [entity].
     */
    fun extractId(entity: E): ID

    /**
     * Maps a [ResultRow] to an entity of type [E].
     */
    suspend fun ResultRow.toEntity(): E

    /**
     * Binds [entity] values to a batch insert statement used by [saveAll].
     *
     * Repository implementations that want to use the default [saveAll] should
     * override this hook and assign all non-id columns required for insertion.
     *
     * ```kotlin
     * override fun BatchInsertStatement.bindSave(entity: ActorRecord) {
     *     this[ActorTable.firstName] = entity.firstName
     *     this[ActorTable.lastName] = entity.lastName
     * }
     * ```
     *
     * @throws UnsupportedOperationException when [saveAll] is called without an
     *         implementation-specific binding.
     */
    fun BatchInsertStatement.bindSave(entity: E): Unit =
        throw UnsupportedOperationException(
            "Override BatchInsertStatement.bindSave(entity) before calling R2dbcRepository.saveAll()."
        )

    /**
     * Persists [entities] with Exposed batch insert and returns generated IDs.
     *
     * The returned IDs preserve the input order reported by Exposed
     * `batchInsert`. Empty input is a no-op.
     *
     * @param entities entities to insert
     * @return generated primary key values
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
     * Returns the total number of entities.
     */
    suspend fun count(): Long = table.selectAll().count()

    /**
     * Returns the number of entities that match [predicate].
     * @param predicate function that returns the filter condition
     */
    suspend fun countBy(predicate: () -> Op<Boolean> = { Op.TRUE }): Long = table.selectAll().where(predicate).count()

    /**
     * Returns the number of entities that match [op].
     * @param op filter condition
     */
    suspend fun countBy(op: Op<Boolean>): Long = table.selectAll().where(op).count()

    /**
     * Returns whether the table contains no rows.
     */
    suspend fun isEmpty(): Boolean = table.selectAll().empty()

    /**
     * Returns whether the table contains at least one row.
     */
    suspend fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Returns whether [query] produces at least one row.
     * @param query AbstractQuery
     */
    suspend fun exists(query: AbstractQuery<*>): Boolean {
        val exists =
            org.jetbrains.exposed.v1.core
                .exists(query)
        return table.select(exists).firstOrNull()?.getOrNull(exists) ?: false
    }

    /**
     * Returns whether an entity exists for [id].
     * @param id entity primary key
     */
    suspend fun existsById(id: ID): Boolean = !table.selectAll().where { table.id eq id }.empty()

    /**
     * Returns whether any entity matches [predicate].
     * @param predicate filter condition
     */
    suspend fun existsBy(predicate: () -> Op<Boolean>): Boolean = !table.selectAll().where(predicate).empty()

    /**
     * Finds an entity by [id] or throws when it does not exist.
     * @param id entity primary key
     */
    suspend fun findById(id: ID): E =
        table
            .selectAll()
            .where { table.id eq id }
            .single()
            .toEntity()

    /**
     * Finds an entity by [id], returning null when it does not exist.
     * @param id entity primary key
     */
    suspend fun findByIdOrNull(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()

    /**
     * Finds all entities that match [predicate].
     * @param limit maximum number of rows to read
     * @param offset zero-based row offset
     * @param sortOrder sort direction
     * @param predicate filter condition
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
     * Finds entities by combining [filters] with logical AND.
     * @param filters filter condition functions
     * @param limit maximum number of rows to read
     * @param offset zero-based row offset
     * @param sortOrder sort direction
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
     * Finds entities by combining [filters] with logical AND. Alias for [findWithFilters].
     * @param filters filter condition functions
     * @param limit maximum number of rows to read
     * @param offset zero-based row offset
     * @param sortOrder sort direction
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
     * Finds the first entity that matches [predicate].
     * @param offset zero-based row offset
     * @param predicate filter condition
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
     * Finds the last entity that matches [predicate].
     * @param offset zero-based row offset
     * @param predicate filter condition
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
     * Finds entities whose [field] equals [value].
     * @param field column to compare
     * @param value expected column value
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
     * Finds the first entity whose [field] equals [value], returning null when none exists.
     * @param field column to compare
     * @param value expected column value
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
     * Finds entities by a collection of IDs.
     *
     * **Note**: very large `ids` collections can exceed database-specific `IN`
     * clause limits. Split large ID collections into chunks before calling.
     *
     * @param ids entity primary keys to read
     */
    fun findAllByIds(ids: Iterable<ID>): Flow<E> =
        table
            .selectAll()
            .where { table.id inList ids }
            .map { it.toEntity() }

    /**
     * Deletes [entity].
     */
    suspend fun delete(entity: E): Int = deleteById(extractId(entity))

    /**
     * Deletes an entity by [id].
     * @param id entity primary key to delete
     */
    suspend fun deleteById(id: ID): Int = table.deleteWhere { table.id eq id }

    /**
     * Deletes all entities that match [op].
     * @param limit maximum number of rows to delete
     * @param op filter condition
     */
    suspend fun deleteAll(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteWhere(limit = limit, op = op)

    /**
     * Deletes an entity by [id], ignoring missing rows.
     * @param id entity primary key to delete
     */
    suspend fun deleteByIdIgnore(id: ID): Int = table.deleteIgnoreWhere { table.id eq id }

    /**
     * Deletes all entities that match [op], ignoring missing rows.
     * @param limit maximum number of rows to delete
     * @param op filter condition
     */
    suspend fun deleteAllIgnore(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteIgnoreWhere(limit, op = op)

    /**
     * Deletes entities by a collection of IDs.
     *
     * **Note**: very large `ids` collections can exceed database-specific `IN`
     * clause limits. Split large ID collections into chunks before calling.
     *
     * @param ids entity primary keys to delete
     */
    suspend fun deleteAllByIds(ids: Iterable<ID>): Int = table.deleteWhere { table.id inList ids }

    /**
     * Updates an entity by [id].
     * @param id entity primary key to update
     * @param limit maximum number of rows to update
     * @param updateStatement update body
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
     * Updates all entities that match [predicate].
     * @param predicate filter condition
     * @param limit maximum number of rows to update
     * @param updateStatement update body
     */
    suspend fun updateAll(
        predicate: () -> Op<Boolean> = { Op.TRUE },
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = predicate, limit = limit, body = updateStatement)

    /**
     * Inserts [entities] with Exposed batch insert.
     * @param entities entities to insert
     * @param ignore whether to ignore duplicate rows
     * @param shouldReturnGeneratedValues whether generated values should be returned
     * @param insertStatement insert body
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
     * Inserts [entities] with Exposed batch insert.
     * @param entities entity sequence to insert
     * @param ignore whether to ignore duplicate rows
     * @param shouldReturnGeneratedValues whether generated values should be returned
     * @param insertStatement insert body
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
     * Upserts [entities] with Exposed batch upsert.
     *
     * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
     *
     * @param entities entities to upsert
     * @param keys (optional) Columns to include in the condition that determines a unique constraint match. If no columns are provided,
     *             primary keys will be used. If the table does not have any primary keys, the first unique index will be attempted.
     * @param onUpdate Lambda block with an [UpdateStatement] as its argument, allowing values to be assigned to the UPDATE clause.
     *  To specify manually that the insert value should be used when updating a column, for example within an expression
     *  or function, invoke `insertValue()` with the desired column as the function argument.
     *  If left null, all columns will be updated with the values provided for the insert.
     * @param onUpdateExclude List of specific columns to exclude from updating. If left null, all columns will be updated with the values provided for the insert.
     * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
     * @return rows returned from the upsert operation
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
     * Upserts [entities] with Exposed batch upsert.
     *
     * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for more details.
     *
     * @param entities entity sequence to upsert
     * @param keys (optional) Columns to include in the condition that determines a unique constraint match. If no columns are provided,
     *             primary keys will be used. If the table does not have any primary keys, the first unique index will be attempted.
     * @param onUpdate Lambda block with an [UpdateStatement] as its argument, allowing values to be assigned to the UPDATE clause.
     *  To specify manually that the insert value should be used when updating a column, for example within an expression
     *  or function, invoke `insertValue()` with the desired column as the function argument.
     *  If left null, all columns will be updated with the values provided for the insert.
     * @param onUpdateExclude List of specific columns to exclude from updating. If left null, all columns will be updated with the values provided for the insert.
     * @param shouldReturnGeneratedValues Specifies whether newly generated values (for example, auto-incremented IDs) should be returned.
     * @return rows returned from the upsert operation
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
     * Finds a page of entities.
     *
     * **Note**: `totalCount` and `content` are fetched by separate queries and
     * are not atomically consistent. If another transaction inserts or deletes
     * rows between the queries, the values may diverge. Use a stronger
     * isolation level when strict consistency is required.
     *
     * @param pageNumber zero-based page number
     * @param pageSize page size
     * @param sortOrder sort direction
     * @param predicate filter condition
     * @return paged result [ExposedPage]
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
 * Convenience [R2dbcRepository] specialization for [Int] primary keys.
 *
 * @param E entity type
 */
interface IntR2dbcRepository<E: Any>: R2dbcRepository<Int, E>

/**
 * Convenience [R2dbcRepository] specialization for [Long] primary keys.
 *
 * @param E entity type
 */
interface LongR2dbcRepository<E: Any>: R2dbcRepository<Long, E>

/**
 * Convenience [R2dbcRepository] specialization for Kotlin [kotlin.uuid.Uuid] primary keys.
 *
 * @param E entity type
 */
@OptIn(ExperimentalUuidApi::class)
interface UuidR2dbcRepository<E: Any>: R2dbcRepository<Uuid, E>

/**
 * Convenience [R2dbcRepository] specialization for [java.util.UUID] primary keys.
 *
 * @param E entity type
 */
interface UUIDR2dbcRepository<E: Any>: R2dbcRepository<UUID, E>

/**
 * Convenience [R2dbcRepository] specialization for [String] primary keys.
 *
 * @param E entity type
 */
interface StringR2dbcRepository<E: Any>: R2dbcRepository<String, E>
