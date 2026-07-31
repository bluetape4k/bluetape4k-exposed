package io.bluetape4k.exposed.trino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.BatchInsertStatement
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.Serializable

private const val DEFAULT_TRINO_PAGE_SIZE = 1_000
private const val DEFAULT_TRINO_BATCH_CHUNK_SIZE = 1_000

/**
 * [pagedQueryFlow] option입니다.
 *
 * @property pageSize transaction마다 요청할 최대 row 수
 * @property initialOffset page query block에 처음 전달할 offset
 */
data class TrinoPagedQueryOptions(
    val pageSize: Int = DEFAULT_TRINO_PAGE_SIZE,
    val initialOffset: Long = 0L,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(pageSize > 0) { "pageSize must be positive: $pageSize" }
        require(initialOffset >= 0L) { "initialOffset must be non-negative: $initialOffset" }
    }
}

/**
 * [trinoBatchInsert] option입니다.
 *
 * Trino write 지원 여부는 connector에 따라 달라지며 이 module은 connector write path에
 * transaction을 제공할 수 없습니다. 기본값은 generated key 조회를 피하고 row를 제한된
 * chunk로 전송하여 Trino JDBC 호환성을 우선합니다.
 *
 * @property chunkSize 한 번의 Exposed `batchInsert` 호출로 전송할 최대 row 수
 * @property shouldReturnGeneratedValues Exposed가 JDBC에 generated value를 요청할지 여부.
 *   일반 Trino table에서는 비활성 상태를 유지하십시오.
 */
data class TrinoBatchInsertOptions(
    val chunkSize: Int = DEFAULT_TRINO_BATCH_CHUNK_SIZE,
    val shouldReturnGeneratedValues: Boolean = false,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
    }
}

/**
 * Connector에 의존하는 Trino batch insert를 제한된 chunk 단위로 실행합니다.
 *
 * Trino 전용 bulk-loader protocol이 아니라 얇은 Exposed `batchInsert` wrapper입니다.
 * 이미 `INSERT`를 지원하는 connector를 대상으로 하며, 호출자가 한 번의 JDBC 호출에서 매우
 * 큰 write를 materialize하거나 전송하지 않도록 batch size를 명시적으로 유지합니다.
 *
 * 이 module에서 Trino transaction은 autocommit처럼 동작합니다. 뒤쪽 chunk가 실패해도 앞선
 * chunk는 이미 보일 수 있으며 [TrinoConnectionWrapper.rollback]으로 rollback되지 않습니다.
 *
 * ```kotlin
 * Events.trinoBatchInsert(events, TrinoBatchInsertOptions(chunkSize = 500)) { event ->
 *     this[Events.eventId] = event.id
 *     this[Events.eventName] = event.name
 *     this[Events.region] = event.region
 * }
 * ```
 *
 * @param data insert할 source row
 * @param options chunking과 generated-value 동작
 * @param body Exposed batch insert body
 * @return [TrinoBatchInsertOptions.shouldReturnGeneratedValues]가 활성화된 경우 Exposed가
 *   반환한 generated row. 기본값에서는 write 성공 후 빈 list를 반환합니다.
 */
fun <E> Table.trinoBatchInsert(
    data: Iterable<E>,
    options: TrinoBatchInsertOptions = TrinoBatchInsertOptions(),
    body: BatchInsertStatement.(E) -> Unit,
): List<ResultRow> {
    val generatedRows = mutableListOf<ResultRow>()
    data.asSequence().chunked(options.chunkSize).forEach { chunk ->
        val rows = batchInsert(
            data = chunk,
            shouldReturnGeneratedValues = options.shouldReturnGeneratedValues,
            body = body,
        )
        if (options.shouldReturnGeneratedValues) {
            generatedRows += rows
        }
    }
    return generatedRows
}

/**
 * Trino에서 suspend 트랜잭션을 실행합니다.
 *
 * ```kotlin
 * val db = TrinoDatabase.connect("jdbc:trino://host:8080/catalog/schema")
 *
 * // suspend 트랜잭션
 * val rows = suspendTransaction(db) {
 *     Events.selectAll().where { Events.region eq "kr" }.toList()
 * }
 *
 * // Virtual Thread 사용
 * val vtDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
 * val rows = suspendTransaction(db, vtDispatcher) {
 *     Events.selectAll().toList()
 * }
 * ```
 *
 * ⚠️ Trino autocommit 주의사항:
 * - 원자성 보장 없음 — 블록 중간 실패 시 앞선 DML 롤백 안 됨
 * - rollback() no-op
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db Trino 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 트랜잭션 블록
 */
suspend fun <T> suspendTransaction(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.() -> T,
    // Trino JDBC 호출은 블로킹 I/O이므로, 코루틴 기본 디스패처(Main/Default)를 점유하지 않도록
    // Dispatchers.IO(또는 Virtual Thread 전용 디스패처)로 컨텍스트를 전환합니다.
): T = withContext(dispatcher) {
    try {
        transaction(db) { block() }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
}

/**
 * Trino 쿼리 결과를 [Flow]로 반환합니다.
 *
 * 구현상 JDBC `ResultSet` 수명과 Exposed 트랜잭션 경계를 안전하게 유지하기 위해
 * 트랜잭션 내부에서 결과를 `List`로 materialize 한 뒤 순차적으로 emit 합니다.
 * 따라서 소비 API는 [Flow]이지만, 엄밀한 의미의 row-by-row 스트리밍은 아닙니다.
 * 중간 규모 결과를 코루틴 파이프라인으로 연결할 때 적합하며,
 * 매우 큰 결과셋은 페이지네이션 또는 전용 배치 전략을 별도로 고려해야 합니다.
 *
 * ```kotlin
 * val db = TrinoDatabase.connect("jdbc:trino://host:8080/catalog/schema")
 *
 * queryFlow(db) {
 *     Events.selectAll().where { Events.region eq "kr" }
 * }.collect { row ->
 *     println(row[Events.eventId])
 * }
 * ```
 *
 * ⚠️ Trino autocommit 주의사항:
 * - 원자성 보장 없음 — 블록 중간 실패 시 앞선 DML 롤백 안 됨
 * - rollback() no-op
 * - nested transaction 호출 허용되나 원자성 없음
 * - multi-statement 쓰기 시 부분 반영 위험
 *
 * @param db Trino 데이터베이스 연결
 * @param dispatcher 블로킹 JDBC 호출을 실행할 디스패처 (기본값: [Dispatchers.IO])
 * @param block 조회 결과를 반환하는 트랜잭션 블록. 반환된 [Iterable]은 트랜잭션 안에서 즉시 materialize 됩니다.
 */
fun <T> queryFlow(
    db: Database,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.() -> Iterable<T>,
): Flow<T> = flow {
    // Trino JDBC 호출은 블로킹 I/O이므로 Dispatchers.IO로 전환하고,
    // ResultSet 수명(트랜잭션 경계 내)과 Flow emit 경계가 겹치지 않도록
    // 트랜잭션 내에서 List로 완전히 materialize한 뒤 방출합니다.
    val items = try {
        withContext(dispatcher) { transaction(db) { block().toList() } }
    } catch (e: CancellationException) {
        // 코루틴 취소는 반드시 재전파해야 합니다 — 삼키면 구조적 동시성이 깨집니다.
        throw e
    }
    for (item in items) {
        currentCoroutineContext().ensureActive()
        emit(item)
    }
}

/**
 * Trino query 결과를 page 단위 [Flow]로 반환합니다.
 *
 * 이 function은 의도적으로 row-by-row JDBC cursor stream을 제공하지 않습니다. 각 page는
 * 짧은 Exposed transaction 안에서 조회하고 materialize한 뒤 transaction을 닫은 후 emit합니다.
 * 따라서 전체 result set을 한 번에 materialize하지 않으면서 JDBC `ResultSet`과 Exposed
 * transaction 수명 경계를 보존합니다.
 *
 * 호출자는 제공된 `limit`과 `offset`을 안정적이고 결정적인 query에 적용해야 하며, 일반적으로
 * 명시적인 `orderBy`가 필요합니다.
 *
 * ```kotlin
 * pagedQueryFlow(db, TrinoPagedQueryOptions(pageSize = 500)) { limit, offset ->
 *     Events.selectAll()
 *         .orderBy(Events.eventId to SortOrder.ASC)
 *         .limit(limit)
 *         .offset(offset)
 * }.collect { row ->
 *     println(row[Events.eventId])
 * }
 * ```
 *
 * 각 page fetch 전과 item emit 전에 cancellation을 확인합니다. Collection이 취소되면 현재
 * transaction을 닫고 추가 page를 요청하지 않습니다.
 *
 * @param db Trino database connection
 * @param options page size와 initial offset
 * @param dispatcher blocking JDBC 호출에 사용할 dispatcher
 * @param block page query block. Page `limit`과 `offset`을 받아 최대 `limit`개의 row를 반환해야 합니다.
 */
fun <T> pagedQueryFlow(
    db: Database,
    options: TrinoPagedQueryOptions = TrinoPagedQueryOptions(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: Transaction.(limit: Int, offset: Long) -> Iterable<T>,
): Flow<T> = flow {
    var offset = options.initialOffset

    while (true) {
        currentCoroutineContext().ensureActive()

        val page = try {
            withContext(dispatcher) {
                transaction(db) {
                    block(options.pageSize, offset).toList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        }

        check(page.size <= options.pageSize) {
            "pagedQueryFlow block returned ${page.size} rows for pageSize=${options.pageSize}. " +
                    "Apply the provided limit and offset."
        }
        if (page.isEmpty()) break

        for (item in page) {
            currentCoroutineContext().ensureActive()
            emit(item)
        }
        if (page.size < options.pageSize) break

        val nextOffset = offset + page.size.toLong()
        check(nextOffset > offset) { "pagedQueryFlow offset overflow at offset=$offset, pageSize=${page.size}" }
        offset = nextOffset
    }
}
