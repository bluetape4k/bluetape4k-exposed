package io.bluetape4k.batch.jdbc

import io.bluetape4k.batch.BatchDefaults
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.concurrent.virtualthread.VT
import kotlin.reflect.KClass
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * [BatchReader] implementation using Exposed JDBC with keyset pagination.
 *
 * ## Keyset pagination
 * Each page uses `WHERE keyColumn > lastFetchedKey ORDER BY keyColumn ASC LIMIT pageSize`,
 * which is more efficient than offset-based pagination for large data sets.
 *
 * ## Checkpoint semantics
 * - [checkpoint]: returns `lastCommittedKey` (the key of the last successfully committed chunk)
 * - [onChunkCommitted]: advances `lastCommittedKey` to `lastReadKey`
 * - [restoreFrom]: resumes from the stored key — call after [open] and before the first [read]
 *
 * ## Concurrency
 * Internal state (`buffer`, `lastFetchedKey`, `lastReadKey`, `lastCommittedKey`, `exhausted`)
 * is designed for single-threaded (runner) access only.
 *
 * ## Usage
 * ```kotlin
 * val reader = ExposedJdbcBatchReader<Long, OrderRecord>(
 *     database = db,
 *     table = OrderTable,
 *     keyColumn = OrderTable.id,
 *     rowMapper = { row -> OrderRecord(row[OrderTable.id].value, row[OrderTable.name]) },
 *     keyExtractor = { it.id },
 * )
 * ```
 *
 * @param K Key type (must be Comparable)
 * @param T Item type
 * @param database Exposed JDBC [Database]
 * @param table Target [Table]
 * @param keyColumn Keyset column of type [K]
 * @param pageSize Number of rows to fetch per page (must be > 0)
 * @param rowMapper Converts a [ResultRow] to [T]
 * @param keyExtractor Extracts [K] from [T] — used to advance page and commit pointers
 * @param minKey Partition start key (exclusive) — null means read from the beginning; use for parallel partitioning
 * @param maxKey Partition end key (inclusive) — null means read to the end; use for parallel partitioning
 */
class ExposedJdbcBatchReader<K: Comparable<K>, T: Any>(
    private val database: Database,
    private val table: Table,
    private val keyColumn: Column<K>,
    private val pageSize: Int = BatchDefaults.READER_PAGE_SIZE,
    private val rowMapper: (ResultRow) -> T,
    private val keyExtractor: (T) -> K,
    private val minKey: K? = null,
    private val maxKey: K? = null,
    private val keyClass: KClass<K>? = null,
): BatchReader<T> {

    companion object: KLoggingChannel()

    init {
        pageSize.requirePositiveNumber("pageSize")
    }

    private val buffer = ArrayDeque<T>()
    private var lastFetchedKey: K? = minKey
    private var lastReadKey: K? = null
    private var lastCommittedKey: K? = null
    private var exhausted = false

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
        log.debug { "청크 커밋 완료: lastCommittedKey=$lastCommittedKey" }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun restoreFrom(checkpoint: Any) {
        // Use keyClass.isInstance for a real runtime check when the key type is known.
        // Without keyClass, K is erased to Comparable at runtime so `as K` may not
        // catch a wrong-type checkpoint (e.g. String for a Long key).
        if (keyClass != null && !keyClass.isInstance(checkpoint)) {
            throw IllegalArgumentException(
                "restoreFrom: checkpoint type mismatch — expected ${keyClass.simpleName}, " +
                    "got ${checkpoint::class.qualifiedName} for keyColumn '${keyColumn.name}'"
            )
        }
        val key = try {
            checkpoint as K
        } catch (e: ClassCastException) {
            throw IllegalArgumentException(
                "restoreFrom: checkpoint type mismatch — expected type compatible with " +
                    "keyColumn '${keyColumn.name}', got ${checkpoint::class.qualifiedName}",
                e
            )
        }
        lastCommittedKey = key
        lastFetchedKey = key
        lastReadKey = key
        buffer.clear()
        exhausted = false
        log.debug { "체크포인트 복원: lastCommittedKey=$key" }
    }

    override suspend fun close() {
        runCatching { buffer.clear() }
    }

    private suspend fun fetchNextPage() {
        val page = withContext(Dispatchers.VT) {
            transaction(database) {
                val query = table.selectAll()
                lastFetchedKey?.let { key ->
                    query.andWhere { keyColumn greater key }
                }
                maxKey?.let { max ->
                    query.andWhere { keyColumn lessEq max }
                }
                query.orderBy(keyColumn, SortOrder.ASC)
                    .limit(pageSize)
                    .map(rowMapper)
            }
        }

        if (page.isEmpty()) {
            exhausted = true
            log.debug { "페이지 조회 결과 없음 — 소진 상태로 전환" }
        } else {
            buffer.addAll(page)
            lastFetchedKey = keyExtractor(page.last())
            log.debug { "페이지 조회: size=${page.size}, lastFetchedKey=$lastFetchedKey" }
        }
    }
}
