package io.bluetape4k.spring.batch.exposed.reader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.batch.exposed.partition.ExposedRangePartitioner
import io.bluetape4k.spring.batch.exposed.support.castToLong
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.beans.factory.InitializingBean

/**
 * Keyset 기반 페이지 읽기 [ItemStreamReader].
 *
 * - `WHERE [column] > lastKey AND [column] <= maxId ORDER BY [column] ASC LIMIT [pageSize]`
 * - lastKey를 [ExecutionContext]에 저장하여 restart 시 마지막 위치부터 재개
 * - 기본 생성자의 [column]과 [keyExtractor]는 strictly increasing하고 unique해야 함
 * - 중복 가능한 `Long` 컬럼은 [forColumnWithEntityIdTieBreaker]로 `LongIdTable.id` tie-breaker를 함께 사용
 * - 파티션별 독립 인스턴스이므로 thread-safety 보장
 * - `reentrantLock().withLock { ... }` 기반 `read()` 상호배제 구현 (Virtual Thread 친화적)
 *
 * @param T 반환 타입
 * @param database Exposed [Database] (null이면 SpringTransactionManager 현재 트랜잭션 참여)
 * @param pageSize 한 번에 읽을 레코드 수 (기본값: 500)
 * @param column unique keyset 기준 컬럼. `Column<Long>` 또는 `castTo<Long>()` 결과 모두 허용
 * @param table Exposed [Table]
 * @param rowMapper [ResultRow] -> T 변환 함수
 * @param keyExtractor [ResultRow]에서 keyset 컬럼 Long 값 추출
 * @param additionalCondition 추가 WHERE 조건 람다 (null이면 조건 없음)
 */
open class ExposedKeysetItemReader<T : Any>(
    private val database: Database? = null,
    private val pageSize: Int = 500,
    private val column: ExpressionWithColumnType<Long>,
    private val table: Table,
    private val rowMapper: (ResultRow) -> T,
    private val keyExtractor: (ResultRow) -> Long = { it[column] },
    private val additionalCondition: (() -> Op<Boolean>)? = null,
) : ItemStreamReader<T>, InitializingBean {

    companion object : KLogging() {
        private const val LAST_KEY = "lastKey"
        private const val LAST_TIE_BREAKER = "lastTieBreaker"
        private const val NON_UNIQUE_KEY_MESSAGE =
            "Keyset column must be strictly unique; use forColumnWithEntityIdTieBreaker for duplicate keys"

        /**
         * `LongIdTable.id` (`Column<EntityID<Long>>`) 기반 Reader 팩토리.
         *
         * - `column`: `table.id.castTo<Long>(LongColumnType())`으로 Long 변환 — WHERE/ORDER BY에서 Long 비교 사용
         * - `keyExtractor`: `it[table.id].value`로 EntityID에서 Long 추출 (selectAll 결과에서 원본 id 컬럼 사용)
         */
        fun <T : Any> forEntityId(
            table: IdTable<Long>,
            pageSize: Int = 500,
            rowMapper: (ResultRow) -> T,
            keyExtractor: (ResultRow) -> Long = { it[table.id].value },
            additionalCondition: (() -> Op<Boolean>)? = null,
            database: Database? = null,
        ): ExposedKeysetItemReader<T> = ExposedKeysetItemReader(
            database = database,
            pageSize = pageSize,
            // MySQL은 CAST(id AS BIGINT)를 지원하지 않으므로 SIGNED를 사용하는 dialect-aware cast 사용
            column = table.id.castToLong(),
            table = table,
            rowMapper = rowMapper,
            keyExtractor = keyExtractor,
            additionalCondition = additionalCondition,
        )

        /**
         * 중복 가능한 `Long` 컬럼과 [LongIdTable.id]를 복합 cursor로 사용하는 Reader를 생성합니다.
         *
         * 조회와 checkpoint는 `(column, table.id)` 순서를 사용하므로 같은 [column] 값이 page 또는
         * chunk 경계를 넘어도 남은 행을 누락하지 않습니다. 두 값은 순회 중 변경하지 않아야 하며,
         * 효율적인 조회를 위해 `(column, id)` 복합 인덱스를 권장합니다. 파티션의 min/max 값은
         * [column] 범위를 나타냅니다.
         *
         * 기존 single-column checkpoint에는 tie-breaker가 없으므로 이 factory로 전환한 reader는
         * 해당 checkpoint를 이어서 읽지 않고 명시적으로 실패합니다.
         */
        fun <T : Any> forColumnWithEntityIdTieBreaker(
            table: LongIdTable,
            column: ExpressionWithColumnType<Long>,
            pageSize: Int = 500,
            rowMapper: (ResultRow) -> T,
            keyExtractor: (ResultRow) -> Long = { it[column] },
            additionalCondition: (() -> Op<Boolean>)? = null,
            database: Database? = null,
        ): ExposedKeysetItemReader<T> = ExposedKeysetItemReader(
            database = database,
            pageSize = pageSize,
            column = column,
            table = table,
            rowMapper = rowMapper,
            keyExtractor = keyExtractor,
            additionalCondition = additionalCondition,
        ).apply {
            tieBreaker = EntityIdTieBreaker(
                expression = table.id,
                boundary = { lastId -> table.id greater lastId },
                extractor = { row -> row[table.id].value },
            )
        }
    }

    private class EntityIdTieBreaker(
        val expression: Expression<*>,
        val boundary: (Long) -> Op<Boolean>,
        val extractor: (ResultRow) -> Long,
    )

    private data class BufferedItem<T : Any>(
        val key: Long,
        val tieBreaker: Long?,
        val item: T,
    )

    private data class CursorPosition(
        val key: Long,
        val tieBreaker: Long?,
    ) {
        fun isBefore(other: CursorPosition): Boolean =
            key < other.key ||
                (key == other.key && tieBreaker != null && other.tieBreaker != null && tieBreaker < other.tieBreaker)
    }

    private var minId: Long = 0L
    private var maxId: Long = Long.MAX_VALUE
    private var lastKey: Long = 0L
    private var lastTieBreaker: Long = Long.MIN_VALUE
    private var tieBreaker: EntityIdTieBreaker? = null
    // cursor와 item을 함께 보관해 실제 소비 시점에 checkpoint를 갱신합니다.
    private val buffer: MutableList<BufferedItem<T>> = mutableListOf()
    private var bufferIndex: Int = 0
    private var exhausted: Boolean = false
    private val lock = reentrantLock()

    override fun afterPropertiesSet() {
        validatePageSize()
    }

    private fun validatePageSize() {
        require(pageSize > 0) { "pageSize must be positive" }
        require(pageSize < Int.MAX_VALUE) { "pageSize must be less than Int.MAX_VALUE" }
    }

    override fun open(executionContext: ExecutionContext) {
        minId = executionContext.getLong(ExposedRangePartitioner.PARTITION_MIN_ID)
        maxId = executionContext.getLong(ExposedRangePartitioner.PARTITION_MAX_ID)

        lastKey = if (executionContext.containsKey(LAST_KEY)) {
            executionContext.getLong(LAST_KEY)
        } else {
            minId - 1
        }

        lastTieBreaker = tieBreaker?.let {
            check(!executionContext.containsKey(LAST_KEY) || executionContext.containsKey(LAST_TIE_BREAKER)) {
                "Composite keyset checkpoint requires $LAST_TIE_BREAKER"
            }
            if (executionContext.containsKey(LAST_TIE_BREAKER)) {
                executionContext.getLong(LAST_TIE_BREAKER)
            } else {
                Long.MIN_VALUE
            }
        } ?: Long.MIN_VALUE
    }

    override fun read(): T? {
        lock.withLock {
            if (exhausted) return null

            if (bufferIndex >= buffer.size) {
                fetchNextPage()
                if (buffer.isEmpty()) {
                    exhausted = true
                    return null
                }
            }

            val bufferedItem = buffer[bufferIndex++]
            lastKey = bufferedItem.key
            bufferedItem.tieBreaker?.let { lastTieBreaker = it }
            return bufferedItem.item
        }
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putLong(LAST_KEY, lastKey)
        if (tieBreaker != null) {
            executionContext.putLong(LAST_TIE_BREAKER, lastTieBreaker)
        }
    }

    override fun close() {
        buffer.clear()
        bufferIndex = 0
        exhausted = false
        lastKey = 0L
        lastTieBreaker = Long.MIN_VALUE
    }

    private fun fetchNextPage() {
        validatePageSize()
        buffer.clear()
        bufferIndex = 0

        transaction(database) {
            val cursorCondition = tieBreaker?.let { currentTieBreaker ->
                (column greater lastKey) or
                    ((column eq lastKey) and currentTieBreaker.boundary(lastTieBreaker))
            } ?: (column greater lastKey)
            var condition: Op<Boolean> = cursorCondition and (column lessEq maxId)
            additionalCondition?.let { addCond ->
                condition = condition and addCond()
            }

            val query = table.selectAll().where { condition }
            tieBreaker?.let { currentTieBreaker ->
                query.orderBy(
                    column to SortOrder.ASC,
                    currentTieBreaker.expression to SortOrder.ASC,
                )
            } ?: query.orderBy(column, SortOrder.ASC)

            val resultRows = query.limit(pageSize + 1).toList()
            val positionedRows = resultRows.map { row ->
                CursorPosition(keyExtractor(row), tieBreaker?.extractor?.invoke(row)) to row
            }
            positionedRows.zipWithNext().forEach { (current, next) ->
                check(current.first.isBefore(next.first)) { NON_UNIQUE_KEY_MESSAGE }
            }

            buffer.addAll(
                positionedRows.take(pageSize).map { (position, row) ->
                    BufferedItem(position.key, position.tieBreaker, rowMapper(row))
                },
            )
        }

        log.debug {
            "${buffer.size}건 읽음 (table=${table.tableName}, lastKey=$lastKey, lastTieBreaker=$lastTieBreaker)"
        }
    }
}
