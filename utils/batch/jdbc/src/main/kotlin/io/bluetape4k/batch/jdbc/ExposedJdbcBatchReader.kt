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
 * Exposed JDBC와 keyset pagination을 사용해 데이터를 읽는 [BatchReader] 구현체입니다.
 *
 * ## Keyset pagination
 * 각 page는 `WHERE keyColumn > lastFetchedKey ORDER BY keyColumn ASC LIMIT pageSize` 형태로 조회합니다.
 * 대용량 데이터셋에서는 offset 기반 pagination보다 안정적이고 효율적입니다.
 *
 * ## Checkpoint 의미
 * - [checkpoint]: 마지막으로 성공적으로 commit된 chunk의 key인 `lastCommittedKey`를 반환합니다.
 * - [onChunkCommitted]: `lastCommittedKey`를 `lastReadKey`까지 전진시킵니다.
 * - [restoreFrom]: 저장된 key부터 읽기를 재개합니다. [open] 이후, 첫 [read] 이전에 호출해야 합니다.
 *
 * ## 동시성
 * 내부 상태(`buffer`, `lastFetchedKey`, `lastReadKey`, `lastCommittedKey`, `exhausted`)는
 * 단일 runner thread/coroutine에서만 접근하는 것을 전제로 합니다.
 *
 * ## 사용 예
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
 * @param K keyset key 타입입니다. [Comparable]이어야 합니다.
 * @param T 읽어올 item 타입입니다.
 * @param database Exposed JDBC [Database]입니다.
 * @param table 조회 대상 [Table]입니다.
 * @param keyColumn keyset pagination에 사용할 [K] 타입 column입니다.
 * @param pageSize page마다 조회할 row 수입니다. 0보다 커야 합니다.
 * @param rowMapper [ResultRow]를 [T]로 변환합니다.
 * @param keyExtractor [T]에서 [K]를 추출합니다. page pointer와 commit pointer를 전진시키는 데 사용합니다.
 * @param minKey partition 시작 key입니다. exclusive boundary이며, `null`이면 처음부터 읽습니다.
 * parallel partitioning에 사용할 수 있습니다.
 * @param maxKey partition 종료 key입니다. inclusive boundary이며, `null`이면 끝까지 읽습니다.
 * parallel partitioning에 사용할 수 있습니다.
 */
@Suppress("LongParameterList")
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
        log.debug { "청크 커밋 완료" }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun restoreFrom(checkpoint: Any) {
        // Use keyClass.isInstance for a real runtime check when the key type is known.
        // Without keyClass, K is erased to Comparable at runtime so `as K` may not
        // catch a wrong-type checkpoint (e.g. String for a Long key).
        keyClass?.let { expectedKeyClass ->
            require(expectedKeyClass.isInstance(checkpoint)) {
                "restoreFrom: checkpoint type mismatch — expected ${expectedKeyClass.simpleName}, " +
                    "got ${checkpoint::class.qualifiedName} for keyColumn '${keyColumn.name}'"
            }
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
        log.debug { "체크포인트 복원 완료" }
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
            log.debug { "페이지 조회: size=${page.size}" }
        }
    }
}
