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
 * Base repository interface for Exposed JDBC backed by an [IdTable].
 *
 * Provides common CRUD operations for reading, persisting, and deleting
 * entities of type [E] identified by primary key type [ID].
 * Implementors need only define [table] and [ResultRow.toEntity].
 *
 * @param ID primary key type (e.g. [Long], [Int], [java.util.UUID])
 * @param E entity (record) type mapped from a [ResultRow]
 *
 * ## Usage
 *
 * ```kotlin
 * // 1. Table definition
 * object ActorTable : LongIdTable("actors") {
 *     val firstName = varchar("first_name", 50)
 *     val lastName  = varchar("last_name",  50)
 *     val birthday  = date("birthday").nullable()
 * }
 *
 * // 2. Record (DTO) type
 * data class ActorRecord(
 *     val id: Long = 0L,
 *     val firstName: String,
 *     val lastName: String,
 * )
 *
 * // 3. Repository implementation
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
 * // 4. Usage inside a transaction
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
     * Returns the [IdTable] that backs this repository.
     */
    val table: IdTable<ID>

    /**
     * Extracts the primary key from [entity].
     */
    fun extractId(entity: E): ID

    /**
     * Maps a [ResultRow] to an entity of type [E].
     *
     * @receiver the row returned by an Exposed query
     * @return the mapped entity [E]
     */
    fun ResultRow.toEntity(): E

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
            "Override BatchInsertStatement.bindSave(entity) before calling JdbcRepository.saveAll()."
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
     * Returns the total number of rows in the table.
     */
    fun count(): Long = table.selectAll().count()

    /**
     * Returns the number of rows matching [predicate].
     *
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     */
    fun countBy(predicate: () -> Op<Boolean> = { Op.TRUE }): Long = table.selectAll().where(predicate).count()

    /**
     * Returns the number of rows matching [op].
     *
     * @param op filter condition
     */
    fun countBy(op: Op<Boolean>): Long = table.selectAll().where(op).count()

    /**
     * Returns `true` when the table contains no rows.
     */
    fun isEmpty(): Boolean = table.selectAll().empty()

    /**
     * Returns `true` when the table contains at least one row.
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Returns `true` when at least one row matches the given [query].
     *
     * @param query the sub-query to check existence for
     */
    fun exists(query: AbstractQuery<*>): Boolean {
        val exists =
            org.jetbrains.exposed.v1.core
                .exists(query)
        return table.select(exists).firstOrNull()?.getOrNull(exists) ?: false
    }

    /**
     * Returns `true` when a row with the given [id] exists in the table.
     *
     * @param id entity primary key
     */
    fun existsById(id: ID): Boolean = !table.selectAll().where { table.id eq id }.empty()

    /**
     * Returns `true` when at least one row matches [predicate].
     *
     * @param predicate filter condition
     */
    fun existsBy(predicate: () -> Op<Boolean>): Boolean = !table.selectAll().where(predicate).empty()

    /**
     * Returns the entity with the given [id], or throws if it does not exist.
     *
     * @param id entity primary key
     * @return the matching entity [E]
     * @throws NoSuchElementException when no row with [id] is found
     * @throws IllegalArgumentException when more than one row with [id] is found
     *
     * ## Usage
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
     * Returns the entity with the given [id], or `null` if it does not exist.
     *
     * @param id entity primary key
     * @return the matching entity [E], or `null`
     */
    fun findByIdOrNull(id: ID): E? =
        table
            .selectAll()
            .where { table.id eq id }
            .singleOrNull()
            ?.toEntity()

    /**
     * Returns all entities matching [predicate].
     *
     * @param limit maximum number of results; `null` means no limit
     * @param offset zero-based row offset; `null` means no offset
     * @param sortOrder result ordering (default: [SortOrder.ASC])
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     * @return list of matching entities
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
     * Returns all entities matching all [filters] combined with AND.
     *
     * @param filters vararg filter lambdas combined with AND
     * @param limit maximum number of results; `null` means no limit
     * @param offset zero-based row offset; `null` means no offset
     * @param sortOrder result ordering (default: [SortOrder.ASC])
     * @return list of matching entities
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
     * Returns all entities matching all [filters] combined with AND.
     *
     * Alias for [findWithFilters].
     *
     * @param filters vararg filter lambdas combined with AND
     * @param limit maximum number of results; `null` means no limit
     * @param offset zero-based row offset; `null` means no offset
     * @param sortOrder result ordering (default: [SortOrder.ASC])
     * @return list of matching entities
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
     * Returns the first entity matching [predicate], or `null` if none is found.
     *
     * @param offset zero-based row offset; `null` means no offset
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     * @return the first matching entity [E], or `null`
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
     * Returns the last entity matching [predicate] ordered by primary key DESC,
     * or `null` if none is found.
     *
     * @param offset zero-based row offset; `null` means no offset
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     * @return the last matching entity [E], or `null`
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
     * Returns all entities where [field] equals [value].
     *
     * @param field the column to filter on
     * @param value the value to match
     * @return list of matching entities
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
     * Returns the first entity where [field] equals [value], or `null` if none exists.
     *
     * @param field the column to filter on
     * @param value the value to match
     * @return the first matching entity [E], or `null`
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
     * Returns all entities whose primary key is contained in [ids].
     *
     * **Note:** a large [ids] collection may exceed the database `IN` clause limit.
     * For bulk lookups, split large ID lists into smaller chunks.
     *
     * @param ids collection of primary key values to look up
     * @return list of matching entities
     */
    fun findAllByIds(ids: Iterable<ID>): List<E> =
        table
            .selectAll()
            .where { table.id inList ids }
            .map { it.toEntity() }

    /**
     * Deletes [entity] by its primary key.
     *
     * @return number of deleted rows
     */
    fun delete(entity: E): Int = deleteById(extractId(entity))

    /**
     * Deletes the row with the given [id].
     *
     * @param id entity primary key
     * @return number of deleted rows
     */
    fun deleteById(id: ID): Int = table.deleteWhere { table.id eq id }

    /**
     * Deletes all rows matching [op].
     *
     * @param limit maximum number of rows to delete; `null` means no limit
     * @param op filter condition; defaults to `Op.TRUE` (all rows)
     * @return number of deleted rows
     */
    fun deleteAll(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteWhere(limit = limit, op = op)

    /**
     * Deletes the row with [id], ignoring constraint violations.
     *
     * @param id entity primary key
     * @return number of deleted rows
     */
    fun deleteByIdIgnore(id: ID): Int = table.deleteIgnoreWhere { table.id eq id }

    /**
     * Deletes all rows matching [op], ignoring constraint violations.
     *
     * @param limit maximum number of rows to delete; `null` means no limit
     * @param op filter condition; defaults to `Op.TRUE` (all rows)
     * @return number of deleted rows
     */
    fun deleteAllIgnore(
        limit: Int? = null,
        op: (IdTable<ID>).() -> Op<Boolean> = { Op.TRUE },
    ): Int = table.deleteIgnoreWhere(limit, op = op)

    /**
     * Deletes all rows whose primary key is contained in [ids].
     *
     * **Note:** a large [ids] collection may exceed the database `IN` clause limit.
     * For bulk deletions, split large ID lists into smaller chunks.
     *
     * @param ids collection of primary key values to delete
     * @return number of deleted rows
     */
    fun deleteAllByIds(ids: Iterable<ID>): Int = table.deleteWhere { table.id inList ids }

    /**
     * Updates the row identified by [id] using [updateStatement].
     *
     * @param id entity primary key
     * @param limit maximum number of rows to update; `null` means no limit
     * @param updateStatement column assignments to apply
     * @return number of updated rows
     */
    fun updateById(
        id: ID,
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = { table.id eq id }, limit = limit, body = updateStatement)

    /**
     * Updates all rows matching [predicate] using [updateStatement].
     *
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     * @param limit maximum number of rows to update; `null` means no limit
     * @param updateStatement column assignments to apply
     * @return number of updated rows
     */
    fun updateAll(
        predicate: () -> Op<Boolean> = { Op.TRUE },
        limit: Int? = null,
        updateStatement: IdTable<ID>.(UpdateStatement) -> Unit,
    ): Int = table.update(where = predicate, limit = limit, body = updateStatement)

    /**
     * Batch-inserts [entities] using a caller-supplied [insertStatement] lambda and
     * returns the resulting entities mapped via [ResultRow.toEntity].
     *
     * @param entities data to insert
     * @param ignore when `true`, duplicate-key violations are silently skipped
     * @param shouldReturnGeneratedValues when `true`, Exposed returns auto-generated values
     * @param insertStatement column assignments for each element
     * @return list of inserted entities
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
     * Batch-inserts [entities] from a [Sequence] using a caller-supplied [insertStatement]
     * lambda and returns the resulting entities mapped via [ResultRow.toEntity].
     *
     * @param entities data sequence to insert
     * @param ignore when `true`, duplicate-key violations are silently skipped
     * @param shouldReturnGeneratedValues when `true`, Exposed returns auto-generated values
     * @param insertStatement column assignments for each element
     * @return list of inserted entities
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
     * Batch-upserts [entities] and returns the resulting entities mapped via [ResultRow.toEntity].
     *
     * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for details.
     *
     * @param entities data to upsert
     * @param keys columns used to match duplicate rows; defaults to the primary key, then the
     *   first unique index
     * @param onUpdate lambda receiving an [UpdateStatement] to specify UPDATE column assignments;
     *   call `insertValue()` to reuse the INSERT value for a column; `null` updates all columns
     *   with the INSERT values
     * @param onUpdateExclude columns to exclude from the UPDATE clause; `null` updates all columns
     * @param shouldReturnGeneratedValues when `true`, Exposed returns auto-generated values
     * @return list of upserted entities
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
     * Batch-upserts [entities] from a [Sequence] and returns the resulting entities mapped via
     * [ResultRow.toEntity].
     *
     * See [Batch Insert](https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert) for details.
     *
     * @param entities data sequence to upsert
     * @param keys columns used to match duplicate rows; defaults to the primary key, then the
     *   first unique index
     * @param onUpdate lambda receiving an [UpdateStatement] to specify UPDATE column assignments;
     *   call `insertValue()` to reuse the INSERT value for a column; `null` updates all columns
     *   with the INSERT values
     * @param onUpdateExclude columns to exclude from the UPDATE clause; `null` updates all columns
     * @param shouldReturnGeneratedValues when `true`, Exposed returns auto-generated values
     * @return list of upserted entities
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
     * Returns a paginated slice of entities matching [predicate].
     *
     * ## Behaviour / Contract
     * `totalCount` and `content` are fetched with separate queries and are **not**
     * atomically consistent. A concurrent transaction that inserts or deletes rows
     * between the two queries can cause the counts to disagree. Use
     * `SERIALIZABLE` isolation level when strict consistency is required.
     *
     * @param pageNumber zero-based page index (must be ≥ 0)
     * @param pageSize number of rows per page (must be > 0)
     * @param sortOrder ordering applied to the result (default: [SortOrder.ASC])
     * @param predicate filter condition; defaults to `Op.TRUE` (all rows)
     * @return [ExposedPage] containing `content`, `totalCount`, `pageNumber`, `pageSize`,
     *   and `totalPages`
     *
     * ## Usage
     *
     * ```kotlin
     * transaction {
     *     val page = repo.findPage(
     *         pageNumber = 0,
     *         pageSize   = 20,
     *     ) { ActorTable.lastName eq "Depp" }
     *
     *     println(page.content)    // matching entities on this page
     *     println(page.totalCount) // total rows matching the predicate
     *     println(page.totalPages) // total number of pages
     *     println(page.pageNumber) // current page index
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
 * Convenience specialization of [JdbcRepository] with an [Int] primary key.
 *
 * @param E entity type
 */
interface IntJdbcRepository<E: Any>: JdbcRepository<Int, E>

/**
 * Convenience specialization of [JdbcRepository] with a [Long] primary key.
 *
 * @param E entity type
 *
 * ## Usage
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
 * Convenience specialization of [JdbcRepository] with a Kotlin [Uuid] primary key.
 *
 * @param E entity type
 */
@OptIn(ExperimentalUuidApi::class)
interface UuidJdbcRepository<E: Any>: JdbcRepository<Uuid, E>

/**
 * Convenience specialization of [JdbcRepository] with a [java.util.UUID] primary key.
 *
 * @param E entity type
 */
interface UUIDJdbcRepository<E: Any>: JdbcRepository<UUID, E>

/**
 * Convenience specialization of [JdbcRepository] with a [String] primary key.
 *
 * @param E entity type
 */
interface StringJdbcRepository<E: Any>: JdbcRepository<String, E>
