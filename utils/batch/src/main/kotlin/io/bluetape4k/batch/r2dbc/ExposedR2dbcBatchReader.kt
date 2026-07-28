package io.bluetape4k.batch.r2dbc

import io.bluetape4k.batch.BatchDefaults
import io.bluetape4k.batch.api.BatchReader
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlin.reflect.KClass
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
 * Exposed R2DBC와 keyset pagination을 사용해 데이터를 읽는 [BatchReader] 구현체입니다.
 *
 * [io.bluetape4k.batch.jdbc.ExposedJdbcBatchReader]와 같은 keyset pagination 패턴을 따르지만,
 * native coroutine 지원을 위해 `suspendTransaction`을 사용합니다.
 *
 * ## Keyset pagination
 * 각 page는 `WHERE keyColumn > lastFetchedKey ORDER BY keyColumn ASC LIMIT pageSize` 형태로 조회합니다.
 * 대용량 데이터셋에서는 offset 기반 접근보다 안정적입니다.
 *
 * ## Checkpoint 의미
 * - [onChunkCommitted] 호출은 `lastCommittedKey`를 `lastReadKey`까지 전진시킵니다.
 * - [restoreFrom] 호출은 저장된 key 이후부터 읽기를 재개합니다.
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
 * @param K keyset key 타입입니다. [Comparable]이어야 하며 일반적으로 `Long`, `Int`, `UUID` 등을 사용합니다.
 * @param T 읽어올 item 타입입니다.
 * @param database Exposed R2DBC database입니다.
 * @param table 조회 대상 Exposed table입니다.
 * @param keyColumn keyset 정렬에 사용할 column입니다. primary key 사용을 권장합니다.
 * @param pageSize page마다 조회할 row 수입니다. 0보다 커야 합니다.
 * @param rowMapper [ResultRow]를 [T]로 변환합니다.
 * @param keyExtractor [T]에서 [K]를 추출합니다.
 * @param minKey partition 시작 key입니다. exclusive boundary이며, `null`이면 처음부터 읽습니다.
 * parallel partitioning에 사용할 수 있습니다.
 * @param maxKey partition 종료 key입니다. inclusive boundary이며, `null`이면 끝까지 읽습니다.
 * parallel partitioning에 사용할 수 있습니다.
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
    private val keyClass: KClass<K>? = null,
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
        resetState()
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
    }

    /**
     * buffer를 비우고 reader를 최초 partition boundary 상태로 되돌립니다.
     */
    override suspend fun close() {
        resetState()
    }

    private fun resetState() {
        buffer.clear()
        lastFetchedKey = minKey
        lastReadKey = null
        lastCommittedKey = null
        exhausted = false
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
