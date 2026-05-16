package io.bluetape4k.batch.r2dbc

import io.bluetape4k.batch.BatchDefaults
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.andWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * [BatchReader] implementation using Exposed R2DBC with keyset pagination.
 *
 * Follows the same keyset pagination pattern as [io.bluetape4k.batch.jdbc.ExposedJdbcBatchReader]
 * but uses `suspendTransaction` for native coroutine support.
 *
 * ## Keyset pagination
 * Each page uses `WHERE keyColumn > lastFetchedKey ORDER BY keyColumn ASC LIMIT pageSize`,
 * which is more stable under large data sets than offset-based approaches.
 *
 * ## Checkpoint semantics
 * - Calling [onChunkCommitted] advances `lastCommittedKey` to `lastReadKey`.
 * - Calling [restoreFrom] resumes reading from that key onward.
 *
 * ```kotlin
 * val reader = ExposedR2dbcBatchReader(
 *     database = db,
 *     table = OrderTable,
 *     keyColumn = OrderTable.id,
 *     pageSize = 500,
 *     rowMapper = { it.toOrderRecord() },
 *     keyExtractor = { it.id },
 * )
 * ```
 *
 * @param K Keyset key type (Comparable — typically Long, Int, or UUID)
 * @param T Item type being read
 * @param database Exposed R2DBC database
 * @param table Target Exposed table
 * @param keyColumn Keyset sort column (PK recommended)
 * @param pageSize Number of rows per page (must be positive)
 * @param rowMapper Converts [ResultRow] to [T]
 * @param keyExtractor Extracts [K] from [T]
 * @param minKey Partition start key (exclusive) — null means read from the beginning; use for parallel partitioning
 * @param maxKey Partition end key (inclusive) — null means read to the end; use for parallel partitioning
 */
class ExposedR2dbcBatchReader<K : Comparable<K>, T : Any>(
    private val database: R2dbcDatabase,
    private val table: Table,
    private val keyColumn: Column<K>,
    private val pageSize: Int = BatchDefaults.READER_PAGE_SIZE,
    private val rowMapper: suspend (ResultRow) -> T,
    private val keyExtractor: (T) -> K,
    private val minKey: K? = null,
    private val maxKey: K? = null,
) : BatchReader<T> {

    companion object : KLoggingChannel()

    private val buffer = ArrayDeque<T>()
    private var lastFetchedKey: K? = minKey
    private var lastReadKey: K? = null
    private var lastCommittedKey: K? = null
    private var exhausted = false

    init {
        pageSize.requirePositiveNumber("pageSize")
    }

    override suspend fun open() {
        buffer.clear()
        lastFetchedKey = minKey
        lastReadKey = null
        lastCommittedKey = null
        exhausted = false
    }

    override suspend fun read(): T? {
        if (buffer.isEmpty() && !exhausted) {
            fetchNextPage()
        }
        val item = buffer.removeFirstOrNull() ?: return null
        lastReadKey = keyExtractor(item)
        return item
    }

    override suspend fun checkpoint(): Any? = lastCommittedKey

    override suspend fun onChunkCommitted() {
        lastCommittedKey = lastReadKey
        lastFetchedKey = lastCommittedKey
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun restoreFrom(checkpoint: Any) {
        lastCommittedKey = checkpoint as K
        lastFetchedKey = lastCommittedKey
        lastReadKey = lastCommittedKey
        buffer.clear()
        exhausted = false
    }

    override suspend fun close() {
        runCatching { buffer.clear() }
    }

    private suspend fun fetchNextPage() {
        val page = suspendTransaction(db = database) {
            val query = table.selectAll()
            lastFetchedKey?.let { key ->
                query.andWhere { keyColumn greater key }
            }
            maxKey?.let { max ->
                query.andWhere { keyColumn lessEq max }
            }
            query.orderBy(keyColumn, SortOrder.ASC)
                .limit(pageSize)
                .map { rowMapper(it) }
                .toList()
        }

        if (page.isEmpty()) {
            exhausted = true
        } else {
            buffer.addAll(page)
            lastFetchedKey = keyExtractor(page.last())
        }
    }
}
